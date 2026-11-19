package com.beeregg2001.komorebi.data.local.dao

import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LastChannelDao {
    // 既存ネットワークbackendはONID/SIDで重複判定するが、
    // 異なるTIF入力の同じONID/SID（HDMIではともに0）には影響させない。
    @Query("DELETE FROM last_watched_channel WHERE channelId NOT LIKE 'tif:%' AND networkId = :networkId AND serviceId = :serviceId")
    suspend fun deleteByNetworkAndServiceId(networkId: Long, serviceId: Long)

    // TIFは保存済みの完全なchannelId（inputIdを含む）が履歴の識別子。
    @Query("DELETE FROM last_watched_channel WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LastChannelEntity)

    // リポジトリから呼ぶためのトランザクション
    @Transaction
    suspend fun insertOrUpdate(entity: LastChannelEntity) {
        if (entity.channelId.startsWith("tif:")) {
            deleteByChannelId(entity.channelId)
        } else {
            deleteByNetworkAndServiceId(entity.networkId, entity.serviceId)
        }
        insert(entity)
    }

    @Query("SELECT * FROM last_watched_channel ORDER BY updatedAt DESC LIMIT 10")
    fun getLastChannels(): Flow<List<LastChannelEntity>>

    // 起動時選局ではバックエンドの絞込みをLIMITより前に適用する。
    @Query("SELECT * FROM last_watched_channel WHERE (:tif = 1 AND channelId LIKE 'tif:%') OR (:tif = 0 AND channelId NOT LIKE 'tif:%') ORDER BY updatedAt DESC LIMIT 10")
    suspend fun getRecentForBackend(tif: Boolean): List<LastChannelEntity>

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