package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import com.beeregg2001.komorebi.data.repository.tif.TifInput
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
    private val settingsRepository: SettingsRepository
) {
    private val memoryCache = ConcurrentHashMap<String, List<EpgChannelWrapper>>()

    // 権限を失ったTIFのキャッシュを番組表や検索の成功結果として公開しない。
    private fun unavailableTifListings(backend: String): Boolean =
        backend == TifInput.BACKEND_TYPE &&
            context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) != PackageManager.PERMISSION_GRANTED

    // backend間でEPGファイル/Room/メモリキャッシュを共用しない。
    private suspend fun scopedCacheKey(type: String) = settingsRepository.backendType.first() + "_" + type

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
        if (unavailableTifListings(selectedBackend)) return emptyList()
        memoryCache.entries.asSequence()
            .filter { (key, _) -> key.startsWith(selectedBackend + "_") }
            .flatMap { (_, channels) -> channels.asSequence() }
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

    suspend fun hasCacheForType(channelType: String): Boolean {
        val backend = settingsRepository.backendType.first()
        if (unavailableTifListings(backend)) return false
        return memoryCache.containsKey(backend + "_" + channelType)
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
        if (unavailableTifListings(requestBackend)) {
            memoryCache.remove(cacheKey)
            return
        }
        if (memoryCache.containsKey(cacheKey)) return

        var isFreshCacheAvailable = false

        // ★修正: DBではなくファイルから読み込む
        val cachedData = readFromFileCache(cacheKey)
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
            memoryCache[cacheKey] = channels

            // ★修正: ファイルへ保存する
            saveToFileCache(cacheKey, channels)

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
        if (unavailableTifListings(requestBackend)) {
            memoryCache.remove(cacheKey)
            emit(Result.failure(SecurityException(
                "TV番組表を表示するにはREAD_TV_LISTINGS権限が必要です。Androidのアプリ設定で許可してください。"
            )))
            return@flow
        }
        if (settingsRepository.backendType.first() != requestBackend) return@flow
        memoryCache[cacheKey]?.let { emit(Result.success(it)) }

        if (memoryCache[cacheKey] == null) {
            // ★修正: DBではなくファイルから読み込む
            val cachedData = readFromFileCache(cacheKey)
            if (cachedData != null) {
                if (settingsRepository.backendType.first() != requestBackend) return@flow
                memoryCache[cacheKey] = cachedData
                emit(Result.success(cachedData))
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
            memoryCache[cacheKey] = channels
            emit(Result.success(channels))

            CoroutineScope(Dispatchers.IO).launch {
                // ★修正: ファイルへ保存する
                saveToFileCache(cacheKey, channels)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            if (memoryCache[cacheKey] == null) emit(Result.failure(e))
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