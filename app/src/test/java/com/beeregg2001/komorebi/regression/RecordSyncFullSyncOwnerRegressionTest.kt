package com.beeregg2001.komorebi.regression

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production syncAllRecords: a cache with a mismatched/missing owner MUST be
 * rebuilt in full. A matching completed owner MUST NOT be wiped on normal refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordSyncFullSyncOwnerRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var provider: RecordProvider
    private lateinit var settings: SettingsRepository
    private val selection = MutableStateFlow("EDCB")
    private val engines = mutableListOf<RecordSyncEngine>()

    private fun prefs() = context.getSharedPreferences(
        "record_cache_backend_owner", Context.MODE_PRIVATE
    )

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        provider = Mockito.mock(RecordProvider::class.java)
        Mockito.doReturn(RecordedApiResponse(total = 0, recordedPrograms = emptyList()))
            .`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selection)
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                id = 1, lastSyncedPage = 19, lastSyncedAt = 100L,
                isInitialBuildCompleted = true
            )
        )
        db.recordedProgramDao().upsertAll(listOf(existing()))
    }

    @After
    fun teardown() {
        engines.forEach { engine ->
            val field = RecordSyncEngine::class.java.getDeclaredField("engineScope")
            field.isAccessible = true
            (field.get(engine) as CoroutineScope).cancel()
        }
        db.close()
        prefs().edit().clear().commit()
    }

    private fun engine(): RecordSyncEngine =
        RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
            .also { engines.add(it) }

    private fun existing() = RecordedProgramEntity(
        id = 44, title = "previous backend recording",
        startTime = "2026-11-10T00:00:00+09:00",
        endTime = "2026-11-10T01:00:00+09:00",
        videoDuration = 3600.0, hasKeyFrames = true,
        isRecording = false, playbackPosition = 0.0,
        channelId = null, channelType = null, channelName = null,
        genres = null
    )

    @Test
    fun unknownOwnerForcesFullSyncAndDropsUnownedRowsBeforePublishing(): Unit = runBlocking {
        val sync = engine()
        sync.syncAllRecords(forceFullSync = false)
        assertTrue(db.recordedProgramDao().getAllIds().isEmpty())
        assertEquals("EDCB", prefs().getString("backend", null))
        assertFalse(prefs().getBoolean("pending", true))
        assertEquals(0, db.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertTrue(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
        assertTrue(sync.recordCacheReady.value)
        Mockito.verify(provider, Mockito.atLeastOnce()).getRecordedPrograms(
            Mockito.eq(1), Mockito.anyInt()
        )
    }

    @Test
    fun matchingOwnerNormalSyncPreservesRowsOnEmptyUpstreamResult() = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        val sync = engine()
        sync.syncAllRecords(forceFullSync = false)
        Mockito.verify(provider, Mockito.atLeastOnce()).getRecordedPrograms(
            Mockito.eq(1), Mockito.anyInt()
        )
        assertEquals(listOf(44), db.recordedProgramDao().getAllIds())
        assertTrue(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
        assertTrue(sync.recordCacheReady.value)
    }

    @Test
    fun explicitForceFullSyncPurgesOldRowsEvenWithMatchingOwner() = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        val sync = engine()
        sync.syncAllRecords(forceFullSync = true)
        assertTrue("forceFullSync must not retain records absent upstream",
            db.recordedProgramDao().getAllIds().isEmpty())
        assertEquals("EDCB", prefs().getString("backend", null))
        assertTrue(sync.recordCacheReady.value)
    }

    @Test
    fun failedUnknownOwnerRebuildMustRemainUnpublishedWithResetResumeCheckpoint() = runBlocking {
        Mockito.doAnswer { throw IllegalStateException("upstream unavailable") }
            .`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val sync = engine()
        sync.syncAllRecords(forceFullSync = false)
        assertTrue("unverified old owner rows must be purged",
            db.recordedProgramDao().getAllIds().isEmpty())
        assertEquals(0, db.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertFalse(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
        assertFalse(sync.recordCacheReady.value)
        assertNotNull(sync.syncProgress.value.error)
    }
    @Test
    fun smartSyncDoesNotLaunchSecondDictionaryJobWhileExistingJobIsActive(): Unit = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        val program = com.beeregg2001.komorebi.data.model.RecordedProgram(
            id = 77, title = "latest recorded", description = "",
            startTime = "2026-11-10T00:00:00+09:00",
            endTime = "2026-11-10T01:00:00+09:00",
            duration = 3600.0, isPartiallyRecorded = false,
            recordedVideo = com.beeregg2001.komorebi.data.model.RecordedVideo(
                id = 77, status = "Recorded", filePath = "recorded.ts",
                recordingStartTime = "2026-11-10T00:00:00+09:00",
                recordingEndTime = "2026-11-10T01:00:00+09:00",
                duration = 3600.0, containerFormat = "MPEG-TS",
                videoCodec = "H264", audioCodec = "AAC"
            )
        )
        Mockito.doReturn(RecordedApiResponse(total = 1, recordedPrograms = listOf(program)))
            .`when`(provider).getRecordedPrograms(1)
        val sync = engine()
        val gate = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
        gate.isAccessible = true
        (gate.get(sync) as kotlinx.coroutines.CompletableDeferred<*>).await()
        assertTrue("owned cache must allow smart sync", sync.recordCacheReady.value)
        val dictionary = RecordSyncEngine::class.java.getDeclaredField("dictionaryJob")
        dictionary.isAccessible = true
        val existingJob = kotlinx.coroutines.Job()
        try {
            dictionary.set(sync, existingJob)
            sync.smartSync()
            assertSame("smartSync must not replace active dictionaryJob", existingJob, dictionary.get(sync))
            assertTrue(existingJob.isActive)
            Mockito.verify(provider).getRecordedPrograms(1)
        } finally {
            existingJob.cancel()
        }
    }

    @Test
    fun completedSyncMustNotStartSecondDictionaryJobWhenOneBecameActiveDuringFetch(): Unit = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            RecordedApiResponse(total = 0, recordedPrograms = emptyList())
        }.`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val sync = engine()
        val jobField = RecordSyncEngine::class.java.getDeclaredField("dictionaryJob")
        jobField.isAccessible = true
        val activeDictionary = kotlinx.coroutines.Job()
        try {
            val current = async(Dispatchers.IO) {
                sync.syncAllRecords(forceFullSync = false)
            }
            assertTrue("record fetch must be in flight", entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            // Inject a concurrent dictionary worker after syncAllRecords has passed
            // its initial cancel+join, but before the final post-sync launch gate.
            jobField.set(sync, activeDictionary)
            release.countDown()
            kotlinx.coroutines.withTimeout(15_000L) { current.await() }
            assertSame("post-sync gate must retain the already-active worker",
                activeDictionary, jobField.get(sync))
            assertTrue(activeDictionary.isActive)
            assertTrue(sync.recordCacheReady.value)
        } finally {
            release.countDown()
            activeDictionary.cancel()
        }
    }


    private fun engineWithFailingOwnerCommit(failAt: Int): RecordSyncEngine {
        val original = prefs()
        val commitCalls = AtomicInteger()
        val intercepted = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val underlying = original.edit()
                return object : SharedPreferences.Editor by underlying {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        underlying.putString(key, value)
                        return this
                    }
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        underlying.putBoolean(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (commitCalls.incrementAndGet() == failAt) return false
                        return underlying.commit()
                    }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return if (name == "record_cache_backend_owner") intercepted
                else super.getSharedPreferences(name, mode)
            }
        }
        return RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), wrapped)
            .also { engines.add(it) }
    }

    @Test
    fun failurePersistingOwnerAfterInitialClearNeverPublishesCache() = runBlocking {
        val sync = engineWithFailingOwnerCommit(1)
        sync.syncAllRecords(forceFullSync = false)
        assertFalse(sync.recordCacheReady.value)
        assertNotNull(sync.syncProgress.value.error)
        assertEquals(0, db.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertFalse(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
        assertNull(prefs().getString("backend", null))
    }

    @Test
    fun failurePersistingOwnerImmediatelyBeforePublicationMustRemainUnreadyOnRestart() = runBlocking {
        val sync = engineWithFailingOwnerCommit(2)
        sync.syncAllRecords(forceFullSync = false)
        assertFalse(sync.recordCacheReady.value)
        assertNotNull(sync.syncProgress.value.error)
        val rebooted = engine()
        val initialized = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
            .apply { isAccessible = true }.get(rebooted) as CompletableDeferred<*>
        withTimeout(10_000L) { initialized.await() }
        assertFalse("commit failure must never appear as trusted after restart",
            rebooted.recordCacheReady.value)
    }


    private fun remoteRecord(id: Int, title: String) =
        com.beeregg2001.komorebi.data.model.RecordedProgram(
            id = id, title = title, description = "",
            startTime = "2030-01-01T00:00:00+09:00",
            endTime = "2030-01-01T01:00:00+09:00",
            duration = 3600.0, isPartiallyRecorded = false,
            recordedVideo = com.beeregg2001.komorebi.data.model.RecordedVideo(
                id = id, status = "Recorded", filePath = "record-$id.ts",
                recordingStartTime = "2030-01-01T00:00:00+09:00",
                recordingEndTime = "2030-01-01T01:00:00+09:00",
                duration = 3600.0, containerFormat = "ts",
                videoCodec = "H264", audioCodec = "AAC"
            )
        )

    @Test
    fun matchingOwnerIncompleteSyncResumesAtCheckpointPlusOneAndKeepsEarlierRows() = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        db.syncMetaDao().upsert(SyncMetaEntity(1, 2, 123L, false))
        val seenPages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        Mockito.doAnswer { invocation ->
            val page = invocation.arguments[0] as Int
            seenPages.add(page)
            if (page == 3) RecordedApiResponse(2, listOf(remoteRecord(55, "resumed page three")))
            else RecordedApiResponse(2, emptyList())
        }.`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val sync = engine()
        sync.syncAllRecords()
        assertTrue("resumed sync must begin at page three", 3 in seenPages)
        assertFalse("must not refetch old page one", 1 in seenPages)
        assertFalse("must not refetch old page two", 2 in seenPages)
        assertTrue(db.recordedProgramDao().getAllIds().containsAll(listOf(44,55)))
        assertEquals(0, db.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertTrue(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
        assertTrue(sync.recordCacheReady.value)
    }

    @Test
    fun completedOwnerIncrementalSyncUpdatesAndPreservesOtherRowsAcrossNonemptyPages() = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        db.recordedProgramDao().upsertAll(listOf(existing().copy(id=66, title="unrelated retained")))
        val pages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        Mockito.doAnswer { invocation ->
            val page = invocation.arguments[0] as Int
            pages.add(page)
            when (page) {
                1 -> RecordedApiResponse(2, listOf(remoteRecord(44, "changed remote title")))
                2 -> RecordedApiResponse(2, listOf(remoteRecord(55, "new remote item")))
                else -> RecordedApiResponse(2, emptyList())
            }
        }.`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val sync = engine()
        sync.syncAllRecords(forceFullSync = false)
        assertEquals(listOf(1, 2), pages.toList())
        assertTrue(db.recordedProgramDao().getAllIds().containsAll(listOf(44,55,66)))
        val row = db.recordedProgramDao().getByIds(listOf(44)).single()
        assertEquals("changed remote title", row.title)
        assertTrue(sync.recordCacheReady.value)
    }

    @Test
    fun explicitFullRebuildWithNonemptyRemotePagesRemovesOldLocalOrphans() = runBlocking {
        prefs().edit().putString("backend", "EDCB").putBoolean("pending", false).commit()
        Mockito.doAnswer { invocation ->
            val page = invocation.arguments[0] as Int
            if (page == 1) RecordedApiResponse(1, listOf(remoteRecord(55, "replacement")))
            else RecordedApiResponse(1, emptyList())
        }.`when`(provider).getRecordedPrograms(Mockito.anyInt(), Mockito.anyInt())
        val sync = engine()
        sync.syncAllRecords(forceFullSync = true)
        assertEquals(listOf(55), db.recordedProgramDao().getAllIds())
        assertTrue(sync.recordCacheReady.value)
        assertTrue(db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true)
    }

}
