package com.beeregg2001.komorebi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackendWatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BackendWatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BackendWatchHistoryEntity>)

    @Query("SELECT * FROM backend_watch_history WHERE backend = :backend ORDER BY watchedAt DESC LIMIT 30")
    fun observeRecent(backend: String): Flow<List<BackendWatchHistoryEntity>>

    @Query("SELECT * FROM backend_watch_history WHERE backend = :backend AND recordId = :recordId")
    suspend fun findById(backend: String, recordId: Int): BackendWatchHistoryEntity?

    @Query("SELECT * FROM backend_watch_history WHERE backend = :backend AND recordId IN (:ids)")
    suspend fun findByIds(backend: String, ids: List<Int>): List<BackendWatchHistoryEntity>

    @Query("DELETE FROM backend_watch_history")
    suspend fun clearAll()
}
