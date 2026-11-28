package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
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
    private lateinit var selectedBackend: MutableStateFlow<String>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        selectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
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
        db.recordedProgramDao().upsertAll(listOf(
            RecordedProgramEntity(
                id = 19, title = "Previous backend recording",
                startTime = "2030-01-01T00:00:00+09:00", endTime = "2030-01-01T01:00:00+09:00",
                videoDuration = 3600.0, hasKeyFrames = true, isRecording = false,
                playbackPosition = 0.0, channelId = null, channelType = null,
                channelName = null, genres = null
            )
        ))
    }

    @Test
    fun switchingBackendInvalidatesRecordedCacheButPreservesHistoryAndEpg() = runBlocking {
        seedIndependentData()
        val beforeGeneration = engine.recordCacheGeneration.value
        engine.switchBackend { selectedBackend.value = "EDCB" }
        assertEquals("EDCB", selectedBackend.value)
        assertEquals(beforeGeneration + 1L, engine.recordCacheGeneration.value)
        assertFalse(engine.recordCacheReady.value)
        assertTrue(context.getSharedPreferences(
            "record_cache_backend_owner", Context.MODE_PRIVATE).getBoolean("pending", false))
        assertEquals("history", db.watchHistoryDao().getById(19)?.title)
        assertEquals("epg", db.epgCacheDao().getCache("KONOMITV_GR")?.dataJson)
        assertTrue(db.recordedProgramDao().getAllIds().isEmpty())
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
        assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
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
    fun nextFullSyncAfterBackendSwitchAdvancesDictionaryEpochAndRejectsOldProgress() = runBlocking {
        val state = progressState()
        val oldEpoch = epochCounter().get()
        engine.switchBackend { }
        // Switching cancels and joins the old dictionary Job. The actual
        // progress generation advances at the start of the next sync, not
        // inside switchBackend itself.
        engine.syncAllRecords(forceFullSync = true)
        assertTrue(epochCounter().get() > oldEpoch)
        state.value = SyncProgress(isSyncing = true, message = "シリーズ辞書を準備中...", current = 9)
        finishDictionary(oldEpoch)
        assertTrue(state.value.isSyncing)
        assertEquals(9, state.value.current)
    }

    @Test
    fun clearDatabaseAndSmartSyncAreBothRejectedWhileBackendTransitionIsActive() = runBlocking {
        val recordProvider = Mockito.mock(RecordProvider::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        assertTrue(
            context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
                .edit().putString("backend", "KONOMITV").putBoolean("pending", false).commit()
        )
        seedIndependentData()
        db.syncMetaDao().upsert(
            SyncMetaEntity(id = 1, lastSyncedPage = 18,
                lastSyncedAt = 44L, isInitialBuildCompleted = true)
        )
        val ownedEngine = RecordSyncEngine(recordProvider, db, settings, db.aiSeriesDictionaryDao(), context)
        val initialized = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
        initialized.isAccessible = true
        withTimeout(10_000L) { (initialized.get(ownedEngine) as CompletableDeferred<*>).await() }
        assertTrue("owned cache must qualify for smart sync before the transition", ownedEngine.recordCacheReady.value)
        val flag = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
        flag.isAccessible = true
        flag.setBoolean(ownedEngine, true)
        try {
            ownedEngine.clearDatabase()
            ownedEngine.smartSync()
            Mockito.verifyNoInteractions(recordProvider)
            assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
            val meta = db.syncMetaDao().getSyncMeta()
            assertEquals(18, meta?.lastSyncedPage)
            assertEquals(true, meta?.isInitialBuildCompleted)
        } finally {
            flag.setBoolean(ownedEngine, false)
        }
    }


    @Test
    fun syncQueuedBehindHeldMutexIsRejectedWhenActualBackendSwitchStarts() = runBlocking {
        val provider = Mockito.mock(RecordProvider::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val actual = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
        val initialization = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
            .apply { isAccessible = true }
            .get(actual) as CompletableDeferred<*>
        withTimeout(10_000L) { initialization.await() }
        val mutex = RecordSyncEngine::class.java.getDeclaredField("syncMutex")
            .apply { isAccessible = true }.get(actual) as Mutex
        mutex.lock()
        try {
            // Undispatched start runs until the suspended lock acquisition.
            val oldSync = async(start = CoroutineStart.UNDISPATCHED) { actual.syncAllRecords() }
            assertFalse(oldSync.isCompleted)
            val switching = async(Dispatchers.Default) {
                actual.switchBackend { selectedBackend.value = "EDCB" }
            }
            val flag = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
                .apply { isAccessible = true }
            withTimeout(10_000L) {
                while (!flag.getBoolean(actual)) delay(10L)
            }
            mutex.unlock()
            withTimeout(15_000L) {
                oldSync.await()
                switching.await()
            }
            Mockito.verifyNoInteractions(provider)
            assertEquals("EDCB", selectedBackend.value)
        } finally {
            if (mutex.isLocked) mutex.unlock()
        }
    }


    @Test
    fun cancellingActiveDictionaryBeforeFailedSwitchPreservesPublishedCacheAndRestoresReady() = runBlocking {
        seedIndependentData()
        db.syncMetaDao().upsert(SyncMetaEntity(1, 6, 44L, true))
        assertTrue(context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().putString("backend", "KONOMITV").putBoolean("pending", false).commit())
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val actual = RecordSyncEngine(
            Mockito.mock(RecordProvider::class.java), db, settings, db.aiSeriesDictionaryDao(), context
        )
        val ready = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
            .apply { isAccessible = true }.get(actual) as CompletableDeferred<*>
        withTimeout(10_000L) { ready.await() }
        assertTrue(actual.recordCacheReady.value)
        val workerReady = CompletableDeferred<Unit>()
        val workerCancelled = CompletableDeferred<Unit>()
        val allowWorkerExit = CompletableDeferred<Unit>()
        val callbackEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val worker = scope.launch {
            workerReady.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                workerCancelled.complete(Unit)
                withContext(NonCancellable) { allowWorkerExit.await() }
            }
        }
        val field = RecordSyncEngine::class.java.getDeclaredField("dictionaryJob")
            .apply { isAccessible = true }
        workerReady.await()
        field.set(actual, worker)
        try {
            val switching = async(Dispatchers.Default) {
                runCatching {
                    actual.switchBackend {
                        callbackEntered.set(true)
                        throw IllegalStateException("injected setting failure")
                    }
                }.exceptionOrNull()
            }
            withTimeout(10_000L) { workerCancelled.await() }
            assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
            assertFalse("dictionary is cancelled but must not finish until released", worker.isCompleted)
            assertFalse("settings must not be written while old dictionary job is still finishing",
                callbackEntered.get())
            allowWorkerExit.complete(Unit)
            val error = withTimeout(15_000L) { switching.await() }
            assertTrue(error is IllegalStateException)
            assertTrue("settings callback must run only after dictionary job has exited",
                callbackEntered.get())
            assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
            assertTrue("published ready state must be restored after failed setting save",
                actual.recordCacheReady.value)
            assertFalse(context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
                .getBoolean("pending", true))
        } finally {
            allowWorkerExit.complete(Unit)
            worker.cancel()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun clearQueuedBehindMutexSkipsDestructionWhenSwitchBeginsDuringWait() = runBlocking {
        seedIndependentData()
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val provider = Mockito.mock(RecordProvider::class.java)
        val actual = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
        val lockField = RecordSyncEngine::class.java.getDeclaredField("syncMutex")
            .apply { isAccessible = true }
        val mutex = lockField.get(actual) as Mutex
        val switchReachedLock = CompletableDeferred<Unit>()
        val allowRealSwitchClear = CompletableDeferred<Unit>()
        val lockCalls = java.util.concurrent.atomic.AtomicInteger()
        lockField.set(actual, object : Mutex by mutex {
            override suspend fun lock(owner: Any?) {
                if (lockCalls.incrementAndGet() == 2) {
                    switchReachedLock.complete(Unit)
                    allowRealSwitchClear.await()
                }
                mutex.lock(owner)
            }
        })
        mutex.lock()
        try {
            val clear = async(start = CoroutineStart.UNDISPATCHED) { actual.clearDatabase() }
            assertFalse(clear.isCompleted)
            val switching = async(Dispatchers.Default) {
                actual.switchBackend { selectedBackend.value = "EDCB" }
            }
            val flag = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
                .apply { isAccessible = true }
            withTimeout(10_000L) { switchReachedLock.await() }
            assertTrue(flag.getBoolean(actual))
            mutex.unlock()
            withTimeout(15_000L) { clear.await() }
            assertEquals("queued clear must not wipe old recording before actual switch", listOf(19),
                db.recordedProgramDao().getAllIds())
            assertEquals("sync meta must remain unchanged until switch itself clears",
                null, db.syncMetaDao().getSyncMeta())
            allowRealSwitchClear.complete(Unit)
            withTimeout(15_000L) { switching.await() }
            assertEquals("EDCB", selectedBackend.value)
            Mockito.verifyNoInteractions(provider)
            assertEquals("history", db.watchHistoryDao().getById(19)?.title)
            assertEquals("epg", db.epgCacheDao().getCache("KONOMITV_GR")?.dataJson)
        } finally {
            allowRealSwitchClear.complete(Unit)
            if (mutex.isLocked) mutex.unlock()
        }
    }


    @Test
    fun smartSyncFindsLockedSyncMutexAndNeverQueuesOldBackendProviderRead() = runBlocking {
        seedIndependentData()
        db.syncMetaDao().upsert(SyncMetaEntity(1, 18, 44L, true))
        assertTrue(context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().putString("backend", "KONOMITV").putBoolean("pending", false).commit())
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val provider = Mockito.mock(RecordProvider::class.java)
        val actual = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
        val ready = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
            .apply { isAccessible = true }.get(actual) as CompletableDeferred<*>
        ready.await()
        assertTrue(actual.recordCacheReady.value)
        val lock = RecordSyncEngine::class.java.getDeclaredField("syncMutex")
            .apply { isAccessible = true }.get(actual) as Mutex
        lock.lock()
        try {
            withTimeout(10_000L) { actual.smartSync() }
            Mockito.verifyNoInteractions(provider)
            assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
        } finally {
            lock.unlock()
        }
    }


    @Test
    fun backendSettingCallbackWaitsForActiveRecordedSyncJobToExit() = runBlocking {
        seedIndependentData()
        db.syncMetaDao().upsert(
            SyncMetaEntity(id = 1, lastSyncedPage = 0, lastSyncedAt = 20L,
                isInitialBuildCompleted = true)
        )
        assertTrue(context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().putString("backend", "KONOMITV").putBoolean("pending", false).commit())
        val provider = Mockito.mock(RecordProvider::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            com.beeregg2001.komorebi.data.model.RecordedApiResponse(
                total = 0, recordedPrograms = emptyList()
            )
        }.`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val actual = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
        val callbackEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val oldSync = async(Dispatchers.IO) {
                runCatching { actual.syncAllRecords(forceFullSync = false) }
            }
            assertTrue("normal sync must reach actual Provider", entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val switching = async(Dispatchers.Default) {
                actual.switchBackend {
                    callbackEntered.set(true)
                    selectedBackend.value = "EDCB"
                }
            }
            val flag = RecordSyncEngine::class.java.getDeclaredField("switchingBackend")
                .apply { isAccessible = true }
            withTimeout(10_000L) { while (!flag.getBoolean(actual)) delay(10) }
            assertFalse("settings must not be written before old Provider sync has exited",
                callbackEntered.get())
            assertEquals(listOf(19), db.recordedProgramDao().getAllIds())
            release.countDown()
            withTimeout(15_000L) { oldSync.await(); switching.await() }
            assertTrue(callbackEntered.get())
            assertEquals("EDCB", selectedBackend.value)
            assertTrue(db.recordedProgramDao().getAllIds().isEmpty())
        } finally { release.countDown() }
    }


    @Test
    fun smartSyncPassingOuterLockCheckMustRecheckSwitchAfterWaitingForMutex() = runBlocking {
        seedIndependentData()
        db.syncMetaDao().upsert(SyncMetaEntity(1, 18, 44L, true))
        assertTrue(context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().putString("backend", "KONOMITV").putBoolean("pending", false).commit())
        val provider = Mockito.mock(RecordProvider::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        val actual = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
        val ready = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
            .apply { isAccessible = true }.get(actual) as CompletableDeferred<*>
        withTimeout(10_000L) { ready.await() }
        assertTrue(actual.recordCacheReady.value)
        val field = RecordSyncEngine::class.java.getDeclaredField("syncMutex")
            .apply { isAccessible = true }
        val raw = field.get(actual) as Mutex
        val checked = CompletableDeferred<Unit>()
        val switchAttempt = CompletableDeferred<Unit>()
        val releaseSwitch = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        field.set(actual, object : Mutex by raw {
            override val isLocked: Boolean
                get() {
                    // Race injection: smartSync observes an unlocked mutex,
                    // then contends for it, proving the *inner* guard independently.
                    checked.complete(Unit)
                    return false
                }
            override suspend fun lock(owner: Any?) {
                if (calls.incrementAndGet() == 2) {
                    switchAttempt.complete(Unit)
                    releaseSwitch.await()
                }
                raw.lock(owner)
            }
        })
        raw.lock()
        try {
            val smart = async(Dispatchers.IO) { actual.smartSync() }
            withTimeout(10_000L) { checked.await() }
            val switching = async(Dispatchers.IO) {
                actual.switchBackend { selectedBackend.value = "EDCB" }
            }
            withTimeout(10_000L) { switchAttempt.await() }
            raw.unlock()
            withTimeout(10_000L) { smart.await() }
            assertEquals("smartSync must not read old Provider after switch begins", 0,
                Mockito.mockingDetails(provider).invocations.size)
            assertEquals("smartSync must not clear rows before legitimate switch", listOf(19),
                db.recordedProgramDao().getAllIds())
            releaseSwitch.complete(Unit)
            withTimeout(15_000L) { switching.await() }
        } finally {
            releaseSwitch.complete(Unit)
            if (raw.isLocked) raw.unlock()
        }
    }

}
