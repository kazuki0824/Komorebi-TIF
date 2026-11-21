package com.beeregg2001.komorebi.regression

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.beeregg2001.komorebi.data.local.dao.BackendWatchHistoryDao
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the production DAOs against SQLite via Robolectric.
 * The test-only database includes only the tables relevant to these contracts.
 * This does NOT substitute for testing the production AppDatabase v15->v16 migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendDataIsolationRegressionTest {
    @Database(
        entities = [
            BackendWatchHistoryEntity::class,
            WatchHistoryEntity::class,
            EpgCacheEntity::class
        ],
        version = 1,
        exportSchema = false
    )
    abstract class RegressionDatabase : RoomDatabase() {
        abstract fun histories(): BackendWatchHistoryDao
        abstract fun legacy(): WatchHistoryDao
        abstract fun epg(): EpgCacheDao
    }

    private lateinit var db: RegressionDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegressionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(backend: String, id: Int, timestamp: Long, data: String) =
        BackendWatchHistoryEntity(backend, id, data, timestamp)

    @Test
    fun identicalRecordIdsFromDifferentBackendsDoNotOverwriteEachOther() = runBlocking {
        val dao = db.histories()
        dao.upsert(row("KONOMITV", 42, 10L, "kon"))
        dao.upsert(row("EDCB", 42, 20L, "edcb"))
        dao.upsert(row("EPGSTATION", 42, 30L, "epg"))
        assertEquals("kon", dao.findById("KONOMITV", 42)?.payloadJson)
        assertEquals("edcb", dao.findById("EDCB", 42)?.payloadJson)
        assertEquals("epg", dao.findById("EPGSTATION", 42)?.payloadJson)
        dao.upsert(row("EDCB", 42, 40L, "edcb-updated"))
        assertEquals("kon", dao.findById("KONOMITV", 42)?.payloadJson)
        assertEquals("edcb-updated", dao.findById("EDCB", 42)?.payloadJson)
    }

    @Test
    fun recentHistoryIsFilteredAndOrderedPerBackend() = runBlocking {
        val dao = db.histories()
        dao.upsertAll(listOf(
            row("KONOMITV", 1, 10L, "old"),
            row("EDCB", 1, 999L, "foreign"),
            row("KONOMITV", 2, 30L, "new")
        ))
        val own = dao.observeRecent("KONOMITV").first()
        assertEquals(listOf(2, 1), own.map { it.recordId })
        assertTrue(own.all { it.backend == "KONOMITV" })
        assertEquals(listOf(1), dao.observeRecent("EDCB").first().map { it.recordId })
    }

    @Test
    fun unknownLegacyOwnerIsNotAutomaticallyInsertedIntoNewHistoryTable() = runBlocking {
        db.legacy().insertOrUpdate(
            WatchHistoryEntity(
                id = 42, title = "legacy", description = "",
                detailJson = null, startTime = "", endTime = "",
                duration = 0.0, videoId = 42
            )
        )
        assertNotNull(db.legacy().getById(42))
        assertNull(db.histories().findById("KONOMITV", 42))
        assertNull(db.histories().findById("EDCB", 42))
    }

    @Test
    fun backendQualifiedEpgCacheKeysRemainIndependent() = runBlocking {
        val dao = db.epg()
        dao.insertOrUpdate(EpgCacheEntity("KONOMITV_GR", "kon-payload", 10L))
        dao.insertOrUpdate(EpgCacheEntity("EDCB_GR", "edcb-payload", 20L))
        assertEquals("kon-payload", dao.getCache("KONOMITV_GR")?.dataJson)
        assertEquals("edcb-payload", dao.getCache("EDCB_GR")?.dataJson)
        assertNull(dao.getCache("EPGSTATION_GR"))
        dao.insertOrUpdate(EpgCacheEntity("KONOMITV_GR", "updated", 30L))
        assertEquals("edcb-payload", dao.getCache("EDCB_GR")?.dataJson)
    }
}
