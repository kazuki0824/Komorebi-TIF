package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var seriesProvider: EpgStationRecordRepository
    private lateinit var selectedSettings: SettingsRepository
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
        selectedSettings = settings
        ownerBackend = MutableStateFlow("KONOMITV")
        Mockito.`when`(settings.backendType).thenReturn(ownerBackend)
        Mockito.`when`(settings.defaultRecordListView).thenReturn(flowOf("LIST"))
        Mockito.`when`(settings.epgStationIp).thenReturn(flowOf("127.0.0.1"))
        Mockito.`when`(settings.epgStationPort).thenReturn(flowOf("8888"))
        val dao = Mockito.mock(RecordedProgramDao::class.java)
        programDaoUnderTest = dao
        recordedRows = MutableStateFlow(emptyList())
        Mockito.`when`(dao.getRecentRecordingsFlow()).thenReturn(recordedRows)
        Mockito.doReturn(emptyList<SeriesProjection>()).`when`(dao).getGroupedSeries()
        Mockito.doReturn(emptyList<ChannelProjection>()).`when`(dao).getDistinctChannels()
        historyRepo = Mockito.mock(WatchHistoryRepository::class.java)
        seriesProvider = Mockito.mock(EpgStationRecordRepository::class.java)
        viewModel = RecordViewModel(
            Mockito.mock(LiveProvider::class.java), provider,
            seriesProvider,
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
    fun ownedLocalRecordingResumesFromRoomWithoutNetworkAndRetainsPosition(): Unit = runBlocking {
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
    fun losingOnlyReadyDuringLocalReadRejectsTheRecordingEvenIfGenerationUnchanged() = runBlocking {
        cacheReady.value = true
        val generationBefore = ownerGeneration.value
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        Mockito.doReturn(historyEntry()).`when`(historyRepo)
            .getHistoryEntityById(55, "KONOMITV")
        Mockito.doAnswer {
            entered.countDown()
            check(released.await(15, TimeUnit.SECONDS))
            RecordedProgramEntity(
                id = 55, title = "revoked local recording",
                startTime = "2030-01-01T00:00:00+09:00",
                endTime = "2030-01-01T01:00:00+09:00",
                videoDuration = 3600.0, hasKeyFrames = true, isRecording = false,
                playbackPosition = 0.0, channelId = null, channelType = null,
                channelName = null, genres = null
            )
        }.`when`(programDaoUnderTest).getById(55)
        try {
            val pending = async(Dispatchers.IO) {
                viewModel.getResumableHistoryProgram(55, "KONOMITV")
            }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            cacheReady.value = false
            assertEquals("generation stays identical to isolate ready guard",
                generationBefore, ownerGeneration.value)
            released.countDown()
            assertNull(withTimeout(15_000L) { pending.await() })
            assertTrue(viewModel.historyResumeError.value.orEmpty().contains("変更"))
            Mockito.verifyNoInteractions(provider)
        } finally { released.countDown() }
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


    @Test
    fun classifiedRecordingRowsAreRevokedImmediatelyWhenOwnershipIsLost() = runBlocking {
        val grouped = SeriesProjection(
            seriesName = "Owned group", programCount = 3, representativeVideoId = 111,
            isEpisodic = true, genres = null, directThumbnailUrl = null,
            apiThumbnailUrl = null, lastAiredAt = "2030-01-01T01:00:00+09:00"
        )
        val channel = ChannelProjection("channel-1", "GR", "Owned Channel")
        Mockito.doReturn(listOf(grouped)).`when`(programDaoUnderTest).getGroupedSeries()
        Mockito.doReturn(listOf(channel)).`when`(programDaoUnderTest).getDistinctChannels()
        cacheReady.value = true
        syncState.value = SyncProgress(isSyncing = false)
        withTimeout(10_000L) { viewModel.groupedSeries.first { it.isNotEmpty() } }
        assertTrue(viewModel.groupedChannels.value.isNotEmpty())
        cacheReady.value = false
        withTimeout(10_000L) {
            viewModel.groupedSeries.first { it.isEmpty() }
        }
        assertTrue(viewModel.groupedChannels.value.isEmpty())
    }

    @Test
    fun delayedEpgstationSeriesReplyCannotRepopulateRevokedRecordingCategories() = runBlocking {
        ownerBackend.value = "EPGSTATION"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val grouped = SeriesProjection(
            seriesName = "Previous EPGStation series", programCount = 2, representativeVideoId = 111,
            isEpisodic = true, genres = null, directThumbnailUrl = null,
            apiThumbnailUrl = null, lastAiredAt = null
        )
        Mockito.doReturn(listOf(grouped)).`when`(programDaoUnderTest).getGroupedSeries()
        Mockito.doReturn(listOf(ChannelProjection("old", "GR", "Old channel")))
            .`when`(programDaoUnderTest).getDistinctChannels()
        Mockito.doAnswer {
            started.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            null
        }.`when`(seriesProvider).getSeriesList()
        try {
            cacheReady.value = true
            syncState.value = SyncProgress(isSyncing = false)
            assertTrue(started.await(15, TimeUnit.SECONDS))
            cacheReady.value = false
            ownerGeneration.value += 1
            ownerBackend.value = "EDCB"
            release.countDown()
            withTimeout(10_000L) { viewModel.groupedSeries.first { it.isEmpty() } }
            assertTrue(viewModel.groupedChannels.value.isEmpty())
        } finally { release.countDown() }
    }


    @Test
    fun onePagingSubscriptionPublishesRowsOnlyWhileCacheOwnershipIsReady(): Unit = runBlocking {
        val ownedRow = RecordedProgramEntity(
            id = 88, title = "owned paging row",
            startTime = "2030-01-01T00:00:00+09:00", endTime = "2030-01-01T01:00:00+09:00",
            videoDuration = 3600.0, hasKeyFrames = true, isRecording = false,
            playbackPosition = 0.0, channelId = null, channelType = null,
            channelName = null, genres = null
        )
        val pagingQueries = java.util.concurrent.atomic.AtomicInteger()
        Mockito.doAnswer {
            pagingQueries.incrementAndGet()
            object : PagingSource<Int, RecordedProgramEntity>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RecordedProgramEntity> =
                    LoadResult.Page(data = listOf(ownedRow), prevKey = null, nextKey = null)
                override fun getRefreshKey(state: PagingState<Int, RecordedProgramEntity>): Int? = null
            }
        }.`when`(programDaoUnderTest).getAll_DateDesc()
        val updates = object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {}
            override fun onRemoved(position: Int, count: Int) {}
            override fun onMoved(fromPosition: Int, toPosition: Int) {}
            override fun onChanged(position: Int, count: Int, payload: Any?) {}
        }
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<RecordedProgram>() {
                override fun areItemsTheSame(oldItem: RecordedProgram, newItem: RecordedProgram) =
                    oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: RecordedProgram, newItem: RecordedProgram) =
                    oldItem == newItem
            },
            updateCallback = updates, mainDispatcher = Dispatchers.Main,
            workerDispatcher = Dispatchers.Default
        )
        val subscription = launch(Dispatchers.Main.immediate) {
            viewModel.pagedRecordings.collectLatest { differ.submitData(it) }
        }
        try {
            assertEquals(0, differ.itemCount)
            dispatcher.scheduler.advanceTimeBy(70L)
            dispatcher.scheduler.runCurrent()
            assertEquals("unowned cache may not create DAO paging query after the delay", 0, pagingQueries.get())
            cacheReady.value = true
            dispatcher.scheduler.advanceTimeBy(60L)
            dispatcher.scheduler.runCurrent()
            withTimeout(15_000L) {
                while (differ.snapshot().items.none { it.id == 88 })
                    kotlinx.coroutines.delay(20)
            }
            assertEquals(listOf(88), differ.snapshot().items.map { it.id })
            val queriesWhileReady = pagingQueries.get()
            assertTrue(queriesWhileReady > 0)
            cacheReady.value = false
            withTimeout(15_000L) {
                while (differ.snapshot().items.isNotEmpty())
                    kotlinx.coroutines.delay(20)
            }
            dispatcher.scheduler.advanceTimeBy(70L)
            dispatcher.scheduler.runCurrent()
            assertTrue(differ.snapshot().items.isEmpty())
            assertEquals("revoked owner may not reload old DAO rows", queriesWhileReady, pagingQueries.get())
        } finally {
            subscription.cancel()
        }
    }



    /** Make the production revocation collector's transition observable.
     * Waiting for an initially empty flow is not evidence of revocation. */
    private fun markPreviouslyPublishedGroups() {
        val field = RecordViewModel::class.java.getDeclaredField("_groupedSeries")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val output = field.get(viewModel) as MutableStateFlow<
            Map<String, List<com.beeregg2001.komorebi.viewmodel.SeriesInfo>>
        >
        output.value = mapOf("previously-published" to emptyList())
    }

    @Test
    fun nonemptyOldEpgstationApiResultCannotPublishAfterBackendChange() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newDaoEntered = CountDownLatch(1)
        val releaseNewDao = CountDownLatch(1)
        val oldApiReturned = AtomicBoolean(false)
        val oldGuardCheckedReadyFalse = CountDownLatch(1)
        Mockito.doAnswer {
            if (oldApiReturned.get() && !cacheReady.value)
                oldGuardCheckedReadyFalse.countDown()
            cacheReady
        }.`when`(sync).recordCacheReady
        Mockito.doAnswer {
            if (ownerBackend.value == "EDCB") {
                newDaoEntered.countDown()
                check(releaseNewDao.await(15, TimeUnit.SECONDS))
            }
            listOf(SeriesProjection("New EDCB group", 1, 345, true, null, null, null, null))
        }.`when`(programDaoUnderTest).getGroupedSeries()
        Mockito.doReturn(listOf(ChannelProjection("new-channel", "GR", "New EDCB channel")))
            .`when`(programDaoUnderTest).getDistinctChannels()
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            oldApiReturned.set(true)
            listOf(com.beeregg2001.komorebi.data.model.EsSeriesListItem(
                id = 201, title = "Old EPGStation API", normalizedTitle = "Old EPGStation API",
                recordedCount = 1
            ))
        }.`when`(seriesProvider).getSeriesList()
        val observed = java.util.concurrent.CopyOnWriteArrayList<Map<String, List<com.beeregg2001.komorebi.viewmodel.SeriesInfo>>>()
        val subscription = launch(Dispatchers.Default) { viewModel.groupedSeries.collect { observed.add(it) } }
        try {
            ownerBackend.value = "EPGSTATION"
            cacheReady.value = true
            syncState.value = SyncProgress(isSyncing = false)
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            markPreviouslyPublishedGroups()
            cacheReady.value = false
            ownerGeneration.value++
            ownerBackend.value = "EDCB"
            // Keep isSyncing=false throughout. If the classification combine
            // misses ready=false, its distinctUntilChanged will suppress the
            // next ready=true and newDaoEntered will time out.
            // Wait for real revocation, not the initial empty StateFlow.
            withTimeout(10_000L) { viewModel.groupedSeries.first { it.isEmpty() } }
            release.countDown()
            assertTrue("obsolete API result must reach the production ready=false guard",
                oldGuardCheckedReadyFalse.await(15, TimeUnit.SECONDS))
            // Give the resumed classification collector a turn to process its
            // queued false while ready is still revoked (no clock sleeps).
            withContext(Dispatchers.IO) { yield() }
            cacheReady.value = true
            assertTrue("new DAO must be entered only after classification observed ready=false",
                newDaoEntered.await(15, TimeUnit.SECONDS))
            assertTrue("old API must not escape while replacement DAO is stopped",
                viewModel.groupedSeries.value.values.flatten().none {
                    it.displayTitle == "Old EPGStation API"
                })
            releaseNewDao.countDown()
            withTimeout(15_000L) {
                viewModel.groupedSeries.first { groups ->
                    groups.values.flatten().any { it.displayTitle == "New EDCB group" }
                }
            }
            assertTrue("obsolete EPGStation API series must never be published",
                observed.none { groups -> groups.values.flatten().any {
                    it.displayTitle == "Old EPGStation API"
                } })
        } finally {
            release.countDown()
            releaseNewDao.countDown()
            subscription.cancel()
        }
    }

    @Test
    fun delayedOldRoomSeriesQueryCannotPublishAfterOwnerChange() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newRoomEntered = CountDownLatch(1)
        val releaseNewRoom = CountDownLatch(1)
        val oldRoomReturned = AtomicBoolean(false)
        val oldGuardCheckedReadyFalse = CountDownLatch(1)
        Mockito.doAnswer {
            if (oldRoomReturned.get() && !cacheReady.value)
                oldGuardCheckedReadyFalse.countDown()
            cacheReady
        }.`when`(sync).recordCacheReady
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                oldRoomReturned.set(true)
                listOf(SeriesProjection("Obsolete Room group", 1, 456, true, null, null, null, null))
            } else {
                newRoomEntered.countDown()
                check(releaseNewRoom.await(15, TimeUnit.SECONDS))
                listOf(SeriesProjection("Current Room group", 1, 457, true, null, null, null, null))
            }
        }.`when`(programDaoUnderTest).getGroupedSeries()
        Mockito.doReturn(listOf(ChannelProjection("id-2", "GR", "Current room channel")))
            .`when`(programDaoUnderTest).getDistinctChannels()
        val observed = java.util.concurrent.CopyOnWriteArrayList<Map<String, List<com.beeregg2001.komorebi.viewmodel.SeriesInfo>>>()
        val subscription = launch(Dispatchers.Default) { viewModel.groupedSeries.collect { observed.add(it) } }
        try {
            ownerBackend.value = "KONOMITV"
            cacheReady.value = true
            syncState.value = SyncProgress(isSyncing = false)
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            markPreviouslyPublishedGroups()
            cacheReady.value = false
            ownerGeneration.value++
            ownerBackend.value = "EDCB"
            // isSyncing remains false: ready=false must be the sole source of
            // shouldLoad=false, otherwise the subsequent true is suppressed.
            withTimeout(10_000L) { viewModel.groupedSeries.first { it.isEmpty() } }
            release.countDown()
            assertTrue("obsolete Room result must reach the production ready=false guard",
                oldGuardCheckedReadyFalse.await(15, TimeUnit.SECONDS))
            // Keep the only false source active through the old request's
            // completion. The following DAO reload proves false was consumed.
            withContext(Dispatchers.IO) { yield() }
            cacheReady.value = true
            assertTrue("new owner DAO proves classification observed ready=false before true",
                newRoomEntered.await(15, TimeUnit.SECONDS))
            assertTrue("obsolete Room result must not appear while new Room query is blocked",
                viewModel.groupedSeries.value.values.flatten().none {
                    it.displayTitle == "Obsolete Room group"
                })
            releaseNewRoom.countDown()
            withTimeout(15_000L) {
                viewModel.groupedSeries.first { groups ->
                    groups.values.flatten().any { it.displayTitle == "Current Room group" }
                }
            }
            assertTrue("old Room series must not escape the owner-generation gate",
                observed.none { groups -> groups.values.flatten().any {
                    it.displayTitle == "Obsolete Room group"
                } })
        } finally {
            release.countDown()
            releaseNewRoom.countDown()
            subscription.cancel()
        }
    }

    @Test
    fun delayedOldEpgStationCategoriesNeverReappearAfterNewOwnerPublishesRoomCategories() = runBlocking {
        val old = SeriesProjection(
            "Obsolete EPGStation group", 2, 301, true, null, null, null, null
        )
        val replacement = SeriesProjection(
            "Current EDCB group", 2, 302, true, null, null, null, null
        )
        val apiEntered = CountDownLatch(1)
        val releaseApi = CountDownLatch(1)
        Mockito.doAnswer {
            listOf(if (ownerBackend.value == "EPGSTATION") old else replacement)
        }.`when`(programDaoUnderTest).getGroupedSeries()
        Mockito.doAnswer {
            listOf(ChannelProjection(
                if (ownerBackend.value == "EPGSTATION") "old" else "current",
                "GR", if (ownerBackend.value == "EPGSTATION") "Obsolete" else "Current"
            ))
        }.`when`(programDaoUnderTest).getDistinctChannels()
        Mockito.doAnswer {
            apiEntered.countDown()
            check(releaseApi.await(15, TimeUnit.SECONDS))
            null
        }.`when`(seriesProvider).getSeriesList()
        val emitted = java.util.concurrent.CopyOnWriteArrayList<Map<String, List<com.beeregg2001.komorebi.viewmodel.SeriesInfo>>>()
        // The production StateFlow is observed without replacing its publication logic.
        val subscription = launch(Dispatchers.Default) {
            viewModel.groupedSeries.collect { emitted.add(it) }
        }
        try {
            ownerBackend.value = "EPGSTATION"
            syncState.value = SyncProgress(isSyncing = false)
            cacheReady.value = true
            assertTrue(apiEntered.await(15, TimeUnit.SECONDS))
            cacheReady.value = false
            ownerGeneration.value++
            ownerBackend.value = "EDCB"
            syncState.value = SyncProgress(isSyncing = true)
            releaseApi.countDown()
            // Allow the suspended EPGStation classification to finish while the
            // owner is revoked. Then publish a distinct new-owner ready event.
            withTimeout(10_000L) {
                while (viewModel.isSeriesLoading.value) kotlinx.coroutines.delay(10L)
            }
            kotlinx.coroutines.delay(100L)
            syncState.value = SyncProgress(isSyncing = false)
            cacheReady.value = true
            withTimeout(15_000L) {
                viewModel.groupedSeries.first { groups ->
                    groups.values.flatten().any { it.displayTitle == "Current EDCB group" }
                }
            }
            assertFalse("obsolete grouped series must never be republished",
                emitted.any { groups -> groups.values.flatten().any { it.displayTitle == "Obsolete EPGStation group" } })
            assertTrue(viewModel.groupedChannels.value.values.flatten()
                .any { it.first == "Current" && it.second == "current" })
        } finally {
            releaseApi.countDown()
            subscription.cancel()
        }
    }

}
