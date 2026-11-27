package com.beeregg2001.komorebi.regression

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity
import com.beeregg2001.komorebi.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production migration against SQL from the pre-PR #3 v15 entities. */
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
        context.deleteDatabase(name)
    }

    private fun seedV15(seed: (SQLiteDatabase) -> Unit) {
        val schema = checkNotNull(javaClass.getResourceAsStream("/room/komorebi-v15.sql"))
            .bufferedReader().use { it.readText() }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { disk ->
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach { disk.execSQL(it) }
            seed(disk)
            disk.version = 15
        }
    }

    private fun insertHistory(disk: SQLiteDatabase, id: Int, title: String, position: Double) {
        disk.execSQL(
            """
            INSERT INTO watch_history (
                id, title, description, startTime, endTime, duration, videoId, playbackPosition,
                watchedAt, tileColumns, tileRows, tileInterval, tileWidth, tileHeight
            ) VALUES (?, ?, '', '', '', 3600.0, ?, ?, 500, 1, 1, 10.0, 320, 180)
            """.trimIndent(),
            arrayOf<Any>(id, title, id, position)
        )
    }

    @Test
    fun v15To16AddsBackendHistoryWithoutDeletingExistingRows() = runBlocking {
        seedV15 { disk ->
            insertHistory(disk, 84, "existing recording", 18.0)
            disk.execSQL(
                "INSERT INTO last_watched_channel VALUES (1, 'existing-channel', 'old channel', 'GR', '1', 10, 20, 100)"
            )
            disk.execSQL("INSERT INTO epg_cache VALUES ('GR', 'legacy-cache', 10)")
            disk.execSQL("INSERT INTO sync_meta VALUES (1, 12, 123, 1)")
        }

        val migrated = DatabaseModule.provideAppDatabase(context)
        current = migrated
        val old = requireNotNull(migrated.watchHistoryDao().getById(84))
        assertEquals("existing recording", old.title)
        assertEquals(18.0, old.playbackPosition, 0.0)
        assertEquals("existing-channel", migrated.lastChannelDao().getStartupCandidates().single().channelId)
        assertEquals("legacy-cache", migrated.epgCacheDao().getCache("GR")?.dataJson)
        assertEquals(12, migrated.syncMetaDao().getSyncMeta()?.lastSyncedPage)
        assertEquals(true, migrated.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted)
        val history = migrated.backendWatchHistoryDao()
        assertNull(history.findById("KONOMITV", 84))
        history.upsert(BackendWatchHistoryEntity("KONOMITV", 84, "migrated-table-ready", 101L))
        assertEquals("migrated-table-ready", history.findById("KONOMITV", 84)?.payloadJson)
    }

    @Test
    fun v15SchemaKeepsRecordedProgramsAndSeparatesSameIdBackendHistory() = runBlocking {
        seedV15 { disk ->
            insertHistory(disk, 55, "preupgrade history", 37.0)
            disk.execSQL(
                """
                INSERT INTO recorded_programs (
                    id, title, series_name, is_episodic, start_time, end_time, video_duration,
                    has_key_frames, is_recording, playback_position
                ) VALUES (
                    55, 'preupgrade recording', 'test-series', 1, '2030-01-01T00:00:00+09:00',
                    '2030-01-01T01:00:00+09:00', 3600.0, 1, 0, 18.0
                )
                """.trimIndent()
            )
        }

        val migrated = DatabaseModule.provideAppDatabase(context)
        current = migrated
        val recording = requireNotNull(migrated.recordedProgramDao().getById(55))
        assertEquals("preupgrade recording", recording.title)
        assertEquals("test-series", recording.seriesName)
        assertEquals(18.0, recording.playbackPosition, 0.0)
        val oldHistory = requireNotNull(migrated.watchHistoryDao().getById(55))
        assertEquals("preupgrade history", oldHistory.title)
        assertEquals(37.0, oldHistory.playbackPosition, 0.0)
        val history = migrated.backendWatchHistoryDao()
        history.upsert(BackendWatchHistoryEntity("KONOMITV", 55, "source K", 1000L))
        history.upsert(BackendWatchHistoryEntity("EDCB", 55, "source E", 2000L))
        assertEquals("source K", history.findById("KONOMITV", 55)?.payloadJson)
        assertEquals("source E", history.findById("EDCB", 55)?.payloadJson)
        assertEquals(oldHistory, migrated.watchHistoryDao().getById(55))
        assertEquals(listOf(55), migrated.recordedProgramDao().getAllIds())
    }

    @Test
    fun v15To16PreservesDictionaryAndEpgParentChildRows() = runBlocking {
        seedV15 { disk ->
            disk.execSQL("INSERT INTO ai_series_dictionary VALUES ('old-title', 'old-series', 987)")
            disk.execSQL(
                """INSERT INTO epg_channel
                (id, displayChannelId, networkId, serviceId, transportStreamId, remoconId,
                 channelNumber, type, name, jikkyoForce, isSubchannel, isRadiochannel, isWatchable)
                VALUES ('old-channel', 'display-old', 1, 2, 3, 4, '011', 'GR', 'old station',
                        NULL, 0, 0, 1)""".trimIndent()
            )
            disk.execSQL(
                """INSERT INTO epg_program
                (id, channelId, networkId, serviceId, eventId, title, description, extended,
                 detailJson, startTime, endTime, startTimeEpoch, endTimeEpoch, duration,
                 isFree, genresJson, videoType, audioType, audioSamplingRate)
                VALUES ('old-event', 'old-channel', 1, 2, 9, 'original show', 'original description',
                        NULL, NULL, '2030-01-01T00:00:00+09:00', '2030-01-01T01:00:00+09:00',
                        1893423600, 1893427200, 3600, 1, NULL, NULL, NULL, NULL)""".trimIndent()
            )
        }
        val migrated = DatabaseModule.provideAppDatabase(context)
        current = migrated
        val sql = migrated.openHelper.readableDatabase
        sql.query("SELECT originalTitle, normalizedSeriesName, updatedAt FROM ai_series_dictionary").use {
            assertTrue(it.moveToFirst())
            assertEquals("old-title", it.getString(0))
            assertEquals("old-series", it.getString(1))
            assertEquals(987L, it.getLong(2))
            assertFalse(it.moveToNext())
        }
        sql.query(
            """SELECT p.id, p.title, c.name FROM epg_program p
               JOIN epg_channel c ON p.channelId = c.id WHERE p.id = 'old-event'"""
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("old-event", it.getString(0))
            assertEquals("original show", it.getString(1))
            assertEquals("old station", it.getString(2))
            assertFalse(it.moveToNext())
        }
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        sql.query("SELECT COUNT(*) FROM backend_watch_history").use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

}
