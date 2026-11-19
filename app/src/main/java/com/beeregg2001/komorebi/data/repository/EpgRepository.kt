package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.data.repository.tif.TifRepository
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EpgSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel
)

/**
 * ★ 最適化: @Singleton を付与。
 * このクラスは番組表データの巨大なメモリキャッシュ([memoryCache])を保持しているが、
 * スコープ未指定だったため Hilt が注入先ごとに別インスタンスを生成していた。
 * 実際 EpgViewModel と HomeViewModel の 2 箇所から注入されており、
 *   - 全放送種別の番組表データがメモリ上に二重に載る（数 MB〜数十 MB 規模）
 *   - 片方が温めたキャッシュをもう片方が使えず、Room 読み出し + GZIP 展開 + Gson パースを
 *     それぞれ独立にやり直す
 * という無駄が発生していた。シングルトン化でこの二重化を解消する。
 */
@Singleton
class EpgRepository @Inject constructor(
    @ApplicationContext private val context: Context, // ★ 追加: キャッシュディレクトリを使用するためContextを注入
    private val epgProvider: DtvProviderProxy,
    private val epgCacheDao: EpgCacheDao,
    private val gson: Gson,
    private val settingsRepository: SettingsRepository,
    private val tifRepository: TifRepository
) {
    private val memoryCache = ConcurrentHashMap<String, List<EpgChannelWrapper>>()
    // メモリ番組表の収録時間範囲。短いホーム取得で7日分の検索母集団を上書きしない。
    private val coverage = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun invalidateTifCache() {
        synchronized(coverage) {
            memoryCache.keys.filter { it.startsWith("TIF_") }.forEach {
                memoryCache.remove(it)
                coverage.remove(it)
            }
        }
    }

    private fun cacheEpg(
        cacheKey: String, backend: String, start: Long, end: Long,
        channels: List<EpgChannelWrapper>
    ) {
        synchronized(coverage) {
            val old = coverage[cacheKey]
            if (backend != TifInput.BACKEND_TYPE || old == null) {
                memoryCache[cacheKey] = channels
                coverage[cacheKey] = start to end
            } else if (start <= old.first && end >= old.second) {
                // 新しい取得が既存範囲を完全に覆う場合は置換する。
                memoryCache[cacheKey] = channels
                coverage[cacheKey] = start to end
            } else if (end > old.second || start < old.first) {
                // 日付を跨いで未来側が伸びた場合は新しい番組を追加し、
                // ホームの短期取得で検索用の既存7日分を失わないようにする。
                val existing = memoryCache[cacheKey].orEmpty().associateBy { it.channel.id }
                val latest = channels.associateBy { it.channel.id }
                memoryCache[cacheKey] = (existing.keys + latest.keys).mapNotNull { id ->
                    val oldRow = existing[id]
                    val newRow = latest[id]
                    when {
                        oldRow == null -> newRow
                        newRow == null -> oldRow
                        else -> newRow.copy(
                            programs = (oldRow.programs + newRow.programs)
                                .distinctBy { it.id }.sortedBy { it.start_time }
                        )
                    }
                }
                coverage[cacheKey] = minOf(start, old.first) to maxOf(end, old.second)
            }
            // 要求期間が既存範囲内なら短い結果による縮小を行わない。
        }
    }

    // ==========================================
    // ★ 高速ファイルキャッシュ処理
    // ==========================================

    @OptIn(UnstableApi::class)
    private suspend fun saveToFileCache(cacheKey: String, channels: List<EpgChannelWrapper>) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = java.io.File(context.cacheDir, "epg_cache_${cacheKey}.json.gz")
            // 直接GZIP圧縮しながらファイルへ書き込む（Base64のオーバーヘッドを削減）
            java.io.FileOutputStream(cacheFile).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    val jsonString = gson.toJson(channels)
                    gzip.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            }

            // DBにはメタデータと「FILE_BASED」という文字列だけを保存して2MB制限を回避
            epgCacheDao.insertOrUpdate(
                EpgCacheEntity(
                    channelType = cacheKey,
                    dataJson = "FILE_BASED",
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e("EPG", "File Cache Save Error for $cacheKey", e)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun readFromFileCache(cacheKey: String): List<EpgChannelWrapper>? = withContext(Dispatchers.IO) {
        try {
            val entity = try {
                epgCacheDao.getCache(cacheKey)
            } catch (e: android.database.sqlite.SQLiteException) {
                // 古い巨大なBLOBがDBに残っていてクラッシュした場合、握りつぶして新規取得させる
                Log.w("EPG", "Huge legacy DB blob found for $cacheKey. Ignoring to force refresh.", e)
                return@withContext null
            }

            if (entity == null) return@withContext null

            val cacheFile = java.io.File(context.cacheDir, "epg_cache_${cacheKey}.json.gz")
            if (!cacheFile.exists()) return@withContext null

            // ファイルから解凍しながら直接文字列として読み込む
            val jsonString = java.io.FileInputStream(cacheFile).use { fis ->
                GZIPInputStream(fis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val listType = object : TypeToken<List<EpgChannelWrapper>>() {}.type
            return@withContext gson.fromJson(jsonString, listType)

        } catch (e: Exception) {
            Log.e("EPG", "File Cache Read Error for $cacheKey", e)
            return@withContext null
        }
    }

    // ==========================================

    private fun normalizeForSearch(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return normalized.lowercase()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun searchFuturePrograms(
        query: String = "",
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<EpgSearchResultItem> {

        val isOrSearch = query.contains(",") || query.contains("、")
        val delimiters = if (isOrSearch) Regex("[,、]+") else Regex("[\\s]+")
        val keywords =
            query.split(delimiters).map { normalizeForSearch(it.trim()) }.filter { it.isNotBlank() }

        if (keywords.isEmpty() && genre.isNullOrBlank() && dateStr.isNullOrBlank() && channelName.isNullOrBlank() && !isLiveOnly) {
            return emptyList()
        }

        val results = mutableListOf<EpgSearchResultItem>()
        val nowMs = System.currentTimeMillis()

        val targetTvDate = try {
            dateStr?.takeIf { it.isNotBlank() }?.let {
                java.time.LocalDate.parse(it.replace("/", "-"))
            }
        } catch (e: Exception) {
            null
        }

        // backend別に保持したcacheを、検索時も選択中backendだけに限定する。
        // 全cache.valuesを検索すると切替前のEPGが結果に混入する。
        val selectedBackend = settingsRepository.backendType.first()
        val visibleTifIds = if (selectedBackend == TifInput.BACKEND_TYPE)
            tifRepository.visibleChannelIds() else null
        memoryCache.entries.asSequence()
            .filter { (key, _) -> key.startsWith(selectedBackend + "_") }
            .flatMap { (_, channels) -> channels.asSequence() }
            .filter { wrapper -> visibleTifIds == null || wrapper.channel.id in visibleTifIds }
            .forEach { wrapper ->
            if (!channelName.isNullOrBlank() && !wrapper.channel.name.contains(
                    channelName,
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            wrapper.programs.forEach { prog ->
                try {
                    val startTimeMs =
                        OffsetDateTime.parse(prog.start_time).toInstant().toEpochMilli()
                    if (startTimeMs > nowMs) {

                        if (targetTvDate != null) {
                            val startDt = OffsetDateTime.parse(prog.start_time)
                            val base = startDt.withHour(4).withMinute(0).withSecond(0).withNano(0)
                            val tvDate = if (startDt.hour < 4) base.minusDays(1)
                                .toLocalDate() else base.toLocalDate()
                            if (tvDate != targetTvDate) return@forEach
                        }

                        if (!genre.isNullOrBlank()) {
                            val matchGenre = (prog.genres?.any {
                                it.major.contains(genre) || it.middle.contains(genre)
                            } == true) || prog.title.contains(genre)
                            if (!matchGenre) return@forEach
                        }

                        if (isLiveOnly) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            val title = prog.title ?: ""
                            val desc = prog.description ?: ""
                            val matchLive =
                                title.contains("[生]") || title.contains("【生】") || title.contains("生中継") || title.contains(
                                    "生放送"
                                ) || title.contains("LIVE", ignoreCase = true) ||
                                        desc.contains("生中継") || desc.contains("生放送") || detailText.contains(
                                    "生中継"
                                ) || detailText.contains("生放送")
                            if (!matchLive) return@forEach
                        }

                        if (keywords.isNotEmpty()) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            val combinedDesc =
                                "${wrapper.channel.name} ${prog.title} ${prog.description} $detailText"
                            val normalizedDesc = normalizeForSearch(combinedDesc)

                            val isMatch = if (isOrSearch) {
                                keywords.any { k -> normalizedDesc.contains(k) }
                            } else {
                                keywords.all { k -> normalizedDesc.contains(k) }
                            }
                            if (!isMatch) return@forEach
                        }

                        results.add(EpgSearchResultItem(prog, wrapper.channel))
                    }
                } catch (e: Exception) { /* ignore */
                }
            }
        }

        return results.sortedBy {
            try {
                OffsetDateTime.parse(it.program.start_time).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchAndCacheEpgDataSilently(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ) {
        val requestBackend = settingsRepository.backendType.first()
        val cacheKey = requestBackend + "_" + channelType
        if (requestBackend == TifInput.BACKEND_TYPE) {
            val old = coverage[cacheKey]
            if (old != null && old.first <= startTime.toInstant().toEpochMilli() &&
                old.second >= endTime.toInstant().toEpochMilli()) return
        } else if (memoryCache.containsKey(cacheKey)) return

        var isFreshCacheAvailable = false

        // ★修正: DBではなくファイルから読み込む
        val cachedData = if (requestBackend == TifInput.BACKEND_TYPE) null else readFromFileCache(cacheKey)
        if (cachedData != null) {
            if (settingsRepository.backendType.first() != requestBackend) return
            memoryCache[cacheKey] = cachedData

            val oneDayLater = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
            val isFresh = cachedData.flatMap { it.programs }.any {
                try {
                    OffsetDateTime.parse(it.start_time).toInstant().toEpochMilli() > oneDayLater
                } catch (e: Exception) {
                    false
                }
            }

            if (isFresh) {
                isFreshCacheAvailable = true
            }
        }

        if (isFreshCacheAvailable) return

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgProgramsForBackend(
                backend = requestBackend,
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            if (settingsRepository.backendType.first() != requestBackend) return
            // 7日分キャッシュをホームの3日取得で縮めない。
            cacheEpg(cacheKey, requestBackend, startTime.toInstant().toEpochMilli(),
                endTime.toInstant().toEpochMilli(), channels)
            if (requestBackend != TifInput.BACKEND_TYPE) saveToFileCache(cacheKey, channels)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Silent Fetch Error for $channelType", e)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun getEpgDataStream(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ): Flow<Result<List<EpgChannelWrapper>>> = flow {
        val requestBackend = settingsRepository.backendType.first()
        val cacheKey = requestBackend + "_" + channelType
        if (settingsRepository.backendType.first() != requestBackend) return@flow
        // TIF番組は取得時の可視性を確認した結果のみ表示する。旧メモリは先出ししない。
        var emittedSuccess = false
        if (requestBackend != TifInput.BACKEND_TYPE)
            memoryCache[cacheKey]?.let {
                emit(Result.success(it))
                emittedSuccess = true
            }

        if (memoryCache[cacheKey] == null) {
            // ★修正: DBではなくファイルから読み込む
            val cachedData = if (requestBackend == TifInput.BACKEND_TYPE)
                null else readFromFileCache(cacheKey)
            if (cachedData != null) {
                if (settingsRepository.backendType.first() != requestBackend) return@flow
                memoryCache[cacheKey] = cachedData
                emit(Result.success(cachedData))
                emittedSuccess = true
            }
        }

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgProgramsForBackend(
                backend = requestBackend,
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            if (settingsRepository.backendType.first() != requestBackend) return@flow
            // TIFアダプターが公開・所有者条件を確認した取得結果を正本とする。
            val published = channels
            if (settingsRepository.backendType.first() != requestBackend) return@flow
            cacheEpg(cacheKey, requestBackend, startTime.toInstant().toEpochMilli(),
                endTime.toInstant().toEpochMilli(), published)
            emit(Result.success(published))

            if (requestBackend != TifInput.BACKEND_TYPE) CoroutineScope(Dispatchers.IO).launch {
                saveToFileCache(cacheKey, published)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            // TIFは旧メモリキャッシュを成功送信しない。既存値の有無と関係なく失敗を通知する。
            if (!emittedSuccess) emit(Result.failure(e))
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgData(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String? = null
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            Result.success(channels)
        } catch (e: Exception) {
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            Result.failure(e)
        }
    }

    suspend fun fetchPinnedChannels(pinnedIds: List<String>): Result<List<EpgChannelWrapper>> {
        return try {
            val channels = epgProvider.getPinnedEpgPrograms(pinnedIds.joinToString(","))
            Result.success(channels)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}