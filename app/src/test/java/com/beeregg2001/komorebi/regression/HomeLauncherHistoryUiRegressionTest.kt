package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.beeregg2001.komorebi.ui.main.MainRootBackground
import com.beeregg2001.komorebi.ui.main.MainRootState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockedStatic
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Real HomeLauncherScreen history ownership routing, not VideoTabContent alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class HomeLauncherHistoryUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val verticalScrollMatcher = SemanticsMatcher("vertical LazyColumn") {
        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = store
    }
    private lateinit var home: HomeViewModel
    private lateinit var records: RecordViewModel
    private lateinit var live: ChannelViewModel
    private lateinit var epg: EpgViewModel
    private lateinit var reserves: ReserveViewModel
    private lateinit var settings: SettingsViewModel
    private lateinit var lastChannelFlow: MutableStateFlow<List<Channel>>
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var owned: MutableStateFlow<Pair<String?, List<KonomiHistoryProgram>>>
    private val resumed = AtomicReference<RecordedProgram?>()
    private val historySelection = AtomicReference<((KonomiHistoryProgram) -> Unit)?>(null)
    private val recordedCardSelection = AtomicReference<((RecordedProgram) -> Unit)?>(null)
    private val selectedProgramAfterClick = AtomicReference<RecordedProgram?>()
    private val verifiedSelection = AtomicReference<((RecordedProgram, Double) -> Unit)?>(null)
    private val liveSelection = AtomicReference<((Channel?, Boolean) -> Unit)?>(null)
    private val recordingSelection = AtomicReference<((RecordedProgram?) -> Unit)?>(null)
    private val epgNavigation = AtomicReference<((String, String, String) -> Unit)?>(null)
    private val staticMocks = mutableListOf<MockedStatic<Any>>()

    private fun interceptHistorySelection(compiledClass: String, method: String, index: Int) {
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val klass = Class.forName(compiledClass) as Class<Any>
            staticMocks += Mockito.mockStatic(klass, Answer { invocation ->
                if (invocation.method.name == method) {
                    @Suppress("UNCHECKED_CAST")
                    historySelection.set(invocation.arguments[index] as (KonomiHistoryProgram) -> Unit)
                    if (method == "VideoTabContent") {
                        recordedCardSelection.set(invocation.arguments[6] as (RecordedProgram) -> Unit)
                    }
                    null
                } else invocation.callRealMethod()
            })
        }
    }

    private fun interceptMainRootHistoryCallback() {
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val klass = Class.forName(
                "com.beeregg2001.komorebi.ui.home.HomeLauncherScreenKt"
            ) as Class<Any>
            staticMocks += Mockito.mockStatic(klass, Answer { invocation ->
                if (invocation.method.name == "HomeLauncherScreen") {
                    @Suppress("UNCHECKED_CAST")
                    verifiedSelection.set(invocation.arguments[17] as (RecordedProgram, Double) -> Unit)
                    liveSelection.set(invocation.arguments[11] as (Channel?, Boolean) -> Unit)
                    recordingSelection.set(invocation.arguments[16] as (RecordedProgram?) -> Unit)
                    epgNavigation.set(invocation.arguments[31] as (String, String, String) -> Unit)
                    null
                } else invocation.callRealMethod()
            })
        }
    }


    private fun history() = KonomiHistoryProgram(
        program = KonomiProgram(
            id = "42", title = "Owner verified test recording",
            description = "", detail = null,
            start_time = "2026-11-20T00:00:00+09:00",
            end_time = "2026-11-20T01:00:00+09:00",
            channel_id = "channel-42"
        ),
        playback_position = 12.0,
        last_watched_at = "2026-11-21T00:00:00+09:00"
    )

    @Before
    fun setup() {
        backend = MutableStateFlow("EDCB")
        owned = MutableStateFlow<Pair<String?, List<KonomiHistoryProgram>>>("EDCB" to listOf(history()))
        home = Mockito.mock(HomeViewModel::class.java)
        Mockito.`when`(home.backendType).thenReturn(backend)
        Mockito.`when`(home.ownedWatchHistory).thenReturn(owned)
        Mockito.`when`(home.watchHistory).thenReturn(MutableStateFlow(owned.value.second))
        lastChannelFlow = MutableStateFlow(emptyList())
        Mockito.`when`(home.lastWatchedChannelFlow).thenReturn(lastChannelFlow)
        Mockito.`when`(home.favoriteBaseballTeams).thenReturn(MutableStateFlow(emptySet()))
        Mockito.`when`(home.favoriteBaseballGames).thenReturn(
            MutableStateFlow(emptyList<Pair<String, List<BaseballGameInfo>>>())
        )
        Mockito.`when`(home.baseballDateOffset).thenReturn(MutableStateFlow(0))
        Mockito.`when`(home.genrePickupPrograms).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(home.pickupGenreLabel).thenReturn(MutableStateFlow("アニメ"))
        Mockito.`when`(home.genrePickupTimeSlot).thenReturn(MutableStateFlow("夜"))
        Mockito.doReturn(emptyList<UiChannelState>())
            .`when`(home).getHotChannels(emptyList())
        Mockito.doReturn(emptyList<ReserveItem>())
            .`when`(home).getUpcomingReserves(emptyList())

        live = Mockito.mock(ChannelViewModel::class.java)
        Mockito.`when`(live.liveRows).thenReturn(MutableStateFlow(emptyList<LiveRowState>()))
        epg = Mockito.mock(EpgViewModel::class.java)
        Mockito.`when`(epg.uiState).thenReturn(EpgUiState.Loading)
        Mockito.`when`(epg.selectedBroadcastingType).thenReturn(MutableStateFlow("GR"))
        reserves = Mockito.mock(ReserveViewModel::class.java)
        Mockito.`when`(reserves.reserves).thenReturn(MutableStateFlow(emptyList<ReserveItem>()))
        Mockito.`when`(reserves.isLoading).thenReturn(MutableStateFlow(false))

        records = Mockito.mock(RecordViewModel::class.java)
        Mockito.`when`(records.syncProgress).thenReturn(MutableStateFlow(SyncProgress(isSyncing = false)))
        Mockito.`when`(records.recentRecordings).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.groupedSeries).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(records.availableGenres).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.selectedSeriesGenre).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.programDetail).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.historyResumeError).thenReturn(MutableStateFlow(null))
        settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        store.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${SettingsViewModel::class.java.canonicalName}", settings)
        store.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${RecordViewModel::class.java.canonicalName}", records)
    }

    @After
    fun teardown() {
        compose.runOnUiThread {
            staticMocks.asReversed().forEach { it.close() }
            staticMocks.clear()
        }
        store.clear()
    }

    private fun render(initialTabIndex: Int = 2, groupedChannels: Map<String, List<Channel>> = emptyMap()) {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KomorebiTheme {
                    HomeLauncherScreen(
                        channelViewModel = live, homeViewModel = home, epgViewModel = epg,
                        recordViewModel = records, reserveViewModel = reserves,
                        settingsViewModel = settings,
                        groupedChannels = groupedChannels,
                        mirakurunIp = "", mirakurunPort = "",
                        konomiIp = "", konomiPort = "",
                        onChannelClick = { _, _ -> },
                        selectedChannel = null, onTabChange = {},
                        initialTabIndex = initialTabIndex, selectedProgram = null,
                        onProgramSelected = { selectedProgramAfterClick.set(it) },
                        onVerifiedHistorySelected = { p, _ -> resumed.set(p) },
                        recordedSelectionEpoch = mutableIntStateOf(0),
                        epgSelectedProgram = null, onEpgProgramSelected = {},
                        isEpgJumpMenuOpen = false, onEpgJumpMenuStateChanged = {},
                        triggerBack = false, onBackTriggered = {}, onFinalBack = {},
                        onUiReady = {}, isUiReadyFlag = true,
                        onNavigateToPlayer = { _, _, _ -> }
                    )
                }
            }
        }
    }

    private fun historyCard() = compose.onNode(
        hasClickAction() and hasAnyDescendant(hasText("Owner verified test recording")),
        useUnmergedTree = true
    )

    @Test
    fun foreignBackendHistoryIsNotRenderedEvenWhenHistoryListStillContainsIt() {
        owned.value = "KONOMITV" to listOf(history())
        render()
        compose.onNodeWithText("Owner verified test recording").assertDoesNotExist()
        Mockito.verify(records, Mockito.never()).clearHistoryResumeError()
        assertNull(resumed.get())
    }

    @Test
    fun matchingHistoryOwnerRoutesClickThroughVerifiedProgramLookup(): Unit = runBlocking {
        val verified = Mockito.mock(RecordedProgram::class.java)
        Mockito.`when`(verified.playbackPosition).thenReturn(32.5)
        Mockito.doReturn(verified).`when`(records)
            .getResumableHistoryProgram(42, "EDCB")
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        render()
        compose.waitUntil(10_000L) { historySelection.get() != null }
        compose.runOnIdle { historySelection.get()!!.invoke(owned.value.second.single()) }
        Mockito.verify(records, Mockito.timeout(10_000L)).getResumableHistoryProgram(42, "EDCB")
        compose.waitUntil(10_000L) { resumed.get() != null }
        assertSame("must only publish the verified recording instance", verified, resumed.get())
    }
    private fun renderThroughMainRootBackground(state: MainRootState, epoch: MutableIntState, displayed: Map<String, List<Channel>> = emptyMap()) {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KomorebiTheme {
                    MainRootBackground(
                        state = state, recordedSelectionEpoch = epoch,
                        channelViewModel = live, homeViewModel = home,
                        epgViewModel = epg, recordViewModel = records,
                        reserveViewModel = reserves, settingsViewModel = settings,
                        groupedChannels = displayed,
                        watchHistory = owned.value.second,
                        conditions = emptyList(), reserves = emptyList(),
                        autoReserveKeywords = emptyList(),
                        mirakurunIp = "", mirakurunPort = "",
                        konomiIp = "", konomiPort = "",
                        timeFormat = "24H", safeTabIndex = 2,
                        isSyncingInitial = false,
                        backgroundBrush = SolidColor(Color.Black),
                        onExitApp = {}
                    )
                }
            }
        }
    }

    @Test
    fun mainRootBackgroundAcceptsOnlyVerifiedHistoryAndAdvancesEpochBeforePlayback(): Unit = runBlocking {
        val verified = Mockito.mock(RecordedProgram::class.java)
        val video = Mockito.mock(RecordedVideo::class.java)
        Mockito.`when`(verified.id).thenReturn(42)
        Mockito.`when`(verified.playbackPosition).thenReturn(32.5)
        Mockito.`when`(verified.recordedVideo).thenReturn(video)
        Mockito.`when`(video.hasKeyFrames).thenReturn(true)
        Mockito.`when`(video.duration).thenReturn(3600.0)
        Mockito.doReturn(verified).`when`(records)
            .getResumableHistoryProgram(42, "EDCB")
        val state = MainRootState()
        val epoch = mutableIntStateOf(7)
        interceptMainRootHistoryCallback()
        renderThroughMainRootBackground(state, epoch)
        compose.waitUntil(10_000L) { verifiedSelection.get() != null }
        compose.runOnIdle { verifiedSelection.get()!!.invoke(verified, 32.5) }
        compose.waitUntil(10_000L) { state.selectedProgram != null }
        compose.runOnIdle {
            assertEquals(8, epoch.intValue)
            assertSame("only the verified recording may be selected", verified, state.selectedProgram)
            assertEquals("42", state.lastSelectedProgramId)
            assertEquals(32500L, state.initialPlaybackPositionMs)
            assertNull(state.selectedChannel)
        }
    }

    @Test
    fun homeTabHidesHistoryWhenSourceBackendDiffersEvenIfLegacyFlowStillContainsItems() {
        owned.value = "KONOMITV" to listOf(history())
        render(initialTabIndex = 0)
        compose.onNodeWithText("Owner verified test recording").assertDoesNotExist()
        assertNull(resumed.get())
    }

    @Test
    fun homeTabClickMustVerifyOwnerBeforePublishingRecording(): Unit = runBlocking {
        val verified = Mockito.mock(RecordedProgram::class.java)
        Mockito.`when`(verified.playbackPosition).thenReturn(46.5)
        Mockito.doReturn(verified).`when`(records).getResumableHistoryProgram(42, "EDCB")
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.HomeContentsKt",
            "HomeContents", 11
        )
        render(initialTabIndex = 0)
        compose.waitUntil(10_000L) { historySelection.get() != null }
        compose.runOnIdle { historySelection.get()!!.invoke(owned.value.second.single()) }
        Mockito.verify(records, Mockito.timeout(10_000L)).getResumableHistoryProgram(42, "EDCB")
        compose.waitUntil(10_000L) { resumed.get() != null }
        assertSame(verified, resumed.get())
    }

    private fun channel(id: String, name: String) = Channel(
        id = id, name = name, displayChannelId = id, type = "GR",
        channelNumber = "101", networkId = 10L, serviceId = 20L,
        transportStreamId = 30L, isWatchable = true, isDisplay = true,
        programPresent = null, programFollowing = null, remocon_Id = 1
    )

    @Test
    fun homeTabRefusesSavedChannelFromOtherBackendEvenWithSameNetworkAndService() {
        owned.value = "EDCB" to emptyList()
        lastChannelFlow.value = listOf(channel("legacy-kon-101", "old Konomi channel"))
        val available = channel("edcb-101", "EDCB channel")
        render(initialTabIndex = 0, groupedChannels = mapOf("GR" to listOf(available)))
        compose.onNodeWithText("前回視聴したチャンネル").assertDoesNotExist()
    }

    @Test
    fun homeTabStillShowsExactChannelIdFromSelectedBackend() {
        owned.value = "EDCB" to emptyList()
        val available = channel("edcb-101", "EDCB channel")
        lastChannelFlow.value = listOf(available)
        render(initialTabIndex = 0, groupedChannels = mapOf("GR" to listOf(available)))
        compose.onNodeWithText("前回視聴したチャンネル").assertIsDisplayed()
    }

    @Test
    fun mainRootBackgroundLiveSelectionAdvancesEpochAndStoresChannel() {
        val state = MainRootState()
        val epoch = mutableIntStateOf(5)
        val channel = channel("edcb-live-101", "live channel")
        interceptMainRootHistoryCallback()
        renderThroughMainRootBackground(state, epoch)
        compose.waitUntil(10_000L) { liveSelection.get() != null }
        compose.runOnIdle { liveSelection.get()!!.invoke(channel, false) }
        assertEquals(6, epoch.intValue)
        assertSame(channel, state.selectedChannel)
        assertEquals(channel.id, state.lastSelectedChannelId)
        Mockito.verify(home).saveLastChannel(channel)
    }

    @Test
    fun mainRootBackgroundRecordingSelectionInvalidatesPendingHistoryEpoch() {
        val state = MainRootState()
        val epoch = mutableIntStateOf(3)
        val recording = Mockito.mock(RecordedProgram::class.java)
        val video = Mockito.mock(RecordedVideo::class.java)
        Mockito.`when`(recording.id).thenReturn(98)
        Mockito.`when`(recording.recordedVideo).thenReturn(video)
        Mockito.`when`(video.hasKeyFrames).thenReturn(true)
        Mockito.`when`(video.duration).thenReturn(3600.0)
        interceptMainRootHistoryCallback()
        renderThroughMainRootBackground(state, epoch)
        compose.waitUntil(10_000L) { recordingSelection.get() != null }
        compose.runOnIdle { recordingSelection.get()!!.invoke(recording) }
        assertEquals(4, epoch.intValue)
        assertSame(recording, state.selectedProgram)
        assertEquals("98", state.lastSelectedProgramId)
        assertNull(state.lastSelectedChannelId)
    }

    @Test
    fun mainRootBackgroundEpgNavigationAdvancesEpochBeforeSelectingLiveChannel() {
        val state = MainRootState()
        val epoch = mutableIntStateOf(10)
        val channel = channel("edcb-epg-3", "EPG channel")
        interceptMainRootHistoryCallback()
        renderThroughMainRootBackground(state, epoch, mapOf("GR" to listOf(channel)))
        compose.waitUntil(10_000L) { epgNavigation.get() != null }
        compose.runOnIdle {
            epgNavigation.get()!!.invoke(channel.id, "", "")
        }
        assertEquals(11, epoch.intValue)
        assertSame(channel, state.selectedChannel)
        assertEquals(channel.id, state.lastSelectedChannelId)
        Mockito.verify(home).saveLastChannel(channel)
    }

    @Test
    fun sameBackendReplacementWithSameRecordIdCannotActivateOldHistoryCard(): Unit = runBlocking {
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        val obsolete = owned.value.second.single()
        render()
        compose.waitUntil(10_000L) { historySelection.get() != null }
        val oldCardCallback = historySelection.get()!!
        compose.runOnIdle { owned.value = "EDCB" to listOf(history().copy(playback_position = 18.0)) }
        compose.runOnIdle { oldCardCallback.invoke(obsolete) }
        Mockito.verify(records, Mockito.never()).getResumableHistoryProgram(
            Mockito.anyInt(), Mockito.anyString()
        )
        assertNull(resumed.get())
    }

    @Test
    fun callbackFromOtherBackendCannotInterpretReusedRecordIdAsNewRecording(): Unit = runBlocking {
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        val old = owned.value.second.single()
        render()
        compose.waitUntil(10_000L) { historySelection.get() != null }
        val oldCallback = historySelection.get()!!
        compose.runOnIdle {
            backend.value = "KONOMITV"
            owned.value = "KONOMITV" to listOf(history())
        }
        compose.runOnIdle { oldCallback.invoke(old) }
        Mockito.verify(records, Mockito.never()).getResumableHistoryProgram(
            Mockito.anyInt(), Mockito.anyString()
        )
        assertNull(resumed.get())
    }

    @Test
    fun laterRecordingClickCancelsPendingHistoryAndBlocksItsStaleReply(): Unit = runBlocking {
        val suspended = AtomicReference<Continuation<RecordedProgram?>?>()
        val staleProgram = Mockito.mock(RecordedProgram::class.java)
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RecordedProgram?>
            suspended.set(continuation)
            COROUTINE_SUSPENDED
        }.`when`(records).getResumableHistoryProgram(42, "EDCB")
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        render()
        compose.waitUntil(10_000L) {
            historySelection.get() != null && recordedCardSelection.get() != null
        }
        val selected = owned.value.second.single()
        compose.runOnIdle { historySelection.get()!!.invoke(selected) }
        compose.waitUntil(10_000L) { suspended.get() != null }
        val later = Mockito.mock(RecordedProgram::class.java)
        compose.runOnIdle { recordedCardSelection.get()!!.invoke(later) }
        suspended.get()!!.resume(staleProgram)
        compose.waitForIdle()
        assertSame("the later click must win", later, selectedProgramAfterClick.get())
        assertNull("late history response must never override later selection", resumed.get())
    }


    @Test
    fun homeFlowFiltersUnavailableHistoryBeforeLimitingToTen() {
        val captured = AtomicReference<List<Channel>?>()
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val cls = Class.forName("com.beeregg2001.komorebi.ui.home.HomeContentsKt") as Class<Any>
            staticMocks += Mockito.mockStatic(cls, Answer { invocation ->
                if (invocation.method.name == "HomeContents") {
                    @Suppress("UNCHECKED_CAST")
                    captured.set(invocation.arguments[0] as List<Channel>)
                    null
                } else invocation.callRealMethod()
            })
        }
        owned.value = "EDCB" to emptyList()
        val all = (1..25).map { channel("channel-$it", "Channel $it") }
        // First ten history entries (25..16) are unavailable; 15..1 can be tuned.
        lastChannelFlow.value = all.asReversed()
        render(initialTabIndex = 0, groupedChannels = mapOf("GR" to all.take(15)))
        compose.waitUntil(10_000L) { captured.get() != null }
        assertEquals((15 downTo 6).map { "channel-$it" },
            captured.get()!!.map { it.id })
    }


    @Test
    fun verifiedHistoryPlaybackRejectsUnparsedAndClampsResumeBoundaryPositions() {
        val state = MainRootState()
        val epoch = mutableIntStateOf(10)
        val program = Mockito.mock(RecordedProgram::class.java)
        val video = Mockito.mock(RecordedVideo::class.java)
        Mockito.`when`(program.id).thenReturn(202)
        Mockito.`when`(program.recordedVideo).thenReturn(video)
        interceptMainRootHistoryCallback()
        renderThroughMainRootBackground(state, epoch)
        compose.waitUntil(10_000L) { verifiedSelection.get() != null }
        compose.runOnIdle {
            Mockito.`when`(video.hasKeyFrames).thenReturn(false)
            verifiedSelection.get()!!.invoke(program, 32.5)
            assertNull("unparsed recording may not start", state.selectedProgram)
            assertEquals(10, epoch.intValue)

            Mockito.`when`(video.hasKeyFrames).thenReturn(true)
            Mockito.`when`(video.duration).thenReturn(3600.0)
            verifiedSelection.get()!!.invoke(program, 4.9)
            assertSame(program, state.selectedProgram)
            assertEquals(0L, state.initialPlaybackPositionMs)

            verifiedSelection.get()!!.invoke(program, 3595.0)
            assertEquals(0L, state.initialPlaybackPositionMs)

            Mockito.`when`(video.duration).thenReturn(0.0)
            verifiedSelection.get()!!.invoke(program, 16.25)
            assertEquals(16250L, state.initialPlaybackPositionMs)
            assertEquals(13, epoch.intValue)
        }
    }


    @Test
    fun homeCorrectsUnavailableGrSelectionToFirstPresentBroadcastType() {
        val onlyBsAndCs = mapOf(
            "BS" to listOf(Channel(
                id = "bs1", displayChannelId = "bs1", name = "BS",
                channelNumber = "101", networkId = 1L, serviceId = 2L,
                type = "BS", isWatchable = true, isDisplay = true,
                programPresent = null, programFollowing = null, remocon_Id = 1
            )),
            "CS" to emptyList<Channel>()
        )
        render(initialTabIndex = 0, groupedChannels = onlyBsAndCs)
        Mockito.verify(epg, Mockito.timeout(10_000L).times(1)).updateBroadcastingType("BS")
        Mockito.verify(epg, Mockito.never()).updateBroadcastingType("GR")
    }

    @Test
    fun homePreservesExistingBsBroadcastTypeAcrossBackendSwitch() {
        Mockito.`when`(epg.selectedBroadcastingType).thenReturn(MutableStateFlow("BS"))
        val channels = mapOf(
            "BS" to emptyList<Channel>(),
            "CS" to emptyList<Channel>()
        )
        render(initialTabIndex = 0, groupedChannels = channels)
        compose.runOnIdle { backend.value = "EPGSTATION" }
        compose.waitForIdle()
        Mockito.verify(epg, Mockito.never()).updateBroadcastingType(Mockito.anyString())
    }


    @Test
    fun currentHistoryLookupFailureDisplaysOnceAndDoesNotSelectRecording(): Unit = runBlocking {
        val reason = MutableStateFlow<String?>("The recording is unavailable")
        Mockito.`when`(records.historyResumeError).thenReturn(reason)
        Mockito.doAnswer {
            reason.value = "The recording is unavailable"
            null
        }.`when`(records).getResumableHistoryProgram(42, "EDCB")
        Mockito.doAnswer { reason.value = null; null }.`when`(records).clearHistoryResumeError()
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        render()
        compose.waitUntil(10_000L) { historySelection.get() != null }
        compose.runOnIdle { historySelection.get()!!.invoke(owned.value.second.single()) }
        Mockito.verify(records, Mockito.timeout(10_000L).times(1)).clearHistoryResumeError()
        assertNull(resumed.get())
        assertNull(selectedProgramAfterClick.get())
        assertNull(reason.value)
    }

    @Test
    fun staleFailureAfterNewerRecordingSelectionCannotPublishOrConsumeError(): Unit = runBlocking {
        val deferred = AtomicReference<Continuation<RecordedProgram?>?>()
        val reason = MutableStateFlow<String?>(null)
        Mockito.`when`(records.historyResumeError).thenReturn(reason)
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RecordedProgram?>
            deferred.set(continuation)
            COROUTINE_SUSPENDED
        }.`when`(records).getResumableHistoryProgram(42, "EDCB")
        interceptHistorySelection(
            "com.beeregg2001.komorebi.ui.home.VideoTabContentKt",
            "VideoTabContent", 7
        )
        render()
        compose.waitUntil(10_000L) { historySelection.get() != null && recordedCardSelection.get() != null }
        compose.runOnIdle { historySelection.get()!!.invoke(owned.value.second.single()) }
        compose.waitUntil(10_000L) { deferred.get() != null }
        val later = Mockito.mock(RecordedProgram::class.java)
        compose.runOnIdle { recordedCardSelection.get()!!.invoke(later) }
        reason.value = "obsolete request rejected"
        deferred.get()!!.resume(null)
        compose.waitForIdle()
        assertSame(later, selectedProgramAfterClick.get())
        assertNull(resumed.get())
        Mockito.verify(records, Mockito.never()).clearHistoryResumeError()
    }

}
