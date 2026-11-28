package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startup ownership gate exercised through the actual RecordSyncEngine initializer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordCacheOwnershipRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun cleanup() {
        db.close()
        context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun initialize(backend: String): RecordSyncEngine {
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf(backend))
        val engine = RecordSyncEngine(
            Mockito.mock(RecordProvider::class.java),
            db, settings, db.aiSeriesDictionaryDao(), context
        )
        val field = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ready = field.get(engine) as CompletableDeferred<Unit>
        withTimeout(10_000L) { ready.await() }
        return engine
    }

    private suspend fun markBuilt() {
        db.syncMetaDao().upsert(
            SyncMetaEntity(id = 1, lastSyncedPage = 5, lastSyncedAt = 100L,
                isInitialBuildCompleted = true)
        )
    }

    private fun setOwner(name: String?, pending: Boolean) {
        val edit = context.getSharedPreferences("record_cache_backend_owner", Context.MODE_PRIVATE)
            .edit().putBoolean("pending", pending)
        if (name == null) edit.remove("backend") else edit.putString("backend", name)
        assertTrue(edit.commit())
    }

    @Test
    fun matchingOwnerWithCompletedSyncIsPublishable() = runBlocking {
        markBuilt()
        setOwner("KONOMITV", false)
        assertTrue(initialize("KONOMITV").recordCacheReady.value)
    }

    @Test
    fun mismatchedOwnerMustNotPublishOldRecordings() = runBlocking {
        markBuilt()
        setOwner("EDCB", false)
        assertFalse(initialize("KONOMITV").recordCacheReady.value)
    }

    @Test
    fun pendingSwitchMustNotPublishEvenMatchingOwner() = runBlocking {
        markBuilt()
        setOwner("KONOMITV", true)
        assertFalse(initialize("KONOMITV").recordCacheReady.value)
    }

    @Test
    fun unknownOwnerMustNotGuessLegacyDatabaseOwnership() = runBlocking {
        markBuilt()
        setOwner(null, false)
        assertFalse(initialize("KONOMITV").recordCacheReady.value)
    }

    @Test
    fun incompleteSyncMustNotPublishEvenMatchingOwner() = runBlocking {
        setOwner("KONOMITV", false)
        assertFalse(initialize("KONOMITV").recordCacheReady.value)
    }

    @Test
    fun unreadableCacheCannotPassCompletionGateOrStartSmartSync() = runBlocking {
        markBuilt()
        for ((owner, pending) in listOf("EDCB" to false, "KONOMITV" to true)) {
            setOwner(owner, pending)
            val provider = Mockito.mock(RecordProvider::class.java)
            val settings = Mockito.mock(SettingsRepository::class.java)
            Mockito.`when`(settings.backendType).thenReturn(flowOf("KONOMITV"))
            val engine = RecordSyncEngine(provider, db, settings, db.aiSeriesDictionaryDao(), context)
            val initialized = RecordSyncEngine::class.java.getDeclaredField("ownerInitialized")
                .apply { isAccessible = true }
                .get(engine) as CompletableDeferred<*>
            withTimeout(10_000L) { initialized.await() }
            assertFalse(engine.recordCacheReady.value)
            assertFalse(engine.isInitialBuildCompleted())
            engine.smartSync()
            Mockito.verifyNoInteractions(provider)
        }
    }

}
