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
    // TIFの取得済み範囲は放送種別一括ではなく、局ごとに保持する。
    // 重複取得の新旧判定も区間単位とし、狭い新取得で広い旧取得を捨てない。
    private data class TifCoverageWindow(val from: Long, val to: Long, val sequence: Long)
    private val tifChannelCoverage = mutableMapOf<String, Map<String, List<TifCoverageWindow>>>()
    private data class TifRetryWindow(val from: Long, val to: Long, val sequence: Long)
    private val tifRetryRanges = mutableMapOf<String, Map<String, List<TifRetryWindow>>>()
    // 放送種別の公開局一覧も実際の照会開始順で更新する。
    private val tifCatalogSequence = mutableMapOf<String, Long>()
    // 入力更新直後の表示・先読みが、公開直後の同一結果を再照会しないための短い共有窓。
    private val tifPublishedAt = mutableMapOf<String, Long>()

    private fun subtractWindows(
        from: Long, to: Long, blocked: List<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {
        if (from >= to) return emptyList()
        var pieces = listOf(from to to)
        blocked.forEach { (blockFrom, blockTo) ->
            pieces = pieces.flatMap { (left, right) ->
                if (blockTo <= left || blockFrom >= right) listOf(left to right)
                else buildList {
                    if (left < blockFrom) add(left to minOf(right, blockFrom))
                    if (blockTo < right) add(maxOf(left, blockTo) to right)
                }
            }
        }
        return pieces
    }

    private fun normalizeRetryWindows(windows: List<TifRetryWindow>): List<TifRetryWindow> {
        val result = mutableListOf<TifRetryWindow>()
        windows.sortedWith(compareBy<TifRetryWindow> { it.from }.thenBy { it.to })
            .forEach { current ->
                val last = result.lastOrNull()
                if (last != null && last.sequence == current.sequence &&
                    last.to >= current.from) {
                    result[result.lastIndex] = last.copy(to = maxOf(last.to, current.to))
                } else result.add(current)
            }
        return result
    }

    private fun tifRangeCovered(key: String, from: Long, to: Long): Boolean =
        synchronized(coverage) {
            val rows = memoryCache[key] ?: return@synchronized false
            // 公開局0件も正常な取得結果。短時間の表示・先読み重複を避ける。
            if (rows.isEmpty()) {
                val emptyRange = coverage[key]
                return@synchronized emptyRange != null &&
                    emptyRange.first <= from && emptyRange.second >= to
            }
            val byId = tifChannelCoverage[key] ?: return@synchronized false
            val pending = tifRetryRanges[key].orEmpty()
            rows.all { row ->
                val id = row.channel.id
                subtractWindows(from, to, byId[id].orEmpty().map { it.from to it.to })
                    .isEmpty() &&
                    pending[id].orEmpty().none { it.from < to && it.to > from }
            }
        }
    // 同じ取得元・種別・期間・入力世代の問い合わせは共有する。
    // 呼出元の画面がキャンセルされても、検索用の取得は失われない。
    private val tifFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class TifInFlight(
        val revision: Long,
        val channelType: String,
        val start: Long,
        val end: Long,
        // 順序は呼出元ではなく、共有する実際のTvProvider照会に付与する。
        val sequence: Long,
        val task: Deferred<TifRepository.EpgSnapshot>,
        var waiters: Int = 0,
        var completedAt: Long? = null
    )
    private data class TifFetchResult(
        val snapshot: TifRepository.EpgSnapshot,
        val sequence: Long
    )
    private val tifFetchLock = Any()
    private val tifInFlight = mutableListOf<TifInFlight>()

    fun invalidateTifCache() {
        // 新しい旧世代照会が割り込めないよう、世代更新と共有照会の取り外しを一括で行う。
        val abandoned = synchronized(tifFetchLock) {
            synchronized(coverage) {
                tifCacheRevision.incrementAndGet()
                memoryCache.keys.filter { it.startsWith("TIF_") }.forEach {
                    memoryCache.remove(it)
                    coverage.remove(it)
                    tifChannelCoverage.remove(it)
                    tifRetryRanges.remove(it)
                    tifCatalogSequence.remove(it)
                    tifPublishedAt.remove(it)
                }
            }
            val tasks = tifInFlight.map { it.task }
            tifInFlight.clear()
            tasks
        }
        // 完了通知の再入を避け、ロック解放後に実際のJobを取り消す。
        abandoned.forEach { it.cancel() }
    }

    private suspend fun fetchTifSnapshot(
        startTime: String, endTime: String, channelType: String, revision: Long
    ): TifFetchResult {
        val from = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val to = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        // より広い取得が進行中なら、それを共有して要求区間へ切り詰める。
        // 狭い取得では広い要求を満たせないため、その逆方向の流用はしない。
        val entry = synchronized(tifFetchLock) {
            if (revision != tifCacheRevision.get()) {
                throw CancellationException("TIF入力世代が更新されました。")
            }
            val now = android.os.SystemClock.elapsedRealtime()
            tifInFlight.removeAll {
                it.waiters == 0 && it.completedAt?.let { at -> now - at > 1500 } == true
            }
            val matched = tifInFlight.firstOrNull {
                it.revision == revision && it.channelType == channelType &&
                    it.start <= from && it.end >= to
            } ?: run {
                val deferred = tifFetchScope.async(start = CoroutineStart.LAZY) {
                    tifRepository.getEpgSnapshot(startTime, endTime, channelType)
                }
                val next = TifInFlight(
                    revision, channelType, from, to,
                    tifRequestSequence.incrementAndGet(), deferred
                )
                tifInFlight.add(next)
                deferred.invokeOnCompletion { cause ->
                    synchronized(tifFetchLock) {
                        next.completedAt = android.os.SystemClock.elapsedRealtime()
                        if (cause != null) tifInFlight.remove(next)
                    }
                }
                deferred.start()
                next
            }
            matched.waiters++
            matched
        }
        try {
            val snapshot = entry.task.await()
            if (entry.start == from && entry.end == to)
                return TifFetchResult(snapshot, entry.sequence)
            val narrowed = withContext(Dispatchers.Default) {
            snapshot.channels.map { channel ->
                channel.copy(programs = channel.programs.filter { program ->
                    val programStart = runCatching {
                        OffsetDateTime.parse(program.start_time).toInstant().toEpochMilli()
                    }.getOrDefault(Long.MAX_VALUE)
                    val programEnd = runCatching {
                        OffsetDateTime.parse(program.end_time).toInstant().toEpochMilli()
                    }.getOrDefault(Long.MIN_VALUE)
                    programEnd > from && programStart < to
                })
            }
        }
            return TifFetchResult(snapshot.copy(channels = narrowed), entry.sequence)
        } finally {
            val abandoned = synchronized(tifFetchLock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.task.isCompleted) {
                    tifInFlight.remove(entry)
                    true
                } else false
            }
            if (abandoned) entry.task.cancel()
        }
    }

    /**
     * 成功した局・期間だけを置き換え、失敗局の旧番組と取得済み範囲を保持する。
     * 重複する区間は照会発行順で判定し、後から返った旧応答で巻き戻さない。
     * 不足する区間は古い広域照会から補完できる。
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

        fun time(value: String, invalid: Long): Long =
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrDefault(invalid)
        fun intersects(program: EpgProgram, ranges: List<Pair<Long, Long>>): Boolean {
            val from = time(program.start_time, Long.MAX_VALUE)
            val to = time(program.end_time, Long.MIN_VALUE)
            return ranges.any { (left, right) -> to > left && from < right }
        }

        while (true) {
            val original = synchronized(coverage) {
                if (tifCacheRevision.get() != expectedRevision) null
                else Triple(memoryCache[cacheKey], tifChannelCoverage[cacheKey],
                    tifRetryRanges[cacheKey])
            } ?: return@withContext null
            val previousChannels = original.first
            val oldCoverage = original.second.orEmpty()
            val oldRetry = original.third.orEmpty()
            val previousById = previousChannels.orEmpty().associateBy { it.channel.id }
            val updatedCoverage = mutableMapOf<String, List<TifCoverageWindow>>()
            val updatedRetry = mutableMapOf<String, List<Pair<Long, Long>>>()

            val merged = channels.map { fresh ->
                val id = fresh.channel.id
                val previous = previousById[id]
                val windows = oldCoverage[id].orEmpty().filter { it.to > cutoff }
                    .map { it.copy(from = maxOf(it.from, cutoff)) }
                val retry = oldRetry[id].orEmpty().filter { it.second > cutoff }
                val protected = windows.filter { it.sequence > requestSequence }
                    .map { it.from to it.to }
                val writable = if (id in failedIds) emptyList()
                    else subtractWindows(start, end, protected)

                val oldPrograms = previous?.programs.orEmpty().filter {
                    time(it.end_time, Long.MIN_VALUE) > cutoff
                }
                val oldKept = oldPrograms.filter {
                    !intersects(it, writable) || intersects(it, protected)
                }
                val freshKept = fresh.programs.filter {
                    time(it.end_time, Long.MIN_VALUE) > cutoff &&
                        intersects(it, writable) && !intersects(it, protected)
                }
                // 新しい取得の番組が優先。同じIDが保護区間にある場合は旧値を採る。
                val protectedIds = oldKept.filter { intersects(it, protected) }
                    .mapTo(mutableSetOf()) { it.id }
                val combined = (freshKept.filter { it.id !in protectedIds } + oldKept)
                    .distinctBy { it.id }.sortedBy { it.start_time }

                val remaining = windows.flatMap { window ->
                    subtractWindows(window.from, window.to, writable)
                        .map { TifCoverageWindow(it.first, it.second, window.sequence) }
                }
                val added = writable.map {
                    TifCoverageWindow(it.first, it.second, requestSequence)
                }
                updatedCoverage[id] = (remaining + added).sortedBy { it.from }
                    .fold(mutableListOf<TifCoverageWindow>()) { acc, window ->
                        val last = acc.lastOrNull()
                        if (last != null && last.sequence == window.sequence &&
                            last.to >= window.from) {
                            acc[acc.lastIndex] = last.copy(to = maxOf(last.to, window.to))
                        } else acc.add(window)
                        acc
                    }
                updatedRetry[id] = if (id in failedIds) {
                    retry + (start to end)
                } else retry.flatMap {
                    subtractWindows(it.first, it.second, listOf(start to end))
                }

                fresh.copy(programs = combined)
            }
            val accepted = synchronized(coverage) {
                when {
                    tifCacheRevision.get() != expectedRevision -> -1
                    memoryCache[cacheKey] !== previousChannels ||
                        tifChannelCoverage[cacheKey] !== original.second ||
                        tifRetryRanges[cacheKey] !== original.third -> 0
                    else -> {
                        memoryCache[cacheKey] = merged
                        tifChannelCoverage[cacheKey] = updatedCoverage
                        tifRetryRanges[cacheKey] = updatedRetry
                        tifPublishedAt[cacheKey] = android.os.SystemClock.elapsedRealtime()
                        1
                    }
                }
            }
            if (accepted == 1) {
                // 取得結果がキャッシュへ反映された段階で、完了済み共有照会を解放する。
                synchronized(tifFetchLock) {
                    tifInFlight.removeAll {
                        it.revision == expectedRevision && it.channelType == cacheKey.removePrefix("TIF_") &&
                            it.start == start && it.end == end && it.task.isCompleted
                    }
                }
                return@withContext merged
            }
            if (accepted == -1) return@withContext null
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
        if (requestBackend == TifInput.BACKEND_TYPE) {
            if (tifRangeCovered(cacheKey, startTime.toInstant().toEpochMilli(),
                    endTime.toInstant().toEpochMilli())) return
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

            val fetched = if (requestBackend == TifInput.BACKEND_TYPE)
                fetchTifSnapshot(startStr, endStr, channelType, requestRevision)
            else null
            val snapshot = fetched?.snapshot
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
                fetched?.sequence ?: 0L, snapshot?.failedChannelIds.orEmpty())
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

            if (requestBackend == TifInput.BACKEND_TYPE) {
                val recent = synchronized(coverage) {
                    val last = tifPublishedAt[cacheKey] ?: Long.MIN_VALUE
                    android.os.SystemClock.elapsedRealtime() - last in 0L..1500L &&
                        tifRangeCovered(cacheKey, startTime.toInstant().toEpochMilli(),
                            endTime.toInstant().toEpochMilli())
                }
                if (recent) {
                    val cached = memoryCache[cacheKey]
                    if (cached != null && settingsRepository.backendType.first() == requestBackend &&
                        requestRevision == tifCacheRevision.get()) {
                        emit(Result.success(cached))
                        return@flow
                    }
                }
            }
            val fetched = if (requestBackend == TifInput.BACKEND_TYPE)
                fetchTifSnapshot(startStr, endStr, channelType, requestRevision)
            else null
            val snapshot = fetched?.snapshot
            val channels = snapshot?.channels ?: epgProvider.getEpgProgramsForBackend(
                backend = requestBackend,
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            if (settingsRepository.backendType.first() != requestBackend ||
                (requestBackend == TifInput.BACKEND_TYPE &&
                 requestRevision != tifCacheRevision.get())) return@flow
            if (requestBackend == TifInput.BACKEND_TYPE && snapshot != null &&
                snapshot.channels.isNotEmpty() &&
                snapshot.failedChannelIds.size == snapshot.channels.size
            ) {
                val visibleIds = snapshot.channels.mapTo(mutableSetOf()) { it.channel.id }
                val rangeStart = startTime.toInstant().toEpochMilli()
                val rangeEnd = endTime.toInstant().toEpochMilli()
                val usableOldPrograms = memoryCache[cacheKey].orEmpty().any { row ->
                    row.channel.id in visibleIds && row.programs.any { program ->
                        val from = runCatching {
                            OffsetDateTime.parse(program.start_time).toInstant().toEpochMilli()
                        }.getOrDefault(Long.MAX_VALUE)
                        val to = runCatching {
                            OffsetDateTime.parse(program.end_time).toInstant().toEpochMilli()
                        }.getOrDefault(Long.MIN_VALUE)
                        to > rangeStart && from < rangeEnd
                    }
                }
                if (!usableOldPrograms) {
                    synchronized(tifFetchLock) {
                        tifInFlight.removeAll {
                            it.revision == requestRevision && it.channelType == channelType &&
                                it.start == rangeStart && it.end == rangeEnd && it.waiters == 0
                        }
                    }
                    throw IllegalStateException("TV番組表の全局照会に失敗しました。")
                }
            }
            val published = cacheEpg(cacheKey, requestBackend,
                startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli(),
                channels, requestRevision, fetched?.sequence ?: 0L, snapshot?.failedChannelIds.orEmpty())
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