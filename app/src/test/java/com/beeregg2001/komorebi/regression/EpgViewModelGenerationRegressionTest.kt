package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.EpgSearchResultItem
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Tests production EpgViewModel's request and backend generation boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgViewModelGenerationRegressionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var repository: EpgRepository
    private lateinit var viewModel: EpgViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        backend = MutableStateFlow("KONOMITV")
        repository = Mockito.mock(EpgRepository::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.isInitialized).thenReturn(flowOf(false))
        Mockito.`when`(settings.backendType).thenReturn(backend)
        Mockito.`when`(settings.mirakurunIp).thenReturn(flowOf(""))
        Mockito.`when`(settings.mirakurunPort).thenReturn(flowOf(""))
        val live = Mockito.mock(LiveProvider::class.java)
        runBlocking {
            Mockito.doReturn("test-logo").`when`(live).getChannelLogoUrl(Mockito.anyString())
        }
        viewModel = EpgViewModel(
            repository, live, settings,
            ApplicationProvider.getApplicationContext<Context>()
        )
    }

    @After
    fun teardown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun searchItem(): EpgSearchResultItem {
        val program = Mockito.mock(EpgProgram::class.java)
        Mockito.`when`(program.start_time).thenReturn(OffsetDateTime.now().plusDays(1).toString())
        val channel = Mockito.mock(EpgChannel::class.java)
        Mockito.`when`(channel.name).thenReturn("Existing Network Channel")
        Mockito.`when`(channel.display_channel_id).thenReturn("channel-1")
        return EpgSearchResultItem(program, channel)
    }

    @Test
    fun clearingSearchResetsQueryAndResultsWithoutStartingNewRequest() = runBlocking {
        val item = searchItem()
        Mockito.doReturn(listOf(item)).`when`(repository)
            .searchFuturePrograms("previous query", null, null, false, null)
        viewModel.executeSearch("previous query")
        withTimeout(10_000L) { job("searchJob").join() }
        assertSame(item.program, viewModel.searchResults.value.single().program)
        assertEquals("previous query", viewModel.activeSearchQuery.value)
        Mockito.clearInvocations(repository)
        viewModel.updateSearchQuery("pending query")
        viewModel.clearSearch()
        assertEquals("", viewModel.searchQuery.value)
        assertEquals("", viewModel.activeSearchQuery.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertFalse(viewModel.isSearching.value)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun silentSearchResultIsDiscardedWhenUserClearsSearchMidflight() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = listOf(searchItem())
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "fixture release timed out" }
            data
        }.`when`(repository).searchFuturePrograms("old", null, null, false, null)

        try {
            val pending = async(Dispatchers.Default) { viewModel.searchSilently("old") }
            assertTrue("search did not begin", entered.await(15, TimeUnit.SECONDS))
            viewModel.clearSearch()
            release.countDown()
            assertTrue(withTimeout(15_000L) { pending.await() }.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun silentSearchResultIsDiscardedWhenBackendChangesMidflight() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = listOf(searchItem())
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "fixture release timed out" }
            data
        }.`when`(repository).searchFuturePrograms("old", null, null, false, null)

        try {
            val pending = async(Dispatchers.Default) { viewModel.searchSilently("old") }
            assertTrue("search did not begin", entered.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            release.countDown()
            assertTrue(withTimeout(15_000L) { pending.await() }.isEmpty())
        } finally {
            release.countDown()
        }
    }
    @Test
    fun silentSearchWithoutGenerationChangeReturnsActualResult() = runBlocking {
        val data = listOf(searchItem())
        Mockito.doReturn(data).`when`(repository).searchFuturePrograms("current", null, null, false, null)
        val results = viewModel.searchSilently("current")
        assertEquals("positive control: valid unchanged search must return a result", 1, results.size)
        assertEquals("test-logo", results.single().logoUrl)
    }

    private fun job(name: String): Job {
        val field = EpgViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(viewModel) as Job
    }

    private suspend fun loadEpg(type: String, result: Result<List<EpgChannelWrapper>>) {
        val now = OffsetDateTime.now()
        Mockito.doReturn(flowOf(result)).`when`(repository).getEpgDataStream(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.eq(type) ?: type
        )
        viewModel.refreshEpgData(type)
        withTimeout(10_000L) { job("epgJob").join() }
    }

    @Test
    fun refreshForDifferentUnselectedTypeDoesNotLaunchNewEpgRequest() {
        assertEquals("GR", viewModel.selectedBroadcastingType.value)
        viewModel.refreshEpgData("BS")
        assertEquals("GR", viewModel.selectedBroadcastingType.value)
        assertSame(com.beeregg2001.komorebi.viewmodel.EpgUiState.Loading, viewModel.uiState)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun successfulEmptyEpgDataProducesSuccessInsteadOfStuckLoading() = runBlocking {
        val target = OffsetDateTime.parse("2030-01-02T18:30:00+09:00")
        viewModel.updateTargetTime(target)
        assertSame(EpgUiState.Loading, viewModel.uiState)
        loadEpg("GR", Result.success(emptyList()))
        val state = viewModel.uiState
        assertTrue("valid empty result must not remain Loading", state is com.beeregg2001.komorebi.viewmodel.EpgUiState.Success)
        val success = state as com.beeregg2001.komorebi.viewmodel.EpgUiState.Success
        assertEquals(target, success.targetTime)
        assertTrue(success.data.isEmpty())
        assertTrue(success.logoUrls.isEmpty())
    }

    @Test
    fun latestDateSliceWinsWhenTargetTimeChangesTwice() = runBlocking {
        val target1 = OffsetDateTime.parse("2030-01-02T18:30:00+09:00")
        val target2 = target1.plusDays(1)
        val old = epgFixture("old", "Old day's program", target1.plusHours(2))
        val latest = epgFixture("latest", "Latest day's program", target2.plusHours(2))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNextRead = AtomicBoolean(false)
        val oldProgram = Mockito.spy(old.programs.single())
        Mockito.doAnswer {
            if (blockNextRead.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            it.callRealMethod()
        }.`when`(oldProgram).start_time
        loadEpg("GR", Result.success(listOf(old.copy(programs = listOf(oldProgram)), latest)))
        withTimeout(10_000L) { job("sliceJob").join() }
        try {
            blockNextRead.set(true)
            viewModel.updateTargetTime(target1)
            val oldSlice = job("sliceJob")
            assertTrue("previous day must still be computing", entered.await(15, TimeUnit.SECONDS))
            viewModel.updateTargetTime(target2)
            withTimeout(10_000L) { job("sliceJob").join() }
            release.countDown()
            withTimeout(10_000L) { oldSlice.join() }
            val success = viewModel.uiState as EpgUiState.Success
            assertEquals("stale date must not overwrite latest", target2, success.targetTime)
            assertEquals(latest.programs, success.data.flatMap { it.programs })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun broadcastingTypeSwitchImmediatelyInvalidatesPreviousSuccess() = runBlocking {
        loadEpg("GR", Result.success(emptyList()))
        assertTrue(viewModel.uiState is com.beeregg2001.komorebi.viewmodel.EpgUiState.Success)
        viewModel.updateBroadcastingType("BS")
        assertEquals("BS", viewModel.selectedBroadcastingType.value)
        assertSame(com.beeregg2001.komorebi.viewmodel.EpgUiState.Loading, viewModel.uiState)
    }

    @Test
    fun requestedBroadcastTypeFailureNeverReusesPreviousTypesSuccess() = runBlocking {
        loadEpg("GR", Result.success(emptyList()))
        assertTrue(viewModel.uiState is com.beeregg2001.komorebi.viewmodel.EpgUiState.Success)
        viewModel.updateBroadcastingType("BS")
        loadEpg("BS", Result.failure(IllegalStateException("BS backend unavailable")))
        assertEquals(EpgUiState.Error("BS backend unavailable"), viewModel.uiState)
    }

    @Test
    fun preloadSearchRangeIncludesLastBroadcastDayFourAmBoundary() {
        val fn = EpgViewModel::class.java.getDeclaredMethod(
            "epgRangeEnd", OffsetDateTime::class.java, String::class.java
        )
        fn.isAccessible = true
        val nearMidnight = OffsetDateTime.parse("2030-01-01T00:30:00+09:00")
        val end = fn.invoke(viewModel, nearMidnight, "KONOMITV") as OffsetDateTime
        assertEquals(nearMidnight.toLocalDate().plusDays(7), end.toLocalDate())
        assertEquals(4, end.hour)
        assertFalse(end.isBefore(nearMidnight.plusDays(7)))

        val shortlyAfterFour = OffsetDateTime.parse("2030-01-01T04:30:00+09:00")
        val second = fn.invoke(viewModel, shortlyAfterFour, "EDCB") as OffsetDateTime
        assertEquals(shortlyAfterFour.toLocalDate().plusDays(7), second.toLocalDate())
        assertEquals(4, second.hour)
        assertFalse(second.isBefore(shortlyAfterFour.plusDays(7)))
    }

    @Test
    fun explicitPreloadFiltersInvalidTypesWithoutShrinkingValidBroadcastSet() = runBlocking {
        val now = OffsetDateTime.now()
        Mockito.doReturn(Unit).`when`(repository).fetchAndCacheEpgDataSilently(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.anyString() ?: "GR"
        )
        viewModel.preloadEpgDataForSearch(listOf("GR", "BS", "UNSUPPORTED"))
        val field = EpgViewModel::class.java.getDeclaredField("preloadJob")
        field.isAccessible = true
        withTimeout(10_000L) { (field.get(viewModel) as Job).join() }
        val broadcastTypes = org.mockito.ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(repository, Mockito.times(2)).fetchAndCacheEpgDataSilently(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            broadcastTypes.capture() ?: "GR"
        )
        assertEquals(listOf("BS", "GR"), broadcastTypes.allValues.sorted())
    }

    @Test
    fun newerInteractiveSearchCannotBeOverwrittenByOlderNetworkReply() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldItem = searchItem()
        val newItem = searchItem()
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            listOf(oldItem)
        }.`when`(repository).searchFuturePrograms("older", null, null, false, null)
        Mockito.doReturn(listOf(newItem)).`when`(repository)
            .searchFuturePrograms("newer", null, null, false, null)
        try {
            viewModel.executeSearch("older")
            val olderSearch = job("searchJob")
            assertTrue("older search did not start", entered.await(15, TimeUnit.SECONDS))
            viewModel.executeSearch("newer")
            withTimeout(10_000L) {
                viewModel.searchResults.first { it.singleOrNull()?.program === newItem.program }
            }
            release.countDown()
            withTimeout(10_000L) { olderSearch.join() }
            assertSame(newItem.program, viewModel.searchResults.value.single().program)
            assertEquals("newer", viewModel.activeSearchQuery.value)
            assertFalse(viewModel.isSearching.value)
        } finally { release.countDown() }
    }

    @Test
    fun backendSwitchCancelsInFlightPreloadChildrenWithoutLaunchingOldBackendFetch() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = OffsetDateTime.now()
        Mockito.doAnswer { invocation ->
            if (invocation.arguments[2] == "GR") {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            Unit
        }.`when`(repository).fetchAndCacheEpgDataSilently(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.anyString() ?: "GR"
        )
        try {
            viewModel.preloadEpgDataForSearch(listOf("GR"))
            assertTrue("preload child did not start", entered.await(15, TimeUnit.SECONDS))
            val field = EpgViewModel::class.java.getDeclaredField("preloadJob")
            field.isAccessible = true
            val oldJob = field.get(viewModel) as Job
            backend.value = "EDCB"
            release.countDown()
            withTimeout(10_000L) { oldJob.join() }
            assertTrue("preload from previous backend must be cancelled", oldJob.isCancelled)
            assertEquals("GR", viewModel.selectedBroadcastingType.value)
            assertSame(com.beeregg2001.komorebi.viewmodel.EpgUiState.Loading, viewModel.uiState)
        } finally { release.countDown() }
    }


    @Test
    fun oldSearchFailureDoesNotStopNewerStillRunningSearch() = runBlocking {
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        val newItem = searchItem()
        Mockito.doAnswer {
            oldEntered.countDown()
            check(oldRelease.await(15, TimeUnit.SECONDS))
            throw IllegalStateException("obsolete search failure")
        }.`when`(repository).searchFuturePrograms("older-error", null, null, false, null)
        Mockito.doAnswer {
            newEntered.countDown()
            check(newRelease.await(15, TimeUnit.SECONDS))
            listOf(newItem)
        }.`when`(repository).searchFuturePrograms("newer-pending", null, null, false, null)
        try {
            viewModel.executeSearch("older-error")
            val oldJob = job("searchJob")
            assertTrue(oldEntered.await(15, TimeUnit.SECONDS))
            viewModel.executeSearch("newer-pending")
            assertTrue(newEntered.await(15, TimeUnit.SECONDS))
            oldRelease.countDown()
            withTimeout(10_000L) { oldJob.join() }
            assertTrue("obsolete finally must not clear current spinner", viewModel.isSearching.value)
            assertEquals("newer-pending", viewModel.activeSearchQuery.value)
            assertTrue(viewModel.searchResults.value.isEmpty())
            newRelease.countDown()
            withTimeout(10_000L) { job("searchJob").join() }
            assertSame(newItem.program, viewModel.searchResults.value.single().program)
            assertFalse(viewModel.isSearching.value)
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
        }
    }

    @Test
    fun silentSearchCancellationPropagatesInsteadOfBecomingEmptyList() = runBlocking {
        Mockito.doAnswer { throw CancellationException("search cancelled by caller") }
            .`when`(repository).searchFuturePrograms("cancel", null, null, false, null)
        try {
            viewModel.searchSilently("cancel")
            fail("CancellationException must not be converted into an empty search")
        } catch (expected: CancellationException) {
            assertEquals("search cancelled by caller", expected.message)
        }
    }


    @Test
    fun unselectedBsRefreshMustNotCancelAlreadyRunningGrRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val now = OffsetDateTime.now()
        Mockito.doReturn(flow {
            started.complete(Unit)
            release.await()
            emit(Result.success(listOf(epgFixture("gr", "GR in flight", now))))
        }).`when`(repository).getEpgDataStream(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.eq("GR") ?: "GR"
        )
        try {
            viewModel.refreshEpgData("GR")
            val active = job("epgJob")
            withTimeout(10_000L) { started.await() }
            assertTrue(active.isActive)
            viewModel.refreshEpgData("BS")
            assertEquals("GR", viewModel.selectedBroadcastingType.value)
            assertTrue("unselected BS request must not cancel active GR", active.isActive)
            release.complete(Unit)
            withTimeout(10_000L) { active.join() }
            assertTrue(viewModel.uiState is EpgUiState.Success)
        } finally { release.complete(Unit) }
    }


    @Test
    fun sameBroadcastTypeFailedRefreshRetainsPreviouslyDisplayedNonemptyCache() = runBlocking {
        val now = OffsetDateTime.now()
        val old = epgFixture("own-gr", "Previously cached GR", now)
        loadEpg("GR", Result.success(listOf(old)))
        withTimeout(10_000L) { job("sliceJob").join() }
        val before = viewModel.uiState as EpgUiState.Success
        assertTrue(before.data.flatMap { it.programs }.any { it.title == "Previously cached GR" })
        loadEpg("GR", Result.failure(IllegalStateException("offline refresh rejected")))
        withTimeout(10_000L) { job("sliceJob").join() }
        val retained = viewModel.uiState as EpgUiState.Success
        assertEquals(before.data, retained.data)
        assertTrue(viewModel.selectedBroadcastingType.value == "GR")
    }


    @Test
    fun initializedVmSwitchBeforeScheduledTenSecondPreloadInvalidatesPreviousEpg() = runBlocking {
        viewModel.viewModelScope.cancel()
        val initialized = MutableStateFlow(true)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.isInitialized).thenReturn(initialized)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        Mockito.`when`(settings.mirakurunIp).thenReturn(flowOf(""))
        Mockito.`when`(settings.mirakurunPort).thenReturn(flowOf(""))
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn("logo").`when`(live).getChannelLogoUrl(Mockito.anyString())
        val now = OffsetDateTime.now()
        val old = listOf(epgFixture("old-owner", "previous EPG", now))
        val latest = listOf(epgFixture("new-owner", "replacement EPG", now))
        Mockito.doAnswer {
            flowOf(Result.success(if (backend.value == "KONOMITV") old else latest))
        }.`when`(repository).getEpgDataStream(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.eq("GR") ?: "GR"
        )
        Mockito.doReturn(Unit).`when`(repository).fetchAndCacheEpgDataSilently(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.anyString() ?: "GR"
        )
        viewModel = EpgViewModel(
            repository, live, settings, ApplicationProvider.getApplicationContext()
        )
        withTimeout(10_000L) {
            while ((viewModel.uiState as? EpgUiState.Success)?.data?.isEmpty() != false)
                kotlinx.coroutines.delay(10L)
        }
        val oldPreload = job("preloadJob")
        backend.value = "EDCB"
        withTimeout(10_000L) {
            while ((viewModel.uiState as? EpgUiState.Success)?.data?.any {
                    it.channel.id == "new-owner"
                } != true) kotlinx.coroutines.delay(10L)
        }
        assertTrue(oldPreload.isCancelled)
        assertEquals("GR", viewModel.selectedBroadcastingType.value)
        val replacement = viewModel.uiState as EpgUiState.Success
        assertTrue(replacement.data.none { it.channel.id == "old-owner" })
        Mockito.verify(repository, Mockito.never()).fetchAndCacheEpgDataSilently(
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.any(OffsetDateTime::class.java) ?: now,
            Mockito.anyString() ?: "GR"
        )
    }


    @Test
    fun nonemptyPreviousBroadcastSliceCannotOverwriteNewBroadcastTypeAfterCancellation() = runBlocking {
        val now = OffsetDateTime.now().plusHours(2)
        val old = epgFixture("gr-old", "Previous GR programme", now)
        val current = epgFixture("bs-current", "Latest BS programme", now)
        val spy = Mockito.spy(old.programs.single())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        Mockito.doAnswer {
            if (blockNext.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            it.callRealMethod()
        }.`when`(spy).start_time
        loadEpg("GR", Result.success(listOf(old.copy(programs = listOf(spy)))))
        withTimeout(10_000L) { job("sliceJob").join() }
        try {
            blockNext.set(true)
            viewModel.updateTargetTime(now.plusMinutes(10))
            val oldSlice = job("sliceJob")
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            viewModel.updateBroadcastingType("BS")
            loadEpg("BS", Result.success(listOf(current)))
            withTimeout(10_000L) { job("sliceJob").join() }
            release.countDown()
            withTimeout(10_000L) { oldSlice.join() }
            assertEquals("BS", viewModel.selectedBroadcastingType.value)
            val result = viewModel.uiState as EpgUiState.Success
            assertTrue(result.data.any { it.channel.id == "bs-current" })
            assertTrue(result.data.none { it.channel.id == "gr-old" })
        } finally { release.countDown() }
    }

}
