package com.beeregg2001.komorebi.data.local.dao

import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LastChannelDao {
    // ★ 修正: channelId(文字列)ではなく、全国共通の networkId と serviceId の組み合わせで重複判定を行う
    @Query("DELETE FROM last_watched_channel WHERE networkId = :networkId AND serviceId = :serviceId")
    suspend fun deleteByNetworkAndServiceId(networkId: Long, serviceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LastChannelEntity)

    // リポジトリから呼ぶためのトランザクション
    @Transaction
    suspend fun insertOrUpdate(entity: LastChannelEntity) {
        deleteByNetworkAndServiceId(entity.networkId, entity.serviceId)
        insert(entity)
    }

    // 表示可能局で絞った後に上限10件を適用するため、DAOでは切り詰めない。
    @Query("SELECT * FROM last_watched_channel ORDER BY updatedAt DESC")
    fun getLastChannels(): Flow<List<LastChannelEntity>>

    // SQL変数上限に依存しない。選局可能IDとの照合と件数上限はRepositoryで適用。
    @Query("SELECT * FROM last_watched_channel ORDER BY updatedAt DESC")
    suspend fun getStartupCandidates(): List<LastChannelEntity>

    @Query("DELETE FROM last_watched_channel")
    suspend fun clearAll()
}

// AppDatabase.kt に DAO を追加
@Database(
    entities = [WatchHistoryEntity::class, LastChannelEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
}