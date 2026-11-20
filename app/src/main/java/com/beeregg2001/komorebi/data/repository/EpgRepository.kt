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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private val coverage = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val tifCacheRevision = java.util.concurrent.atomic.AtomicLong(0L)
    private val tifRequestSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val appliedTifRequests = mutableMapOf<String, Long>()
    // 同じ取得元・種別・期間・入力世代の問い合わせは共有する。
    // 呼出元の画面がキャンセルされても、検索用の取得は失われない。
    private val tifFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tifInFlight = ConcurrentHashMap<String, Deferred<TifRepository.EpgSnapshot>>()

    fun invalidateTifCache() {
        synchronized(coverage) {
            tifCacheRevision.incrementAndGet()
            memoryCache.keys.filter { it.startsWith("TIF_") }.forEach {
                memoryCache.remove(it)
                coverage.remove(it)
                appliedTifRequests.remove(it)
            }
        }
    }

    private suspend fun fetchTifSnapshot(
        startTime: String, endTime: String, channelType: String, revision: Long
    ): TifRepository.EpgSnapshot {
        val key = "$revision|$channelType|$startTime|$endTime"
        val candidate = tifFetchScope.async(start = CoroutineStart.LAZY) {
            tifRepository.getEpgSnapshot(startTime, endTime, channelType)
        }
        val existing = tifInFlight.putIfAbsent(key, candidate)
        val task = if (existing != null) {
            candidate.cancel()
            existing
        } else {
            candidate.also {
                it.invokeOnCompletion { tifInFlight.remove(key, it) }
                it.start()
            }
        }
        return task.await()
    }

    /**
     * 取得順序と入力世代を確認し、旧スナップショットによる逆戻りを防ぐ。
     * 番組の走査・日時解析・結合はDefaultで実行し、短い公開操作だけをロックする。
     * 失敗した局は旧番組を保持し、失敗した取得期間を被覆済みと記録しない。
     */
    private suspend fun cacheEpg(
        cacheKey: String, backend: String, start: Long, end: Long,
        channels: List<EpgChannelWrapper>, expectedRevision: Long,
        requestSequence: Long = 0L, failedIds: Set<String> = emptySet()
    ): List<EpgChannelWrapper>? = withContext(Dispatchers.Default) {
        if (backend != TifInput.BACKEND_TYPE) {
            synchronized(coverage) {
                memoryCache[cacheKey] = channels
                coverage[cacheKey] = start to end
            }
            return@withContext channels
        }

        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        if (end <= cutoff) return@withContext channels

        fun endMillis(value: String): Long =
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrDefault(Long.MIN_VALUE)
        fun startMillis(value: String): Long =
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrDefault(Long.MIN_VALUE)

        while (true) {
            val snapshot = synchronized(coverage) {
                if (tifCacheRevision.get() != expectedRevision ||
                    (appliedTifRequests[cacheKey] ?: 0L) > requestSequence) {
                    null
                } else {
                    Pair(coverage[cacheKey], memoryCache[cacheKey])
                }
            } ?: return@withContext null
            val old = snapshot.first
            val previousChannels = snapshot.second
            val disjoint = old == null || end < old.first || start > old.second
            val oldById = previousChannels.orEmpty().associateBy { it.channel.id }

            val merged = channels.map { fresh ->
                val previous = oldById[fresh.channel.id]
                if (fresh.channel.id in failedIds && previous != null) {
                    previous
                } else {
                    val untouched = (if (disjoint) emptyList() else previous?.programs.orEmpty())
                        .filter { program ->
                        val programStart = startMillis(program.start_time)
                        val programEnd = endMillis(program.end_time)
                        programEnd > cutoff && (programEnd <= start || programStart >= end)
                    }
                    fresh.copy(programs = (fresh.programs + untouched)
                        .distinctBy { it.id }
                        .filter { endMillis(it.end_time) > cutoff }
                        .sortedBy { it.start_time })
                }
            }
            val nextCoverage = if (failedIds.isNotEmpty()) null else {
                val from = if (disjoint) start else minOf(old!!.first, start)
                val to = if (disjoint) end else maxOf(old!!.second, end)
                maxOf(from, cutoff) to to
            }
            val committed = synchronized(coverage) {
                when {
                    tifCacheRevision.get() != expectedRevision ||
                        (appliedTifRequests[cacheKey] ?: 0L) > requestSequence -> -1
                    memoryCache[cacheKey] !== previousChannels ||
                        coverage[cacheKey] != old -> 0
                    else -> {
                        memoryCache[cacheKey] = merged
                        if (nextCoverage == null) coverage.remove(cacheKey)
                        else coverage[cacheKey] = nextCoverage
                        appliedTifRequests[cacheKey] = requestSequence
                        1
                    }
                }
            }
            if (committed == 1) return@withContext merged
            if (committed == -1) return@withContext null
        }
        @Suppress("UNREACHABLE_CODE")
        null
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
        val requestRevision = tifCacheRevision.get()
        val requestSequence = tifRequestSequence.incrementAndGet()
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

            val snapshot = if (requestBackend == TifInput.BACKEND_TYPE)
                fetchTifSnapshot(startStr, endStr, channelType, requestRevision)
            else null
            val channels = snapshot?.channels ?: epgProvider.getEpgProgramsForBackend(
                backend = requestBackend,
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            if (settingsRepository.backendType.first() != requestBackend ||
                (requestBackend == TifInput.BACKEND_TYPE &&
                 requestRevision != tifCacheRevision.get())) return
            cacheEpg(cacheKey, requestBackend, startTime.toInstant().toEpochMilli(),
                endTime.toInstant().toEpochMilli(), channels, requestRevision,
                requestSequence, snapshot?.failedChannelIds.orEmpty())
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
        val requestRevision = tifCacheRevision.get()
        val requestSequence = tifRequestSequence.incrementAndGet()
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

            val snapshot = if (requestBackend == TifInput.BACKEND_TYPE)
                fetchTifSnapshot(startStr, endStr, channelType, requestRevision)
            else null
            val channels = snapshot?.channels ?: epgProvider.getEpgProgramsForBackend(
                backend = requestBackend,
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            if (settingsRepository.backendType.first() != requestBackend ||
                (requestBackend == TifInput.BACKEND_TYPE &&
                 requestRevision != tifCacheRevision.get())) return@flow
            val published = cacheEpg(cacheKey, requestBackend,
                startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli(),
                channels, requestRevision, requestSequence, snapshot?.failedChannelIds.orEmpty())
                ?: return@flow
            if (settingsRepository.backendType.first() != requestBackend ||
                (requestBackend == TifInput.BACKEND_TYPE &&
                 requestRevision != tifCacheRevision.get())) return@flow
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