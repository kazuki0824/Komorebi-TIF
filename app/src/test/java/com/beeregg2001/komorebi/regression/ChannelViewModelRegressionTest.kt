package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests real ChannelViewModel fetch and backend-switch error handling.
 * The mocked provider models the upstream network response; no live tuner required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelViewModelRegressionTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun settings(backend: MutableStateFlow<String>): SettingsRepository {
        val result = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(result.backendType).thenReturn(backend)
        Mockito.`when`(result.hideSubChannels).thenReturn(flowOf(false))
        return result
    }

    private fun viewModel(live: LiveProvider, backend: MutableStateFlow<String>) =
        ChannelViewModel(
            live, Mockito.mock(RecordProvider::class.java),
            Mockito.mock(WatchHistoryRepository::class.java), settings(backend)
        )

    private fun fetchJob(vm: ChannelViewModel): Job =
        ChannelViewModel::class.java.getDeclaredField("fetchJob").let { field ->
            field.isAccessible = true
            field.get(vm) as Job
        }

    private suspend fun awaitObservedBackend(vm: ChannelViewModel, backend: String) {
        val field = ChannelViewModel::class.java.getDeclaredField("observedBackend")
        field.isAccessible = true
        withTimeout(10_000L) {
            while (field.get(vm) != backend) kotlinx.coroutines.delay(10L)
        }
    }

    @Test
    fun successfulEmptyChannelListIsNotMisclassifiedAsConnectionFailure() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        try {
            vm.fetchChannels(force = true)
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            Mockito.verify(live, Mockito.atLeastOnce()).getChannels()
            assertFalse(vm.connectionError.value)
            assertTrue(vm.groupedChannels.value.isEmpty())
            assertTrue(vm.liveRows.value.isEmpty())
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun changingBackendClearsPreviousTransportFailureAndAcceptsNewEmptyResult() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        val shouldFail = AtomicBoolean(true)
        val successfulRequests = AtomicInteger()
        Mockito.doAnswer {
            if (shouldFail.get()) throw IllegalStateException("old backend unavailable")
            successfulRequests.incrementAndGet()
            ChannelApiResponse()
        }.`when`(live).getChannels()

        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        try {
            vm.fetchChannels(force = true)
            withTimeout(10_000L) { vm.connectionError.first { it } }
            shouldFail.set(false)
            backend.value = "EDCB"
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertEquals(1, successfulRequests.get())
            assertFalse(vm.connectionError.value)
            assertTrue(vm.groupedChannels.value.isEmpty())
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }
    @Test
    fun nonForcedFetchIsRejectedWhileAnActiveFetchJobAlreadyExists() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val vm = viewModel(live, MutableStateFlow("KONOMITV"))
        vm.stopPolling()
        val field = ChannelViewModel::class.java.getDeclaredField("fetchJob")
        field.isAccessible = true
        // Inject an active request at the exact production guard boundary.
        // Unlike timing-based latches, this cannot race with startup polling.
        val activeRequest = kotlinx.coroutines.Job()
        try {
            Mockito.clearInvocations(live)
            field.set(vm, activeRequest)
            vm.fetchChannels(force = false)
            vm.fetchChannels(force = false)
            assertSame("non-forced calls must not replace the active request", activeRequest, field.get(vm))
            Mockito.verifyNoInteractions(live)
        } finally {
            activeRequest.cancel()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun backendChangeWhileOldFetchBlockedRejectsOldBackendTransportFailure() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val live = Mockito.mock(LiveProvider::class.java)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        vm.stopPolling()
        awaitObservedBackend(vm, "KONOMITV")
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                throw IllegalStateException("obsolete backend error")
            }
            ChannelApiResponse(terrestrial = listOf(networkChannel("new-edcb-101")))
        }.`when`(live).getChannels()
        try {
            vm.fetchChannels(force = true)
            val oldRequest = fetchJob(vm)
            assertTrue("old provider request never started", started.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            withTimeout(10_000L) {
                vm.groupedChannels.first { it["GR"]?.singleOrNull()?.id == "new-edcb-101" }
            }
            release.countDown()
            withTimeout(10_000L) { oldRequest.join() }
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertFalse("old provider failure cannot contaminate new backend", vm.connectionError.value)
            assertEquals("new-edcb-101", vm.groupedChannels.value["GR"]?.single()?.id)
        } finally {
            release.countDown()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun fetchedChannelRetainsOriginalTransportStreamIdWithoutReconstructingIdentity() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        val input = com.beeregg2001.komorebi.data.model.Channel(
            id = "edcb:network:service", displayChannelId = "101",
            name = "Transport stream fixture", channelNumber = "101",
            networkId = 1234L, serviceId = 5678L, transportStreamId = 0x1234L,
            type = "GR", isWatchable = true, isDisplay = true,
            programPresent = null, programFollowing = null, remocon_Id = 1
        )
        Mockito.doReturn(ChannelApiResponse(terrestrial = listOf(input)))
            .`when`(live).getChannels()
        val vm = viewModel(live, MutableStateFlow("EDCB"))
        try {
            vm.stopPolling()
            vm.fetchChannels(force = true)
            val channel = withTimeout(10_000L) {
                vm.groupedChannels.first { it["GR"]?.isNotEmpty() == true }
                    .getValue("GR").single()
            }
            assertEquals("edcb:network:service", channel.id)
            assertEquals(0x1234L, channel.transportStreamId)
            assertEquals(1234L, channel.networkId)
            assertEquals(5678L, channel.serviceId)
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    private fun networkChannel(id: String) = Channel(
        id = id, displayChannelId = id, name = id, channelNumber = "101",
        networkId = 11L, serviceId = 55L, transportStreamId = 4L,
        type = "GR", isWatchable = true, isDisplay = true,
        programPresent = null, programFollowing = null, remocon_Id = 1
    )

    @Test
    fun oldSuccessfulFetchMustNotOverwriteNewBackendChannels() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val live = Mockito.mock(LiveProvider::class.java)
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        vm.stopPolling()
        // The backend collector starts on Dispatchers.Default in this test.
        // Establish its actual initial observation before changing the backend;
        // otherwise "EDCB" may be the first value it sees, not a transition.
        awaitObservedBackend(vm, "KONOMITV")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "old provider stalled" }
                ChannelApiResponse(terrestrial = listOf(networkChannel("old-kon-101")))
            } else ChannelApiResponse(terrestrial = listOf(networkChannel("new-edcb-101")))
        }.`when`(live).getChannels()
        try {
            vm.fetchChannels(force = true)
            val oldRequest = fetchJob(vm)
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            withTimeout(10_000L) {
                vm.groupedChannels.first { it["GR"]?.singleOrNull()?.id == "new-edcb-101" }
            }
            release.countDown()
            withTimeout(10_000L) { oldRequest.join() }
            assertEquals("new-edcb-101", vm.groupedChannels.value["GR"]?.single()?.id)
            assertFalse(vm.connectionError.value)
            assertFalse(vm.isLoading.value)
        } finally {
            release.countDown()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun laterForcedFetchWinsEvenIfEarlierSuccessCompletesLast() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val live = Mockito.mock(LiveProvider::class.java)
        val vm = viewModel(live, MutableStateFlow("EDCB"))
        vm.stopPolling()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                ChannelApiResponse(terrestrial = listOf(networkChannel("older-request")))
            } else ChannelApiResponse(terrestrial = listOf(networkChannel("later-request")))
        }.`when`(live).getChannels()
        try {
            vm.fetchChannels(force = true)
            val oldRequest = fetchJob(vm)
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            vm.fetchChannels(force = true)
            withTimeout(10_000L) {
                vm.groupedChannels.first { it["GR"]?.singleOrNull()?.id == "later-request" }
            }
            release.countDown()
            withTimeout(10_000L) { oldRequest.join() }
            assertEquals("later-request", vm.groupedChannels.value["GR"]?.single()?.id)
            assertFalse(vm.isLoading.value)
        } finally {
            release.countDown()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun savedRecordingHistoryIsAssignedExplicitlyToCurrentBackend() = runBlocking {
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        val backend = MutableStateFlow("EDCB")
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val vm = ChannelViewModel(
            live, Mockito.mock(RecordProvider::class.java),
            history, settings(backend)
        )
        val program = RecordedProgram(
            id = 77, title = "source-bound recording", description = "",
            startTime = "2030-01-01T00:00:00+09:00",
            endTime = "2030-01-01T01:00:00+09:00",
            duration = 3600.0, isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(
                id = 77, status = "Recorded", filePath = "recorded.ts",
                recordingStartTime = "2030-01-01T00:00:00+09:00",
                recordingEndTime = "2030-01-01T01:00:00+09:00",
                duration = 3600.0, containerFormat = "ts",
                videoCodec = "H264", audioCodec = "AAC"
            )
        )
        try {
            vm.saveToHistory(program)
            Mockito.verify(history, Mockito.timeout(10_000L)).saveToLocalHistory(
                Mockito.argThat<WatchHistoryEntity> { it.id == 77 && it.title == "source-bound recording" }
                    ?: WatchHistoryEntity(0, "", "", null, "", "", 0.0, 0),
                Mockito.eq("EDCB") ?: "EDCB"
            )
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun backendChangeReplacesOldProgressUpdaterWithNewGenerationWorker() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        val field = ChannelViewModel::class.java.getDeclaredField("progressUpdateJob")
        field.isAccessible = true
        try {
            vm.stopPolling()
            vm.startPolling()
            val old = field.get(vm) as kotlinx.coroutines.Job
            assertTrue(old.isActive)
            backend.value = "EDCB"
            val newJob = withTimeout(10_000L) {
                while (true) {
                    val current = field.get(vm) as kotlinx.coroutines.Job
                    if (current !== old) break
                    kotlinx.coroutines.delay(10L)
                }
                field.get(vm) as kotlinx.coroutines.Job
            }
            assertTrue("old backend progress updater must stop", old.isCancelled)
            assertTrue("new backend must have fresh progress updater", newJob.isActive)
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }


    @Test
    fun switchingBackendInvalidatesLogoCacheEvenWithIdenticalChannelId() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val url = java.util.concurrent.atomic.AtomicReference("https://old.example/logo.png")
        Mockito.doAnswer { url.get() }.`when`(live).getChannelLogoUrl("shared-101")
        val vm = viewModel(live, backend)
        try {
            vm.stopPolling()
            awaitObservedBackend(vm, "KONOMITV")
            assertEquals("https://old.example/logo.png", vm.getChannelLogoUrl("shared-101"))
            url.set("https://new.example/logo.png")
            backend.value = "EDCB"
            awaitObservedBackend(vm, "EDCB")
            assertEquals("https://new.example/logo.png", vm.getChannelLogoUrl("shared-101"))
            Mockito.verify(live, Mockito.times(2)).getChannelLogoUrl("shared-101")
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }


    @Test
    fun actualInitialPollingFetchCannotOverwriteLaterForcedChannelRequest() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val live = Mockito.mock(LiveProvider::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                ChannelApiResponse(terrestrial = listOf(networkChannel("stale-initial-poll")))
            } else {
                ChannelApiResponse(terrestrial = listOf(networkChannel("explicit-refresh")))
            }
        }.`when`(live).getChannels()
        // Production init starts the polling path; this is not a manual fetch mock.
        val vm = viewModel(live, MutableStateFlow("KONOMITV"))
        try {
            assertTrue("initial polling request must have reached provider", entered.await(15, TimeUnit.SECONDS))
            vm.fetchChannels(force = true)
            withTimeout(10_000L) {
                vm.groupedChannels.first {
                    it["GR"]?.singleOrNull()?.id == "explicit-refresh"
                }
            }
            release.countDown()
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertEquals("explicit-refresh", vm.groupedChannels.value["GR"]?.single()?.id)
            assertFalse(vm.connectionError.value)
        } finally {
            release.countDown()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }


    @Test
    fun obsoleteFetchFinallyCannotClearLoadingWhileNewBackendFetchIsStillPending() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val live = Mockito.mock(LiveProvider::class.java)
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        Mockito.doAnswer {
            when (calls.incrementAndGet()) {
                1 -> {
                    oldEntered.countDown()
                    check(oldRelease.await(15, TimeUnit.SECONDS))
                    throw IllegalStateException("obsolete fetch failed")
                }
                else -> {
                    newEntered.countDown()
                    check(newRelease.await(15, TimeUnit.SECONDS))
                    ChannelApiResponse(terrestrial = listOf(networkChannel("new-backend")))
                }
            }
        }.`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        vm.stopPolling()
        awaitObservedBackend(vm, "KONOMITV")
        try {
            vm.fetchChannels(force = true)
            val obsoleteJob = fetchJob(vm)
            assertTrue(oldEntered.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            assertTrue(newEntered.await(15, TimeUnit.SECONDS))
            assertTrue(vm.isLoading.value)
            oldRelease.countDown()
            withTimeout(10_000L) { obsoleteJob.join() }
            assertTrue("old finally cannot end latest loading", vm.isLoading.value)
            assertFalse("old failure cannot mark new request failed", vm.connectionError.value)
            newRelease.countDown()
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertEquals("new-backend", vm.groupedChannels.value["GR"]?.single()?.id)
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

}
