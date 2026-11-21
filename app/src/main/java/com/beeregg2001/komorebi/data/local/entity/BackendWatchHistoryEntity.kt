package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity

/**
 * 録画IDはバックエンド内でのみ一意。旧watch_historyは帰属不明として保存したままにする。
 * 取得元と履歴データを同一Room行へ保存して、別バックエンドとのID衝突を防ぐ。
 */
@Entity(tableName = "backend_watch_history", primaryKeys = ["backend", "recordId"])
data class BackendWatchHistoryEntity(
    val backend: String,
    val recordId: Int,
    val payloadJson: String,
    val watchedAt: Long
)
