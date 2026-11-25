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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import com.beeregg2001.komorebi.data.sync.SyncProgress
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
    @Suppress("UNCHECKED_CAST")
    private fun progressState(): MutableStateFlow<SyncProgress> {
        val field = RecordSyncEngine::class.java.getDeclaredField("_syncProgress")
        field.isAccessible = true
        return field.get(engine) as MutableStateFlow<SyncProgress>
    }

    private fun epochCounter(): AtomicLong {
        val field = RecordSyncEngine::class.java.getDeclaredField("syncProgressEpoch")
        field.isAccessible = true
        return field.get(engine) as AtomicLong
    }

    /** Invoke production's private epoch gate, not a duplicate test algorithm. */
    private fun updateDictionary(epoch: Long, transform: (SyncProgress) -> SyncProgress) {
        val method = RecordSyncEngine::class.java.getDeclaredMethod(
            "updateDictionaryProgress", java.lang.Long.TYPE, kotlin.jvm.functions.Function1::class.java
        )
        method.isAccessible = true
        method.invoke(engine, epoch, transform)
    }

    @Test
    fun staleDictionaryGenerationCannotOverwriteProgressOfNewSync() {
        val state = progressState()
        val epoch = epochCounter().incrementAndGet()
        state.value = SyncProgress(isSyncing = true, message = "シリーズ辞書を準備中...", current = 4)
        updateDictionary(epoch - 1L) { it.copy(current = 999) }
        assertEquals(4, state.value.current)
    }

    @Test
    fun currentDictionaryGenerationMayUpdateItsOwnProgress() {
        val state = progressState()
        val epoch = epochCounter().incrementAndGet()
        state.value = SyncProgress(isSyncing = true, message = "シリーズ辞書を準備中...", current = 4)
        updateDictionary(epoch) { it.copy(current = 7) }
        assertEquals(7, state.value.current)
    }

    @Test
    fun dictionaryProgressDoesNotOverwriteErrorOrOtherSyncMessage() {
        val state = progressState()
        val epoch = epochCounter().incrementAndGet()
        state.value = SyncProgress(isSyncing = true, message = "シリーズ辞書を準備中...", current = 4, error = "sync failed")
        updateDictionary(epoch) { it.copy(current = 999) }
        assertEquals(4, state.value.current)
        state.value = SyncProgress(isSyncing = true, message = "録画リストを更新中...", current = 5)
        updateDictionary(epoch) { it.copy(current = 999) }
        assertEquals(5, state.value.current)
    }

    @Test
    fun partiallySavedSettingMustKeepCachePendingAndNeverPublishOldOwner() = runBlocking {
        val newSelectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(newSelectedBackend)
        val secondaryEngine = RecordSyncEngine(
            Mockito.mock(RecordProvider::class.java), db,
            settings, db.aiSeriesDictionaryDao(), context
        )
        val result = runCatching {
            secondaryEngine.switchBackend {
                // Simulates DataStore committing before the Room transaction fails.
                newSelectedBackend.value = "EDCB"
                throw IllegalStateException("injected failure after setting commit")
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("EDCB", newSelectedBackend.value)
        assertTrue(
            context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
                .getBoolean("pending", false)
        )
        assertFalse(secondaryEngine.recordCacheReady.value)
    }

    @Test
    fun queuedSyncMustNotReadBackendWhileSwitchInProgress() = runBlocking {
        val repoMock = Mockito.mock(RecordProvider::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf("KONOMITV"))
        val secondaryEngine = RecordSyncEngine(
            repoMock, db, settings, db.aiSeriesDictionaryDao(), context
        )
        val field = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
        field.isAccessible = true
        field.setBoolean(secondaryEngine, true)
        withTimeout(10_000L) { secondaryEngine.syncAllRecords() }
        Mockito.verifyNoInteractions(repoMock)
    }

    /** Invokes the actual private epoch-gated completion path in production. */
    private fun finishDictionary(epoch: Long) {
        val fn = RecordSyncEngine::class.java.getDeclaredMethod(
            "finishDictionaryProgress", java.lang.Long.TYPE
        )
        fn.isAccessible = true
        fn.invoke(engine, epoch)
    }

    @Test
    fun obsoleteDictionaryCompletionCannotMarkNewSyncAsFinished() {
        val state = progressState()
        val latest = epochCounter().incrementAndGet()
        state.value = SyncProgress(
            isSyncing = true, message = "シリーズ辞書を自動生成中...",
            current = 3, total = 5
        )
        finishDictionary(latest - 1L)
        assertTrue(state.value.isSyncing)
        assertEquals(3, state.value.current)
        assertEquals("シリーズ辞書を自動生成中...", state.value.message)
    }

    @Test
    fun currentDictionaryCompletionMayClearOnlyItsOwnProgress() {
        val state = progressState()
        val latest = epochCounter().incrementAndGet()
        state.value = SyncProgress(
            isSyncing = true, message = "シリーズ辞書を準備中...",
            current = 5, total = 5
        )
        finishDictionary(latest)
        assertFalse(state.value.isSyncing)
        assertNull(state.value.error)
    }

    @Test
    fun backendSwitchAdvancesDictionaryEpochSoPreviousWorkerCannotResetNewMessage() = runBlocking {
        val state = progressState()
        val oldEpoch = epochCounter().get()
        engine.switchBackend { }
        assertTrue(epochCounter().get() > oldEpoch)
        state.value = SyncProgress(isSyncing = true, message = "シリーズ辞書を準備中...", current = 9)
        finishDictionary(oldEpoch)
        assertTrue(state.value.isSyncing)
        assertEquals(9, state.value.current)
    }

    @Test
    fun clearDatabaseAndSmartSyncAreBothRejectedWhileBackendTransitionIsActive() = runBlocking {
        val recordProvider = Mockito.mock(RecordProvider::class.java)
        val flag = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
        flag.isAccessible = true
        flag.setBoolean(engine, true)
        db.syncMetaDao().upsert(
            SyncMetaEntity(id = 1, lastSyncedPage = 18,
                lastSyncedAt = 44L, isInitialBuildCompleted = true)
        )
        try {
            engine.clearDatabase()
            engine.smartSync()
            val meta = db.syncMetaDao().getSyncMeta()
            assertEquals(18, meta?.lastSyncedPage)
            assertEquals(true, meta?.isInitialBuildCompleted)
        } finally {
            flag.setBoolean(engine, false)
        }
    }

}
