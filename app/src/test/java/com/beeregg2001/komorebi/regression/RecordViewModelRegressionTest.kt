package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.ChannelProjection
import com.beeregg2001.komorebi.data.local.dao.SeriesProjection
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationRecordRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production RecordViewModel: recording visibility, search restoration and sync delegation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordViewModelRegressionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var recordedRows: MutableStateFlow<List<RecordedProgramEntity>>
    private lateinit var programDaoUnderTest: RecordedProgramDao
    private lateinit var provider: RecordProvider
    private lateinit var sync: RecordSyncEngine
    private lateinit var cacheReady: MutableStateFlow<Boolean>
    private lateinit var syncState: MutableStateFlow<SyncProgress>
    private lateinit var viewModel: RecordViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        provider = Mockito.mock(RecordProvider::class.java)
        sync = Mockito.mock(RecordSyncEngine::class.java)
        cacheReady = MutableStateFlow(false)
        syncState = MutableStateFlow(SyncProgress(isSyncing = true))
        Mockito.`when`(sync.recordCacheReady).thenReturn(cacheReady)
        Mockito.`when`(sync.recordCacheGeneration).thenReturn(MutableStateFlow(0L))
        Mockito.`when`(sync.syncProgress).thenReturn(syncState)
        Mockito.`when`(sync.recordPublicationLock).thenReturn(Any())
        Mockito.doReturn(false).`when`(sync).isInitialBuildCompleted()

        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf("KONOMITV"))
        Mockito.`when`(settings.defaultRecordListView).thenReturn(flowOf("LIST"))
        val dao = Mockito.mock(RecordedProgramDao::class.java)
        programDaoUnderTest = dao
        recordedRows = MutableStateFlow(emptyList())
        Mockito.`when`(dao.getRecentRecordingsFlow()).thenReturn(recordedRows)
        Mockito.doReturn(emptyList<SeriesProjection>()).`when`(dao).getGroupedSeries()
        Mockito.doReturn(emptyList<ChannelProjection>()).`when`(dao).getDistinctChannels()
        viewModel = RecordViewModel(
            Mockito.mock(LiveProvider::class.java), provider,
            Mockito.mock(EpgStationRecordRepository::class.java),
            Mockito.mock(ReserveProvider::class.java),
            Mockito.mock(WatchHistoryRepository::class.java),
            settings, sync, dao, ApplicationProvider.getApplicationContext<Context>()
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun searchRestoresPreviouslySelectedRecordingCategory() {
        viewModel.updateChannel("channel-1")
        assertEquals(RecordCategory.CHANNEL, viewModel.selectedCategory.value)
        viewModel.searchRecordings("needle")
        assertEquals("needle", viewModel.activeSearchQuery.value)
        assertEquals(RecordCategory.ALL, viewModel.selectedCategory.value)
        viewModel.clearSearch()
        assertEquals(RecordCategory.CHANNEL, viewModel.selectedCategory.value)
        assertEquals("", viewModel.activeSearchQuery.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun unpublishedRecordedCacheBlocksProgramDetailNetworkAccess() = runBlocking {
        viewModel.fetchProgramDetail(42)
        delay(600L) // Production's detail job waits 300 ms before checking ownership gate.
        Mockito.verify(provider, Mockito.never()).getRecordedProgram(42)
        assertNull(viewModel.programDetail.value)
    }

    @Test
    fun forcedRefreshRetainsFullSyncIntent() {
        viewModel.fetchRecentRecordings(forceRefresh = true)
        Mockito.verify(sync).launchSyncAllRecords(forceFullSync = true)
    }
    @Test
    fun recordingDetailAppearsOnlyWhileCacheIsOwnedAndReady() = runBlocking {
        syncState.value = SyncProgress(isSyncing = true)
        cacheReady.value = true
        val program = Mockito.mock(RecordedProgram::class.java)
        Mockito.doReturn(program).`when`(provider).getRecordedProgram(42)
        viewModel.fetchProgramDetail(42)
        withTimeout(10_000L) { viewModel.programDetail.first { it === program } }
        assertSame(program, viewModel.programDetail.value)
        cacheReady.value = false
        withTimeout(10_000L) { viewModel.programDetail.first { it == null } }
        assertNull(viewModel.programDetail.value)
    }

    @Test
    fun inFlightDetailCannotPublishAfterRecordedCacheIsRevoked() = runBlocking {
        syncState.value = SyncProgress(isSyncing = true)
        cacheReady.value = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val program = Mockito.mock(RecordedProgram::class.java)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "fixture release timed out" }
            program
        }.`when`(provider).getRecordedProgram(43)
        try {
            viewModel.fetchProgramDetail(43)
            assertTrue("record provider never started", entered.await(15, TimeUnit.SECONDS))
            cacheReady.value = false
            release.countDown()
            // Join the actual production job; do not pass while the old response is still blocked.
            val field = RecordViewModel::class.java.getDeclaredField("detailFetchJob")
            field.isAccessible = true
            val detailJob = field.get(viewModel) as Job
            withTimeout(10_000L) { detailJob.join() }
            assertNull(viewModel.programDetail.value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun unownedRecordedCacheDoesNotConstructAnyPagingQuery(): Unit = runBlocking {
        assertFalse(cacheReady.value)
        // The production flatMapLatest emits the empty PagingData branch without
        // constructing a real DAO PagingSource when the owner is invalid.
        val result = withTimeout(10_000L) { viewModel.pagedRecordings.first() }
        assertNotNull("unowned cache still emits the empty PagingData branch", result)
        Mockito.verify(programDaoUnderTest, Mockito.never()).getAll_DateDesc()
        Mockito.verify(programDaoUnderTest, Mockito.never()).getAll_DateAsc()
    }

    @Test
    fun recentRecordingRowsRemainHiddenUntilCacheOwnershipIsConfirmedAndHideAgainAfterRevocation() = runBlocking {
        val subscription = launch { viewModel.recentRecordings.collect { } }
        try {
            recordedRows.value = listOf(
                RecordedProgramEntity(
                    id = 42, title = "owner-gated-record", startTime = "2030-01-01T00:00:00+09:00",
                    endTime = "2030-01-01T01:00:00+09:00", videoDuration = 3600.0,
                    hasKeyFrames = true, isRecording = false, playbackPosition = 10.0,
                    channelId = null, channelType = null, channelName = null, genres = null
                )
            )
            // Let the real combine observe a populated DAO while ownership is false.
            delay(80L)
            assertTrue("unowned Room rows must not leak", viewModel.recentRecordings.value.isEmpty())
            cacheReady.value = true
            val exposed = withTimeout(10_000L) {
                viewModel.recentRecordings.first { it.size == 1 }
            }
            assertEquals(42, exposed.single().id)
            assertEquals("owner-gated-record", exposed.single().title)
            cacheReady.value = false
            withTimeout(10_000L) {
                viewModel.recentRecordings.first { it.isEmpty() }
            }
            assertTrue("revoked cache must hide formerly visible rows", viewModel.recentRecordings.value.isEmpty())
        } finally {
            subscription.cancel()
        }
    }

}
