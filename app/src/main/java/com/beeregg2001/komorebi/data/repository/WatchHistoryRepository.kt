package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.BackendWatchHistoryDao
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.BackendWatchHistoryEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val backendHistoryDao: BackendWatchHistoryDao,
    private val lastChannelDao: LastChannelDao,
    private val konomiRepository: KonomiRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase
) {
    private val gson = Gson()

    // 旧watch_historyはbackend不明。データは保持するが新方式へ推測移行しない。
    // 新しいテーブルではbackendと録画IDを複合主キーにし、履歴と出所を同じ行に保存する。
    private fun toRow(entity: WatchHistoryEntity, backend: String): BackendWatchHistoryEntity =
        BackendWatchHistoryEntity(
            backend = backend, recordId = entity.id,
            payloadJson = gson.toJson(entity), watchedAt = entity.watchedAt
        )

    private fun fromRow(row: BackendWatchHistoryEntity): WatchHistoryEntity =
        gson.fromJson(row.payloadJson, WatchHistoryEntity::class.java)

    suspend fun isHistoryOwnedBy(id: Int, backend: String): Boolean =
        backendHistoryDao.findById(backend, id) != null

    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> =
        getLocalWatchHistory().map { it.map(KonomiDataMapper::toUiModel) }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        // このAPIは常にKonomiTVが所有する履歴。切替後の現在backendで上書きしない。
        val response = apiService.getWatchHistory()
        if (response.isNotEmpty()) saveAllToLocalHistory(
            response.map(KonomiDataMapper::toEntity), "KONOMITV"
        )
    }

    suspend fun saveWatchHistory(
        program: RecordedProgram, positionSeconds: Double, backend: String
    ) {
        val entity = KonomiDataMapper.toEntity(program, positionSeconds)
        backendHistoryDao.upsert(toRow(entity, backend))
        // 既存ネットワーク同期はKonomiTVの履歴に限る。
        if (backend == "KONOMITV") runCatching {
            konomiRepository.syncPlaybackPosition(program.id.toString(), positionSeconds)
        }
    }

    // 選択backendとRoomから取り出した履歴を単一emissionで公開する。
    // backend切替時の直前のリストを、新backendの録画履歴と誤認しない。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getOwnedLocalWatchHistory(): Flow<Pair<String, List<WatchHistoryEntity>>> =
        settingsRepository.backendType.flatMapLatest { backend ->
            backendHistoryDao.observeRecent(backend).map { rows ->
                backend to rows.map(::fromRow)
            }
        }

    fun getLocalWatchHistory(): Flow<List<WatchHistoryEntity>> =
        getOwnedLocalWatchHistory().map { it.second }

    suspend fun getHistoryEntityById(id: Int, backend: String): WatchHistoryEntity? =
        backendHistoryDao.findById(backend, id)?.let(::fromRow)

    fun getLastChannels() = lastChannelDao.getLastChannels()

    suspend fun getHistoryEntitiesByIds(ids: List<Int>, backend: String): List<WatchHistoryEntity> =
        backendHistoryDao.findByIds(backend, ids).map(::fromRow)

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity, backend: String) {
        backendHistoryDao.upsert(toRow(entity, backend))
    }

    suspend fun saveAllToLocalHistory(entities: List<WatchHistoryEntity>, backend: String) {
        // backendは取得の開始前に確定した値を呼出元から受け取り、
        // 行と所有者を一つのRoomトランザクションに保存する。
        db.withTransaction { backendHistoryDao.upsertAll(entities.map { toRow(it, backend) }) }
    }

    suspend fun clearWatchHistory() {
        db.withTransaction {
            backendHistoryDao.clearAll()
            watchHistoryDao.clearAll()
        }
    }
}
