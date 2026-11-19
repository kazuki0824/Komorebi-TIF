package com.beeregg2001.komorebi.data.repository

import android.os.Build
import android.content.Context
import com.beeregg2001.komorebi.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val lastChannelDao: LastChannelDao,
    private val konomiRepository: KonomiRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext context: Context
) {
    // レガシーの帰属不明IDは保持するが、別backendへ誤再生しない。
    private val historyOwners = context.getSharedPreferences("record_watch_history_owners", Context.MODE_PRIVATE)

    fun isHistoryOwnedBy(id: Int, backend: String): Boolean =
        historyOwners.getString("record_$id", null) == backend

    private fun markHistoryOwner(id: Int, backend: String) {
        check(historyOwners.edit().putString("record_$id", backend).commit()) {
            "視聴履歴の取得元を保存できません。"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> =
        combine(watchHistoryDao.getAllHistory(), settingsRepository.backendType) { entities, backend ->
            entities.filter { isHistoryOwnedBy(it.id, backend) }
                .map { KonomiDataMapper.toUiModel(it) }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        runCatching { apiService.getWatchHistory() }.onSuccess { apiHistoryList ->
            apiHistoryList.forEach { history ->
                val entity = KonomiDataMapper.toEntity(history)
                watchHistoryDao.insertOrUpdate(entity)
                markHistoryOwner(entity.id, "KONOMITV")
            }
        }
    }

    suspend fun saveWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        // 1. ローカルDBに保存
        val entity = KonomiDataMapper.toEntity(program, positionSeconds)
        watchHistoryDao.insertOrUpdate(entity)
        markHistoryOwner(entity.id, settingsRepository.backendType.first())

        // 2. サーバーへ同期
        runCatching {
            konomiRepository.syncPlaybackPosition(program.id.toString(), positionSeconds)
        }
    }

    // ==========================================
    // ★ 追加: KonomiRepository から移行したローカルDB操作
    // ==========================================

    /**
     * ローカルDBにキャッシュされている視聴履歴（レジュームポイント）をFlowとして取得します。
     */
    fun getLocalWatchHistory(): Flow<List<WatchHistoryEntity>> = watchHistoryDao.getAllHistory()

    suspend fun getHistoryEntityById(id: Int): WatchHistoryEntity? {
        return watchHistoryDao.getById(id)
    }

    /**
     * アプリ内で最後に視聴したチャンネル（放送局）のリストをローカルDBから取得します。
     */
    fun getLastChannels() = lastChannelDao.getLastChannels()

    /**
     * 複数の録画番組の視聴履歴をローカルDBから一括で取得します。
     * 同期時の差分チェックなどでパフォーマンスを向上させるために使用します。
     */
    suspend fun getHistoryEntitiesByIds(ids: List<Int>): List<WatchHistoryEntity> {
        return watchHistoryDao.getByIds(ids)
    }

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
        markHistoryOwner(entity.id, settingsRepository.backendType.first())
    }

    /**
     * サーバーから取得した最新の視聴履歴リストをローカルDBへ一括保存します。
     */
    suspend fun saveAllToLocalHistory(entities: List<WatchHistoryEntity>) {
        watchHistoryDao.insertOrUpdateAll(entities)
        val backend = settingsRepository.backendType.first()
        entities.forEach { markHistoryOwner(it.id, backend) }
    }

    // ★追加: 履歴の全削除
    suspend fun clearWatchHistory() {
        watchHistoryDao.clearAll()
    }
}