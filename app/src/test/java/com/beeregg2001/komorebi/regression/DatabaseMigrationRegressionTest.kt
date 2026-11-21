package com.beeregg2001.komorebi.regression

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
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
}
