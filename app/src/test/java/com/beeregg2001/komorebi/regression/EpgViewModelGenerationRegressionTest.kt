package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.EpgSearchResultItem
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            Mockito.doReturn("test-logo").`when`(live).getChannelLogoUrl("channel-1")
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
    fun clearingSearchResetsQueryAndResultsWithoutStartingNewRequest() {
        viewModel.updateSearchQuery("pending query")
        viewModel.clearSearch()
        assertEquals("", viewModel.searchQuery.value)
        assertEquals("", viewModel.activeSearchQuery.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertFalse(viewModel.isSearching.value)
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

    private fun setPrivate(name: String, value: Any) {
        val field = EpgViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(viewModel, value)
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
    fun successfulEmptyEpgDataProducesSuccessInsteadOfStuckLoading() {
        val target = OffsetDateTime.parse("2030-01-02T18:30:00+09:00")
        setPrivate("hasDisplayedDataForType", true)
        setPrivate("fullEpgData", emptyList<com.beeregg2001.komorebi.data.model.EpgChannelWrapper>())
        viewModel.updateTargetTime(target)
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
        val channel = Mockito.mock(EpgChannel::class.java)
        val program = Mockito.mock(EpgProgram::class.java)
        Mockito.`when`(program.start_time).thenReturn(target2.plusHours(2).toString())
        Mockito.`when`(program.end_time).thenReturn(target2.plusHours(3).toString())
        setPrivate("hasDisplayedDataForType", true)
        setPrivate("displayedBroadcastingType", "GR")
        setPrivate("fullEpgData", listOf(com.beeregg2001.komorebi.data.model.EpgChannelWrapper(channel, listOf(program))))
        viewModel.updateTargetTime(target1)
        viewModel.updateTargetTime(target2)
        val field = EpgViewModel::class.java.getDeclaredField("sliceJob")
        field.isAccessible = true
        val job = field.get(viewModel) as kotlinx.coroutines.Job
        withTimeout(10_000L) { job.join() }
        val ui = viewModel.uiState
        assertTrue(ui is com.beeregg2001.komorebi.viewmodel.EpgUiState.Success)
        val success = ui as com.beeregg2001.komorebi.viewmodel.EpgUiState.Success
        assertEquals("stale date must not overwrite latest", target2, success.targetTime)
        assertEquals(1, success.data.single().programs.size)
    }

    @Test
    fun broadcastingTypeSwitchImmediatelyInvalidatesPreviousSuccess() {
        setPrivate("hasDisplayedDataForType", true)
        viewModel.updateTargetTime(OffsetDateTime.parse("2030-01-02T18:00:00+09:00"))
        assertTrue(viewModel.uiState is com.beeregg2001.komorebi.viewmodel.EpgUiState.Success)
        viewModel.updateBroadcastingType("BS")
        assertEquals("BS", viewModel.selectedBroadcastingType.value)
        assertSame(com.beeregg2001.komorebi.viewmodel.EpgUiState.Loading, viewModel.uiState)
    }

}
