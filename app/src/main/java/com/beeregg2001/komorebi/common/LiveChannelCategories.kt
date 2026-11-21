package com.beeregg2001.komorebi.common

/** ライブ一覧とミニチャンネル一覧に共通する種別の表示順・名称。EPG対象とは別。 */
object LiveChannelCategories {
    val orderedTypes = listOf("GR", "BS", "CS", "BS4K", "SKY", "TV", "INPUT")

    fun label(type: String): String = when (type) {
        "GR" -> "地デジ"
        "BS" -> "BS"
        "CS" -> "CS"
        "BS4K" -> "BS4K"
        "SKY" -> "スカパー"
        "TV" -> "その他のTV"
        "INPUT" -> "外部入力"
        else -> type
    }
}
