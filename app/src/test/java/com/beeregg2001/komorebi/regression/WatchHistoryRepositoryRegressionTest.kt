package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LastChannelRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises actual WatchHistoryRepository, not merely the Room DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryRepositoryRegressionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WatchHistoryRepository
    private lateinit var selectedBackend: MutableStateFlow<String>
    private lateinit var api: KonomiApi
    private lateinit var konomi: KonomiRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        selectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        api = Mockito.mock(KonomiApi::class.java)
        konomi = Mockito.mock(KonomiRepository::class.java)
        repository = WatchHistoryRepository(
            api,
            db.watchHistoryDao(), db.backendWatchHistoryDao(), db.lastChannelDao(),
            konomi, settings, db
        )
    }

    @After
    fun tearDown() = db.close()

    private fun history(id: Int, title: String): WatchHistoryEntity =
        WatchHistoryEntity(
            id = id, title = title, description = "", detailJson = null,
            startTime = "", endTime = "", duration = 10.0,
            videoId = id, watchedAt = id.toLong()
        )

    @Test
    fun selectingAnotherBackendAtomicallyChangesOwnerAndRows() = runBlocking {
        repository.saveToLocalHistory(history(42, "Konomi title"), "KONOMITV")
        repository.saveToLocalHistory(history(42, "EDCB title"), "EDCB")
        val first = repository.getOwnedLocalWatchHistory().first()
        assertEquals("KONOMITV", first.first)
        assertEquals("Konomi title", first.second.single().title)

        selectedBackend.value = "EDCB"
        val second = repository.getOwnedLocalWatchHistory().first()
        assertEquals("EDCB", second.first)
        assertEquals("EDCB title", second.second.single().title)
    }

    @Test
    fun batchHistoryWritesKeepExplicitOwnerIndependentOfSelectedBackend() = runBlocking {
        selectedBackend.value = "EDCB"
        repository.saveAllToLocalHistory(listOf(history(1, "Konomi 1"), history(2, "Konomi 2")), "KONOMITV")
        assertTrue(repository.getLocalWatchHistory().first().isEmpty())
        assertTrue(repository.isHistoryOwnedBy(1, "KONOMITV"))
        assertFalse(repository.isHistoryOwnedBy(1, "EDCB"))
        assertEquals(listOf("Konomi 1", "Konomi 2"),
            repository.getHistoryEntitiesByIds(listOf(1, 2), "KONOMITV")
                .sortedBy { it.id }.map { it.title })
    }

    @Test
    fun historyLookupsRejectSameIdFromAnotherBackend() = runBlocking {
        repository.saveToLocalHistory(history(55, "Konomi"), "KONOMITV")
        repository.saveToLocalHistory(history(55, "EDCB"), "EDCB")
        assertEquals("Konomi", repository.getHistoryEntityById(55, "KONOMITV")?.title)
        assertEquals("EDCB", repository.getHistoryEntityById(55, "EDCB")?.title)
        assertNull(repository.getHistoryEntityById(55, "EPGSTATION"))
    }

    @Test
    fun clearHistoryErasesBothSchemasButLeavesEpgCacheAlone() = runBlocking {
        val legacy = history(66, "Old")
        db.watchHistoryDao().insertOrUpdate(legacy)
        repository.saveToLocalHistory(history(66, "New"), "KONOMITV")
        db.epgCacheDao().insertOrUpdate(
            com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity(
                "KONOMITV_GR", "payload", 2L
            )
        )
        repository.clearWatchHistory()
        assertNull(db.watchHistoryDao().getById(66))
        assertNull(repository.getHistoryEntityById(66, "KONOMITV"))
        assertEquals("payload", db.epgCacheDao().getCache("KONOMITV_GR")?.dataJson)
    }

    @Test
    fun continuousHistorySubscriptionNeverCombinesNewOwnerWithOldRows() = runBlocking {
        repository.saveToLocalHistory(history(42, "Konomi title"), "KONOMITV")
        repository.saveToLocalHistory(history(42, "EDCB title"), "EDCB")
        val observed = Channel<Pair<String, List<WatchHistoryEntity>>>(Channel.UNLIMITED)
        val subscription = launch(Dispatchers.Default) {
            repository.getOwnedLocalWatchHistory().collect { observed.send(it) }
        }
        try {
            val first = withTimeout(10_000L) { observed.receive() }
            assertEquals("KONOMITV", first.first)
            assertEquals(listOf("Konomi title"), first.second.map { it.title })
            selectedBackend.value = "EDCB"
            val second = withTimeout(10_000L) { observed.receive() }
            assertEquals("EDCB", second.first)
            assertEquals(listOf("EDCB title"), second.second.map { it.title })
            // An old-owner row must never be published with the new owner.
            assertTrue(second.second.none { it.title == "Konomi title" })
        } finally {
            subscription.cancelAndJoin()
            observed.close()
        }
    }

    @Test
    fun failureDeletingLegacyHistoryRollsBackEarlierBackendHistoryDeletion() = runBlocking {
        db.watchHistoryDao().insertOrUpdate(history(66, "legacy"))
        repository.saveToLocalHistory(history(66, "Konomi"), "KONOMITV")
        repository.saveToLocalHistory(history(66, "EDCB"), "EDCB")
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER fail_legacy_history_delete BEFORE DELETE ON watch_history
               BEGIN SELECT RAISE(ABORT, 'injected legacy deletion failure'); END"""
        )
        try {
            try {
                repository.clearWatchHistory()
                fail("Failure injection must abort the transaction")
            } catch (e: android.database.sqlite.SQLiteException) {
                assertTrue(e.message.orEmpty().contains("injected"))
            }
            assertNotNull(db.watchHistoryDao().getById(66))
            assertEquals("Konomi", repository.getHistoryEntityById(66, "KONOMITV")?.title)
            assertEquals("EDCB", repository.getHistoryEntityById(66, "EDCB")?.title)
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS fail_legacy_history_delete")
        }
    }


    private fun recording(id: Int): RecordedProgram {
        val program = Mockito.mock(RecordedProgram::class.java)
        val video = Mockito.mock(RecordedVideo::class.java)
        Mockito.`when`(program.id).thenReturn(id)
        Mockito.`when`(program.title).thenReturn("record-$id")
        Mockito.`when`(program.description).thenReturn("")
        Mockito.`when`(program.startTime).thenReturn("2030-01-01T00:00:00+09:00")
        Mockito.`when`(program.endTime).thenReturn("2030-01-01T01:00:00+09:00")
        Mockito.`when`(program.duration).thenReturn(3600.0)
        Mockito.`when`(video.id).thenReturn(id)
        Mockito.`when`(program.recordedVideo).thenReturn(video)
        return program
    }

    @Test
    fun actualHistorySavePersistsOwnerAndPositionAndOnlyKonomiSyncsUpstream() = runBlocking {
        selectedBackend.value = "EDCB"
        val own = recording(99)
        repository.saveWatchHistory(own, 42.5, "EDCB")
        repository.saveWatchHistory(own, 19.0, "EPGSTATION")
        repository.saveWatchHistory(own, 7.25, "KONOMITV")
        assertEquals(42.5, repository.getHistoryEntityById(99, "EDCB")?.playbackPosition ?: -1.0, 0.001)
        assertEquals(19.0, repository.getHistoryEntityById(99, "EPGSTATION")?.playbackPosition ?: -1.0, 0.001)
        assertEquals(7.25, repository.getHistoryEntityById(99, "KONOMITV")?.playbackPosition ?: -1.0, 0.001)
        Mockito.verify(konomi, Mockito.times(1)).syncPlaybackPosition("99", 7.25)
        Mockito.verify(konomi, Mockito.times(1)).syncPlaybackPosition(
            Mockito.anyString() ?: "", Mockito.anyDouble()
        )
    }

    @Test
    fun failedKonomiRemoteSyncDoesNotEraseSuccessfullySavedLocalHistory() = runBlocking {
        val program = recording(101)
        Mockito.doAnswer { throw IllegalStateException("remote unavailable") }
            .`when`(konomi).syncPlaybackPosition("101", 13.5)
        repository.saveWatchHistory(program, 13.5, "KONOMITV")
        assertEquals(13.5, repository.getHistoryEntityById(101, "KONOMITV")?.playbackPosition ?: -1.0, 0.001)
    }

    @Test
    fun historyImportedFromKonomiApiKeepsKonomiOwnerWhenEdcbIsSelected() = runBlocking {
        selectedBackend.value = "EDCB"
        val result = KonomiHistoryProgram(
            program = KonomiProgram(
                id = "200", title = "API owned", description = "", detail = null,
                start_time = "2030-01-01T00:00:00Z", end_time = "2030-01-01T01:00:00Z",
                channel_id = "1"
            ),
            playback_position = 22.0, last_watched_at = "2030-01-01T00:30:00Z"
        )
        Mockito.doReturn(listOf(result)).`when`(api).getWatchHistory()
        repository.refreshHistoryFromApi()
        assertEquals("API owned", repository.getHistoryEntityById(200, "KONOMITV")?.title)
        assertNull(repository.getHistoryEntityById(200, "EDCB"))
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun homeViewModelPublishesMatchingOwnerAndRowsAcrossContinuousSubscription() = runBlocking {
        repository.saveToLocalHistory(history(42, "Konomi title"), "KONOMITV")
        repository.saveToLocalHistory(history(42, "EDCB title"), "EDCB")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        Mockito.`when`(settings.homePickupGenre).thenReturn(flowOf("アニメ"))
        Mockito.`when`(settings.homePickupTime).thenReturn(flowOf("夜"))
        Mockito.`when`(settings.excludePaidBroadcasts).thenReturn(flowOf("OFF"))
        Mockito.`when`(settings.favoriteBaseballTeams).thenReturn(flowOf("[]"))
        Mockito.`when`(settings.receiveBetaUpdates).thenReturn(flowOf(false))
        val updater = Mockito.mock(AppUpdater::class.java)
        Mockito.`when`(updater.updateState)
            .thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val home = HomeViewModel(
            Mockito.mock(LiveProvider::class.java),
            Mockito.mock(KonomiRepository::class.java),
            Mockito.mock(EpgRepository::class.java),
            settings,
            Mockito.mock(LastChannelRepository::class.java),
            repository,
            updater
        )
        val observed = Channel<Pair<String?, List<com.beeregg2001.komorebi.data.model.KonomiHistoryProgram>>>(Channel.UNLIMITED)
        val subscription = launch(Dispatchers.Default) {
            home.ownedWatchHistory.collect { if (it.first != null) observed.send(it) }
        }
        try {
            val first = withTimeout(10_000L) { observed.receive() }
            assertEquals("KONOMITV", first.first)
            assertEquals("Konomi title", first.second.single().program.title)
            selectedBackend.value = "EDCB"
            val second = withTimeout(10_000L) { observed.receive() }
            assertEquals("EDCB", second.first)
            assertEquals("EDCB title", second.second.single().program.title)
            assertTrue(second.second.none { it.program.title == "Konomi title" })
        } finally {
            subscription.cancelAndJoin()
            home.viewModelScope.cancel()
            observed.close()
            Dispatchers.resetMain()
        }
    }

}
