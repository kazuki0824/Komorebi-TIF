package com.beeregg2001.komorebi.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.beeregg2001.komorebi.data.local.dao.BackendWatchHistoryDao
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao // ★復元
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.SyncMetaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private val migrate15To16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `backend_watch_history` (`backend` TEXT NOT NULL, `recordId` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `watchedAt` INTEGER NOT NULL, PRIMARY KEY(`backend`, `recordId`))")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "komorebi.db")
            .addMigrations(migrate15To16)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideBackendWatchHistoryDao(database: AppDatabase): BackendWatchHistoryDao =
        database.backendWatchHistoryDao()

    @Provides
    fun provideWatchHistoryDao(database: AppDatabase): WatchHistoryDao {
        return database.watchHistoryDao()
    }

    @Provides
    fun provideLastChannelDao(database: AppDatabase): LastChannelDao {
        return database.lastChannelDao()
    }

    // ★復元: EPGキャッシュ用のDaoのProvide
    @Provides
    fun provideEpgCacheDao(database: AppDatabase): EpgCacheDao {
        return database.epgCacheDao()
    }

    // 同期エンジン用のDaoのProvide
    @Provides
    fun provideRecordedProgramDao(database: AppDatabase): RecordedProgramDao {
        return database.recordedProgramDao()
    }

    @Provides
    fun provideSyncMetaDao(database: AppDatabase): SyncMetaDao {
        return database.syncMetaDao()
    }

    // AI辞書用のDaoのProvide
    @Provides
    fun provideAiSeriesDictionaryDao(database: AppDatabase): AiSeriesDictionaryDao {
        return database.aiSeriesDictionaryDao()
    }
}