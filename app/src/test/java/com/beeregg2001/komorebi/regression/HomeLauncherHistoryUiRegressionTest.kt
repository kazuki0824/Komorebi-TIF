package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.beeregg2001.komorebi.data.model.*
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Real HomeLauncherScreen history ownership routing, not VideoTabContent alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class HomeLauncherHistoryUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = store
    }
    private lateinit var home: HomeViewModel
    private lateinit var records: RecordViewModel
    private lateinit var live: ChannelViewModel
    private lateinit var epg: EpgViewModel
    private lateinit var reserves: ReserveViewModel
    private lateinit var settings: SettingsViewModel
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var owned: MutableStateFlow<Pair<String?, List<KonomiHistoryProgram>>>
    private val resumed = AtomicReference<RecordedProgram?>()

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
        Mockito.`when`(home.lastWatchedChannelFlow).thenReturn(MutableStateFlow(emptyList<Channel>()))
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
        Mockito.`when`(records.recentRecordings).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.groupedSeries).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(records.availableGenres).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.selectedSeriesGenre).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.programDetail).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.historyResumeError).thenReturn(MutableStateFlow(null))
        settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        store.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${SettingsViewModel::class.java.canonicalName}", settings)
    }

    @After
    fun teardown() { store.clear() }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KomorebiTheme {
                    HomeLauncherScreen(
                        channelViewModel = live, homeViewModel = home, epgViewModel = epg,
                        recordViewModel = records, reserveViewModel = reserves,
                        settingsViewModel = settings,
                        groupedChannels = emptyMap(),
                        mirakurunIp = "", mirakurunPort = "",
                        konomiIp = "", konomiPort = "",
                        onChannelClick = { _, _ -> },
                        selectedChannel = null, onTabChange = {},
                        initialTabIndex = 2, selectedProgram = null,
                        onProgramSelected = {},
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
        render()
        compose.onAllNodes(hasScrollAction())[0]
            .performScrollToNode(hasText("続きから見る"))
        compose.onNodeWithText("Owner verified test recording").assertIsDisplayed()
        historyCard().performClick()
        Mockito.verify(records, Mockito.timeout(10_000L)).getResumableHistoryProgram(42, "EDCB")
        compose.waitUntil(10_000L) { resumed.get() != null }
        assertSame("must only publish the verified recording instance", verified, resumed.get())
    }
}
