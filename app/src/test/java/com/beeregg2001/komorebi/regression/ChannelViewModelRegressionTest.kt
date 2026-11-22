package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
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

    @Test
    fun successfulEmptyChannelListIsNotMisclassifiedAsConnectionFailure() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        try {
            vm.fetchChannels(force = true)
            withTimeout(10_000L) { vm.isLoading.first { !it } }
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
        Mockito.doAnswer {
            if (shouldFail.get()) throw IllegalStateException("old backend unavailable")
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
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                throw IllegalStateException("obsolete backend error")
            }
            ChannelApiResponse()
        }.`when`(live).getChannels()
        try {
            val trigger = async(Dispatchers.IO) { vm.fetchChannels(force = true) }
            assertTrue("old provider request never started", started.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            release.countDown()
            withTimeout(10_000L) { trigger.await() }
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertFalse("old provider failure cannot contaminate new backend", vm.connectionError.value)
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

}
