package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.time.OffsetDateTime
import javax.inject.Inject

data class UiSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

private const val PREF_NAME_EPG_SEARCH = "epg_search_history_pref"
private const val KEY_EPG_HISTORY = "history_list"

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val repository: EpgRepository,
    private val liveProvider: LiveProvider,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType: StateFlow<String> = _selectedBroadcastingType.asStateFlow()

    private var mirakurunIp = ""
    private var mirakurunPort = ""

    private var hasInitialFetched = false
    private var observedBackend: String? = null
    // Main/Default workerを跨ぐ非同期検索・スライスの旧backend結果の公開を禁止する。
    @Volatile private var backendGeneration: Long = 0L
    private var searchGeneration: Long = 0L
    private var searchJob: Job? = null
    private var epgJob: Job? = null
    private var preloadJob: Job? = null

    // ★ 追加: DB検索待ちをゼロにするためのメモリキャッシュ群
    private val epgMemoryCache = mutableMapOf<String, List<EpgChannelWrapper>>()
    private val logoMemoryCache = mutableMapOf<String, List<String>>()

    private var fullEpgData: List<EpgChannelWrapper> = emptyList()
    private var fullLogoUrls: List<String> = emptyList()
    private var currentTargetTime: OffsetDateTime = OffsetDateTime.now()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UiSearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<UiSearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    var lastFocusedChannelId: String? = null
    var lastFocusedTime: OffsetDateTime? = null
    var epgRestoreTrigger by androidx.compose.runtime.mutableStateOf(0L)
        private set

    fun saveEpgFocus(channelId: String, time: OffsetDateTime) {
        lastFocusedChannelId = channelId
        lastFocusedTime = time
    }

    fun triggerRestore() {
        epgRestoreTrigger = System.currentTimeMillis()
    }

    fun clearEpgFocus() {
        lastFocusedChannelId = null
        lastFocusedTime = null
    }

    init {
        loadSearchHistory()
        loadInitialData()

        viewModelScope.launch {
            com.beeregg2001.komorebi.data.repository.edcb.EdcbEpgCacheManager.epgBackgroundUpdateEvent.collect {
                Log.i(
                    "EpgViewModel",
                    "Background EPG fetch completed! Refreshing ViewModel cache..."
                )
                refreshEpgData()
            }
        }
    }

    private fun loadSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonString = prefs.getString(KEY_EPG_HISTORY, "[]")
                val jsonArray = JSONArray(jsonString)
                val list = ArrayList<String>()
                for (i in 0 until jsonArray.length()) list.add(jsonArray.getString(i))
                _searchHistory.value = list
            } catch (e: Exception) {
                _searchHistory.value = emptyList()
            }
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query)
        currentList.add(0, query)
        if (currentList.size > 5) currentList.removeAt(currentList.lastIndex)
        _searchHistory.value = currentList
        saveSearchHistory(currentList)
    }

    fun removeSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        if (currentList.remove(query)) {
            _searchHistory.value = currentList
            saveSearchHistory(currentList)
        }
    }

    private fun saveSearchHistory(list: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_EPG_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(UnstableApi::class)
    fun executeSearch(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ) {
        searchJob?.cancel()
        val generation = backendGeneration
        val request = ++searchGeneration
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            val displayQuery = listOfNotNull(
                keyword.takeIf { it.isNotBlank() },
                channelName?.takeIf { it.isNotBlank() },
                genre?.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            _searchQuery.value = displayQuery
            _activeSearchQuery.value = displayQuery
            if (displayQuery.isNotBlank()) addSearchHistory(displayQuery)

            try {
                val rawResults = withContext(Dispatchers.Default) {
                    repository.searchFuturePrograms(
                        keyword,
                        genre,
                        dateStr,
                        isLiveOnly,
                        channelName
                    )
                }

                val topMatches = rawResults.sortedBy {
                    try {
                        OffsetDateTime.parse(it.program.start_time)
                    } catch (e: Exception) {
                        OffsetDateTime.MAX
                    }
                }.take(100)

                val results = withContext(Dispatchers.IO) {
                    topMatches.map { item ->
                        async {
                            UiSearchResultItem(
                                program = item.program,
                                channel = item.channel,
                                logoUrl = getLogoUrl(item.channel)
                            )
                        }
                    }.awaitAll()
                }

                if (generation == backendGeneration && request == searchGeneration) {
                    _searchResults.value = results
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EpgViewModel", "Search Error", e)
                if (generation == backendGeneration && request == searchGeneration) {
                    _searchResults.value = emptyList()
                }
            } finally {
                if (generation == backendGeneration && request == searchGeneration) {
                    _isSearching.value = false
                }
            }
        }
    }

    suspend fun searchSilently(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<UiSearchResultItem> {
        val generation = backendGeneration
        return try {
            val rawResults = withContext(Dispatchers.Default) {
                repository.searchFuturePrograms(keyword, genre, dateStr, isLiveOnly, channelName)
            }

            val topMatches = rawResults.sortedBy {
                try {
                    OffsetDateTime.parse(it.program.start_time)
                } catch (e: Exception) {
                    OffsetDateTime.MAX
                }
            }.take(100)

            val results = withContext(Dispatchers.IO) {
                topMatches.map { item ->
                    async {
                        UiSearchResultItem(
                            program = item.program,
                            channel = item.channel,
                            logoUrl = getLogoUrl(item.channel)
                        )
                    }
                }.awaitAll()
            }
            if (generation == backendGeneration) results else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchGeneration++
        _isSearching.value = false
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    // INPUTには番組表がなく、TIFはSKY/BS4Kを独立したカテゴリに分類しない。
    private fun preloadTypesFor(backend: String): List<String> =
        if (backend == "TIF") listOf("GR", "BS", "CS", "TV")
        else listOf("GR", "BS", "CS", "SKY", "BS4K")

    /** 呼出元のJobへ従属させる。delay後の実取得もcancelが伝播する。 */
    private suspend fun preloadEpgDataForSearchNow(availableTypes: List<String>) {
        val now = OffsetDateTime.now()
        val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
        val end = now.plusDays(7)
        coroutineScope {
            availableTypes.map { type ->
                async(Dispatchers.IO) {
                    // Repository自身がキャッシュ／権限を判断する。入力一覧の二重列挙は不要。
                    repository.fetchAndCacheEpgDataSilently(start, end, type)
                }
            }.awaitAll()
        }
    }

    fun preloadEpgDataForSearch(availableTypes: List<String>) {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            val backend = settingsRepository.backendType.first()
            preloadEpgDataForSearchNow(availableTypes.filter { it in preloadTypesFor(backend) })
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.isInitialized,
                settingsRepository.backendType,
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                _selectedBroadcastingType
            ) { isInit, backend, mIp, mPort, type ->
                if (observedBackend != backend) {
                    backendGeneration++
                    epgJob?.cancel()
                    preloadJob?.cancel()
                    sliceJob?.cancel()
                    clearSearch()
                    epgMemoryCache.clear()
                    logoMemoryCache.clear()
                    fullEpgData = emptyList()
                    fullLogoUrls = emptyList()
                    uiState = EpgUiState.Loading
                    _isInitialLoadComplete.value = false
                    _isPreloading.value = true
                    hasInitialFetched = false
                    observedBackend = backend
                    // 既存のBS等は維持し、切替先の対象外（TIF専用TV等）だけ修正する。
                    val available = preloadTypesFor(backend)
                    if (type !in available) _selectedBroadcastingType.value = available.first()
                }
                mirakurunIp = mIp
                mirakurunPort = mPort

                if (isInit && !hasInitialFetched) {
                    hasInitialFetched = true
                    viewModelScope.launch { refreshEpgData(_selectedBroadcastingType.value) }

                    preloadJob = viewModelScope.launch {
                        delay(10000)
                        // 旧backendの遅延ジョブは切替時にcancelし、子の並列取得も一括停止。
                        preloadEpgDataForSearchNow(preloadTypesFor(backend))
                    }

                } else if (isInit && hasInitialFetched) {
                    refreshEpgData(_selectedBroadcastingType.value)
                }
            }.collectLatest { }
        }
    }

    fun preloadAllEpgData() {
        refreshEpgData()
    }

    /** TV入力スキャン／削除後は描画済み番組を使い回さず、公開チャンネルを再確認する。 */
    fun refreshAfterTifInputChange() {
        epgJob?.cancel()
        sliceJob?.cancel()
        searchJob?.cancel()
        searchGeneration++
        _searchResults.value = emptyList()
        epgMemoryCache.clear()
        logoMemoryCache.clear()
        fullEpgData = emptyList()
        fullLogoUrls = emptyList()
        uiState = EpgUiState.Loading
        refreshEpgData()
    }

    fun refreshEpgData(channelType: String? = null) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val typeToFetch = channelType ?: _selectedBroadcastingType.value
            if (settingsRepository.backendType.first() == TifInput.BACKEND_TYPE) {
                val tunerPresent = TifInput.inputs(context).any { !it.isPassthroughInput }
                val permissionDenied = context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED
                if (!tunerPresent || permissionDenied) {
                    // HDMIのみならEPGは空。チューナーがある場合だけ権限案内を出す。
                    sliceJob?.cancel()
                    epgMemoryCache.clear()
                    logoMemoryCache.clear()
                    fullEpgData = emptyList()
                    fullLogoUrls = emptyList()
                    uiState = if (!tunerPresent) EpgUiState.Success(
                        data = emptyList(), logoUrls = emptyList(),
                        mirakurunIp = mirakurunIp, mirakurunPort = mirakurunPort,
                        targetTime = currentTargetTime
                    ) else EpgUiState.Error(
                        "TV番組一覧の権限がありません。Androidのアプリ設定でREAD_TV_LISTINGSを許可してください。"
                    )
                    _isInitialLoadComplete.value = true
                    _isPreloading.value = false
                    return@launch
                }
            }
            val now = OffsetDateTime.now()
            val start = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
            val end = now.plusDays(7)

            // ★ 修正: メモリキャッシュがあれば即座にUIへ反映（Loadingスピナーすら出さない）
            if (epgMemoryCache.containsKey(typeToFetch)) {
                fullEpgData = epgMemoryCache[typeToFetch]!!
                fullLogoUrls = logoMemoryCache[typeToFetch] ?: emptyList()
                sliceAndEmitEpgData()
            } else {
                if (uiState !is EpgUiState.Success) {
                    uiState = EpgUiState.Loading
                }
            }

            // キャッシュ表示後も、裏側でRoomから最新情報を取得してキャッシュを更新する
            repository.getEpgDataStream(start, end, typeToFetch).collect { result ->
                result.onSuccess { data ->
                    fullEpgData = data
                    epgMemoryCache[typeToFetch] = data
                    // ★ 時刻解析メモが無制限に膨らまないよう、元データ更新時に破棄する
                    if (timeParseCache.size > 100_000) timeParseCache.clear()

                    fullLogoUrls =
                        withContext(Dispatchers.Default) { data.map { getLogoUrl(it.channel) } }
                    logoMemoryCache[typeToFetch] = fullLogoUrls

                    sliceAndEmitEpgData()

                    _isInitialLoadComplete.value = true
                    _isPreloading.value = false
                }.onFailure { e ->
                    if (uiState !is EpgUiState.Success) {
                        uiState = EpgUiState.Error(e.message ?: "Unknown Error")
                        _isInitialLoadComplete.value = true
                    }
                }
            }
        }
    }

    fun updateTargetTime(time: OffsetDateTime) {
        currentTargetTime = time
        sliceAndEmitEpgData()
    }

    private fun getTvDayStart(time: OffsetDateTime): OffsetDateTime {
        val base = time.withHour(4).withMinute(0).withSecond(0).withNano(0)
        return if (time.hour < 4) base.minusDays(1) else base
    }

    /**
     * ★ 最適化: ISO8601 文字列 -> エポックミリ秒 の解析結果メモ。
     *
     * [sliceAndEmitEpgData] は日付移動のたびに fullEpgData 全件を走査し、
     * 1 番組につき start_time / end_time を 2 回 OffsetDateTime.parse していた。
     * 100 チャンネル × 200 番組なら 1 回の日付移動で 4 万回の parse になり、
     * 番組表の日付切り替えが目に見えて待たされる原因になっていた。
     *
     * 日付移動では fullEpgData 自体は変わらないため、同じ文字列を何度も解析している。
     * メモ化により 2 回目以降の日付移動は数値比較だけで済む。
     */
    private val timeParseCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun parseEpochMillis(text: String?): Long {
        if (text.isNullOrEmpty()) return Long.MIN_VALUE
        return timeParseCache.getOrPut(text) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (e: Exception) {
                Long.MIN_VALUE
            }
        }
    }

    /** ★ 追加: 連続した日付移動で古いスライス処理が走り続けないようキャンセルするためのジョブ */
    private var sliceJob: Job? = null

    private fun sliceAndEmitEpgData() {
        sliceJob?.cancel()
        if (fullEpgData.isEmpty()) {
            // EPGを持たないTIF（HDMIのみ等）も正常完了。Loadingのままにしない。
            uiState = EpgUiState.Success(
                data = emptyList(),
                logoUrls = emptyList(),
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                targetTime = currentTargetTime
            )
            return
        }
        val generation = backendGeneration
        val sourceData = fullEpgData
        val sourceLogos = fullLogoUrls
        val sourceTargetTime = currentTargetTime
        val sourceMirakurunIp = mirakurunIp
        val sourceMirakurunPort = mirakurunPort
        // ★ 最適化 + 競合の修正:
        //   従来は呼ばれるたびに新しいコルーチンを起動しっぱなしだったため、
        //   日付を素早く連打すると同じデータに対する重いスライス処理が多重に走り、
        //   さらに完了順が前後すると古い結果が新しい結果を上書きする可能性があった。
        sliceJob = viewModelScope.launch(Dispatchers.Default) {

            val tvDayStart = getTvDayStart(sourceTargetTime)
            val tvDayEnd = tvDayStart.plusHours(24)
            val tvDayStartMs = tvDayStart.toInstant().toEpochMilli()
            val tvDayEndMs = tvDayEnd.toInstant().toEpochMilli()

            val slicedData = sourceData.map { wrapper ->
                val filteredPrograms = wrapper.programs.filter { prog ->
                    val startMs = parseEpochMillis(prog.start_time)
                    val endMs = parseEpochMillis(prog.end_time)
                    // 解析に失敗した番組は従来どおり除外する
                    startMs != Long.MIN_VALUE && endMs != Long.MIN_VALUE &&
                        endMs > tvDayStartMs && startMs < tvDayEndMs
                }
                wrapper.copy(programs = filteredPrograms)
            }

            // CPU上のmap/filterはcancel()だけでは即停止しない。
            // 計算完了後にgenerationとactiveを確認し、Mainでのみ描画状態を公開する。
            ensureActive()
            withContext(Dispatchers.Main.immediate) {
                if (generation != backendGeneration || !isActive) return@withContext
                uiState = EpgUiState.Success(
                    data = slicedData,
                    logoUrls = sourceLogos,
                    mirakurunIp = sourceMirakurunIp,
                    mirakurunPort = sourceMirakurunPort,
                    targetTime = sourceTargetTime
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun getLogoUrl(channel: EpgChannel): String {
        return liveProvider.getChannelLogoUrl(channel.display_channel_id)
    }

    fun updateBroadcastingType(type: String) {
        if (_selectedBroadcastingType.value != type) {
            _selectedBroadcastingType.value = type
        }
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()

    data class Success(
        val data: List<EpgChannelWrapper>,
        val logoUrls: List<String>,
        val mirakurunIp: String,
        val mirakurunPort: String,
        val targetTime: OffsetDateTime
    ) : EpgUiState()

    data class Error(val message: String) : EpgUiState()
}