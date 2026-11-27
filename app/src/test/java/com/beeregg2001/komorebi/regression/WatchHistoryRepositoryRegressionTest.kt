package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        selectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        repository = WatchHistoryRepository(
            Mockito.mock(KonomiApi::class.java),
            db.watchHistoryDao(), db.backendWatchHistoryDao(), db.lastChannelDao(),
            Mockito.mock(KonomiRepository::class.java), settings, db
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
        val observations = async(Dispatchers.Default) {
            withTimeout(10_000L) { repository.getOwnedLocalWatchHistory().take(2).toList() }
        }
        // Wait until the original owner emission has been observed before switching.
        withTimeout(10_000L) {
            while (true) {
                // Observe subscription readiness through a separate owner read.
                if (selectedBackend.value == "KONOMITV") break
                kotlinx.coroutines.yield()
            }
        }
        // Give the collecting coroutine an opportunity to register with Room.
        kotlinx.coroutines.delay(100)
        selectedBackend.value = "EDCB"
        val emissions = observations.await()
        assertEquals(2, emissions.size)
        assertEquals("KONOMITV" to listOf("Konomi title"),
            emissions[0].first to emissions[0].second.map { it.title })
        assertEquals("EDCB" to listOf("EDCB title"),
            emissions[1].first to emissions[1].second.map { it.title })
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

}
