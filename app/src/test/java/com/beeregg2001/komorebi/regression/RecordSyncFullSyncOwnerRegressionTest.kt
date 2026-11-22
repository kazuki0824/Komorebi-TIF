package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
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
    fun unknownOwnerForcesFullSyncAndDropsUnownedRowsBeforePublishing() = runBlocking {
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
}
