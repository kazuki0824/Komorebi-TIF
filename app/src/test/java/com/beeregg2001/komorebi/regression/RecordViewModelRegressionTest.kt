package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.ChannelProjection
import com.beeregg2001.komorebi.data.local.dao.SeriesProjection
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.RecordedVideo
import kotlinx.coroutines.async
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
    private lateinit var ownerBackend: MutableStateFlow<String>
    private lateinit var ownerGeneration: MutableStateFlow<Long>
    private lateinit var historyRepo: WatchHistoryRepository
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
        ownerGeneration = MutableStateFlow(0L)
        Mockito.`when`(sync.recordCacheGeneration).thenReturn(ownerGeneration)
        Mockito.`when`(sync.syncProgress).thenReturn(syncState)
        Mockito.`when`(sync.recordPublicationLock).thenReturn(Any())
        Mockito.doReturn(false).`when`(sync).isInitialBuildCompleted()

        val settings = Mockito.mock(SettingsRepository::class.java)
        ownerBackend = MutableStateFlow("KONOMITV")
        Mockito.`when`(settings.backendType).thenReturn(ownerBackend)
        Mockito.`when`(settings.defaultRecordListView).thenReturn(flowOf("LIST"))
        val dao = Mockito.mock(RecordedProgramDao::class.java)
        programDaoUnderTest = dao
        recordedRows = MutableStateFlow(emptyList())
        Mockito.`when`(dao.getRecentRecordingsFlow()).thenReturn(recordedRows)
        Mockito.doReturn(emptyList<SeriesProjection>()).`when`(dao).getGroupedSeries()
        Mockito.doReturn(emptyList<ChannelProjection>()).`when`(dao).getDistinctChannels()
        historyRepo = Mockito.mock(WatchHistoryRepository::class.java)
        viewModel = RecordViewModel(
            Mockito.mock(LiveProvider::class.java), provider,
            Mockito.mock(EpgStationRecordRepository::class.java),
            Mockito.mock(ReserveProvider::class.java),
            historyRepo,
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
        withTimeout(10_000L) { detailFetchJob().join() }
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

    private fun detailFetchJob(): Job =
        RecordViewModel::class.java.getDeclaredField("detailFetchJob").let { field ->
            field.isAccessible = true
            field.get(viewModel) as Job
        }

    @Test
    fun inFlightDetailCannotPublishAfterRecordedCacheIsRevokedAndRebuilt() = runBlocking {
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
            val oldRequest = detailFetchJob()
            cacheReady.value = false
            assertTrue("revoking old cache must cancel its detail request", oldRequest.isCancelled)
            ownerGeneration.value = 1L
            ownerBackend.value = "EDCB"
            cacheReady.value = true
            release.countDown()
            withTimeout(10_000L) { oldRequest.join() }
            assertNull("new cache readiness must not publish the old backend's detail", viewModel.programDetail.value)
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
        val subscription = launch(Dispatchers.Main.immediate) { viewModel.recentRecordings.collect { } }
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
            dispatcher.scheduler.runCurrent()
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

    private fun historyEntry(id: Int = 55) = WatchHistoryEntity(
        id = id, title = "owned history", description = "", detailJson = null,
        startTime = "2030-01-01T00:00:00+09:00",
        endTime = "2030-01-01T01:00:00+09:00", duration = 3600.0,
        videoId = id, playbackPosition = 37.0
    )

    private fun remoteProgram(id: Int = 55) = RecordedProgram(
        id = id, title = "verified network recording", description = "",
        startTime = "2030-01-01T00:00:00+09:00",
        endTime = "2030-01-01T01:00:00+09:00", duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = id, status = "Recorded", filePath = "recorded.ts",
            recordingStartTime = "2030-01-01T00:00:00+09:00",
            recordingEndTime = "2030-01-01T01:00:00+09:00",
            duration = 3600.0, containerFormat = "ts",
            videoCodec = "H264", audioCodec = "AAC"
        )
    )

    @Test
    fun resumeRejectsOtherBackendBeforeHistoryOrNetworkRead(): Unit = runBlocking {
        ownerBackend.value = "EDCB"
        assertNull(viewModel.getResumableHistoryProgram(55, "KONOMITV"))
        assertTrue(viewModel.historyResumeError.value.orEmpty().contains("バックエンド"))
        Mockito.verify(historyRepo, Mockito.never()).getHistoryEntityById(
            Mockito.anyInt(), Mockito.anyString()
        )
        Mockito.verify(provider, Mockito.never()).getRecordedProgram(55)
    }

    @Test
    fun resumeRejectsMissingHistoryBeforeRemoteLookup(): Unit = runBlocking {
        assertNull(viewModel.getResumableHistoryProgram(55, "KONOMITV"))
        assertTrue(viewModel.historyResumeError.value.orEmpty().contains("視聴履歴"))
        Mockito.verify(provider, Mockito.never()).getRecordedProgram(55)
    }

    @Test
    fun resumeObtainsUncachedRecordingOnlyFromMatchingBackendAndRestoresPosition(): Unit = runBlocking {
        val saved = historyEntry()
        Mockito.doReturn(saved).`when`(historyRepo).getHistoryEntityById(55, "KONOMITV")
        Mockito.doReturn(remoteProgram()).`when`(provider).getRecordedProgram(55)
        assertFalse(cacheReady.value)
        val resumed = viewModel.getResumableHistoryProgram(55, "KONOMITV")
        assertNotNull(resumed)
        assertEquals(55, resumed?.id)
        assertEquals(37.0, resumed?.playbackPosition ?: -1.0, 0.001)
        assertNull(viewModel.historyResumeError.value)
        Mockito.verify(provider).getRecordedProgram(55)
    }

    @Test
    fun resumeRejectsRemoteIdCollisionEvenWithMatchingOwnerHistory() = runBlocking {
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        Mockito.doReturn(remoteProgram(999)).`when`(provider).getRecordedProgram(55)
        assertNull(viewModel.getResumableHistoryProgram(55, "KONOMITV"))
        assertTrue(viewModel.historyResumeError.value.orEmpty().contains("録画詳細"))
    }

    @Test
    fun resumeRejectsInFlightRecordingWhenCacheGenerationChanges() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            remoteProgram()
        }.`when`(provider).getRecordedProgram(55)
        try {
            val pending = async(Dispatchers.Default) {
                viewModel.getResumableHistoryProgram(55, "KONOMITV")
            }
            assertTrue("network lookup not entered", entered.await(15, TimeUnit.SECONDS))
            ownerGeneration.value = 1L
            release.countDown()
            assertNull(withTimeout(10_000L) { pending.await() })
            assertTrue(viewModel.historyResumeError.value.orEmpty().contains("変更"))
        } finally { release.countDown() }
    }


    @Test
    fun ownedLocalRecordingResumesFromRoomWithoutNetworkAndRetainsPosition() = runBlocking {
        cacheReady.value = true
        val entity = RecordedProgramEntity(
            id = 55, title = "owned local recording",
            startTime = "2030-01-01T00:00:00+09:00", endTime = "2030-01-01T01:00:00+09:00",
            videoDuration = 3600.0, hasKeyFrames = true, isRecording = false,
            playbackPosition = 0.0, channelId = null, channelType = null,
            channelName = null, genres = null
        )
        Mockito.doReturn(entity).`when`(programDaoUnderTest).getById(55)
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        val result = viewModel.getResumableHistoryProgram(55, "KONOMITV")
        assertNotNull(result)
        assertEquals(55, result?.id)
        assertEquals(37.0, result?.playbackPosition ?: -1.0, 0.001)
        Mockito.verify(provider, Mockito.never()).getRecordedProgram(55)
    }

    @Test
    fun lostOwnershipDuringLocalRoomReadRejectsStaleRecording() = runBlocking {
        cacheReady.value = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            RecordedProgramEntity(
                id = 55, title = "obsolete local",
                startTime = "2030-01-01T00:00:00+09:00",
                endTime = "2030-01-01T01:00:00+09:00",
                videoDuration = 3600.0, hasKeyFrames = true, isRecording = false,
                playbackPosition = 0.0, channelId = null, channelType = null,
                channelName = null, genres = null
            )
        }.`when`(programDaoUnderTest).getById(55)
        try {
            val pending = async(Dispatchers.IO) { viewModel.getResumableHistoryProgram(55, "KONOMITV") }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            cacheReady.value = false
            ownerGeneration.value++
            release.countDown()
            assertNull(withTimeout(15_000L) { pending.await() })
            assertTrue(viewModel.historyResumeError.value.orEmpty().contains("変更"))
            Mockito.verifyNoInteractions(provider)
        } finally { release.countDown() }
    }

    @Test
    fun remoteResumeProviderFailureReportsErrorButCancellationPropagates() = runBlocking {
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        Mockito.doAnswer { throw IllegalStateException("injected remote failure") }
            .`when`(provider).getRecordedProgram(55)
        assertNull(viewModel.getResumableHistoryProgram(55, "KONOMITV"))
        assertTrue(viewModel.historyResumeError.value.orEmpty().contains("録画詳細"))
        Mockito.doAnswer { throw kotlinx.coroutines.CancellationException("request cancelled") }
            .`when`(provider).getRecordedProgram(55)
        try {
            viewModel.getResumableHistoryProgram(55, "KONOMITV")
            fail("caller cancellation must not be swallowed")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("request cancelled", expected.message)
        }
    }

}
