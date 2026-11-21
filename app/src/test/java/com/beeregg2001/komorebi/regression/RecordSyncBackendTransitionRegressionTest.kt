package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers the actual switchBackend transaction/ownership code in PR #3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordSyncBackendTransitionRegressionTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var engine: RecordSyncEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf("KONOMITV"))
        engine = RecordSyncEngine(
            Mockito.mock(RecordProvider::class.java),
            db, settings, db.aiSeriesDictionaryDao(), context
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun seedIndependentData() {
        db.watchHistoryDao().insertOrUpdate(WatchHistoryEntity(
            id = 19, title = "history", description = "", detailJson = null,
            startTime = "", endTime = "", duration = 0.0, videoId = 19
        ))
        db.epgCacheDao().insertOrUpdate(EpgCacheEntity("KONOMITV_GR", "epg", 1L))
    }

    @Test
    fun switchingBackendInvalidatesRecordedCacheButPreservesHistoryAndEpg() = runBlocking {
        seedIndependentData()
        val beforeGeneration = engine.recordCacheGeneration.value
        engine.switchBackend { /* test success: repository writes backend itself */ }
        assertEquals(beforeGeneration + 1L, engine.recordCacheGeneration.value)
        assertFalse(engine.recordCacheReady.value)
        assertTrue(context.getSharedPreferences(
            "record_cache_backend_owner", Context.MODE_PRIVATE).getBoolean("pending", false))
        assertEquals("history", db.watchHistoryDao().getById(19)?.title)
        assertEquals("epg", db.epgCacheDao().getCache("KONOMITV_GR")?.dataJson)
        assertEquals(false, db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted)
    }

    @Test
    fun settingWriteFailureRollsBackDatabaseAndClearsTransitionMarker() = runBlocking {
        seedIndependentData()
        db.syncMetaDao().upsert(
            SyncMetaEntity(id = 1, lastSyncedPage = 18,
                lastSyncedAt = 444L, isInitialBuildCompleted = true)
        )
        var didThrow = false
        try {
            engine.switchBackend { throw IllegalStateException("injected setting failure") }
        } catch (e: IllegalStateException) {
            didThrow = true
        }
        assertTrue("injected failure must be observed", didThrow)
        assertEquals(18, db.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertEquals(true, db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted)
        assertEquals("history", db.watchHistoryDao().getById(19)?.title)
        assertEquals("epg", db.epgCacheDao().getCache("KONOMITV_GR")?.dataJson)
        assertFalse(context.getSharedPreferences(
            "record_cache_backend_owner", Context.MODE_PRIVATE).getBoolean("pending", true))
    }
}
