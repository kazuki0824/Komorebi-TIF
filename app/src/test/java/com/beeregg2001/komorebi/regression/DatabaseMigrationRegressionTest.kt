package com.beeregg2001.komorebi.regression

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the production DatabaseModule migration and destructive-fallback configuration.
 *
 * The v15 fixture is synthesized from the production v16 schema by dropping the
 * only table added in PR #3 and lowering user_version. This is NOT an exported
 * historical v15 schema, so schema compatibility with every released v15 APK
 * must still be verified separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationRegressionTest {
    private lateinit var context: Context
    private val name = "komorebi.db"
    private var current: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        current?.close()
        current = null
        context.deleteDatabase(name)
    }

    @Test
    fun v15To16AddsBackendHistoryWithoutDeletingExistingRows() = runBlocking {
        val created = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        created.openHelper.writableDatabase
        created.watchHistoryDao().insertOrUpdate(
            WatchHistoryEntity(
                id = 84, title = "existing recording", description = "",
                detailJson = null, startTime = "", endTime = "",
                duration = 0.0, videoId = 84, watchedAt = 100L
            )
        )
        created.lastChannelDao().insert(
            LastChannelEntity(
                channelId = "existing-channel", name = "old channel",
                type = "GR", channelNumber = "1",
                networkId = 10L, serviceId = 20L, updatedAt = 100L
            )
        )
        created.epgCacheDao().insertOrUpdate(EpgCacheEntity("GR", "legacy-cache", 10L))
        created.syncMetaDao().upsert(SyncMetaEntity(id = 1, lastSyncedPage = 12, lastSyncedAt = 123L, isInitialBuildCompleted = true))
        created.close()

        // PR #3 introduces backend_watch_history, leaving other v15 tables intact.
        val disk = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        disk.execSQL("DROP TABLE backend_watch_history")
        disk.version = 15
        disk.close()

        val migrated = DatabaseModule.provideAppDatabase(context)
        current = migrated
        val old = migrated.watchHistoryDao().getById(84)
        assertNotNull("v15 history must not be destructively replaced", old)
        assertEquals("existing recording", old?.title)
        val candidates = migrated.lastChannelDao().getStartupCandidates()
        assertEquals("existing-channel", candidates.single().channelId)
        assertEquals("legacy-cache", migrated.epgCacheDao().getCache("GR")?.dataJson)
        assertEquals(12, migrated.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertEquals(true, migrated.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted)
        assertNull(migrated.backendWatchHistoryDao().findById("KONOMITV", 84))
        migrated.backendWatchHistoryDao().upsert(
            com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity(
                backend = "KONOMITV", recordId = 84,
                payloadJson = "migrated-table-ready", watchedAt = 101L
            )
        )
        assertEquals("migrated-table-ready",
            migrated.backendWatchHistoryDao().findById("KONOMITV", 84)?.payloadJson)
    }
    @Test
    fun v15SyntheticFixtureKeepsRecordedProgramsAndSeparatesSameIdBackendHistory(): Unit = runBlocking {
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries().build()
        original.openHelper.writableDatabase
        original.watchHistoryDao().insertOrUpdate(
            WatchHistoryEntity(
                id = 55, title = "preupgrade history", description = "",
                detailJson = null, startTime = "2030-01-01T00:00:00+09:00",
                endTime = "2030-01-01T01:00:00+09:00",
                duration = 3600.0, videoId = 55, watchedAt = 500L
            )
        )
        original.recordedProgramDao().upsertAll(
            listOf(com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity(
                id = 55, title = "preupgrade recording",
                startTime = "2030-01-01T00:00:00+09:00",
                endTime = "2030-01-01T01:00:00+09:00",
                videoDuration = 3600.0, hasKeyFrames = true,
                isRecording = false, playbackPosition = 18.0,
                channelId = null, channelType = null, channelName = null, genres = null
            ))
        )
        original.close()

        val disk = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE
        )
        disk.execSQL("DROP TABLE backend_watch_history")
        disk.version = 15
        disk.close()

        val migrated = DatabaseModule.provideAppDatabase(context)
        current = migrated
        assertEquals("preupgrade recording",
            migrated.recordedProgramDao().getById(55)?.title)
        assertEquals("preupgrade history",
            migrated.watchHistoryDao().getById(55)?.title)
        val history = migrated.backendWatchHistoryDao()
        history.upsert(
            com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity(
                "KONOMITV", 55, "source K", 1000L
            )
        )
        history.upsert(
            com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity(
                "EDCB", 55, "source E", 2000L
            )
        )
        assertEquals("source K", history.findById("KONOMITV", 55)?.payloadJson)
        assertEquals("source E", history.findById("EDCB", 55)?.payloadJson)
        assertEquals("preupgrade history",
            migrated.watchHistoryDao().getById(55)?.title)
        assertEquals(listOf(55), migrated.recordedProgramDao().getAllIds())
    }

}
