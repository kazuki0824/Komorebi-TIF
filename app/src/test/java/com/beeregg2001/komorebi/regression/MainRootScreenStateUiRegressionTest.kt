package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.ui.main.MainRootState
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual MainRootScreen composition and LaunchedEffect branches. Replace only
 * child player renderers with inert test UI: no real tuner, SMB server or VLC.
 * Test state injection uses Mockito's thread-scoped static mock of the
 * rememberMainRootState() factory (no production source changes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class MainRootScreenStateUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var state: MainRootState
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var channels: MutableStateFlow<Map<String, List<Channel>>>
    private lateinit var channelError: MutableStateFlow<Boolean>
    private lateinit var startup: MutableStateFlow<String>
    private lateinit var home: HomeViewModel
    private lateinit var channelVm: ChannelViewModel
    private lateinit var recordVm: RecordViewModel
    private lateinit var epgVm: EpgViewModel
    private lateinit var reserveVm: ReserveViewModel
    private lateinit var settingsVm: SettingsViewModel
    private lateinit var aiVm: AiConciergeViewModel
    private val mocked = mutableListOf<MockedStatic<Any>>()
    private val renderedRoot = AtomicReference<MainRootState?>()
    private val exposedEpoch = AtomicReference<MutableIntState?>()
    private val smbProgram = AtomicReference<RecordedProgram?>()
    private val liveSelect = AtomicReference<((Channel) -> Unit)?>(null)

    @Before
    fun setup(): Unit = runBlocking {
        state = MainRootState()
        backend = MutableStateFlow("KONOMITV")
        channels = MutableStateFlow(emptyMap())
        channelError = MutableStateFlow(false)
        startup = MutableStateFlow("OFF")

        home = Mockito.mock(HomeViewModel::class.java)
        Mockito.`when`(home.backendType).thenReturn(backend)
        Mockito.`when`(home.favoriteBaseballTeams).thenReturn(MutableStateFlow(emptySet()))
        Mockito.`when`(home.isLoading).thenReturn(MutableStateFlow(false))
        Mockito.`when`(home.watchHistory).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(home.lastWatchedChannelFlow).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(home.updateState)
            .thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))

        channelVm = Mockito.mock(ChannelViewModel::class.java)
        Mockito.`when`(channelVm.groupedChannels).thenReturn(channels)
        Mockito.`when`(channelVm.isLoading).thenReturn(MutableStateFlow(false))
        Mockito.`when`(channelVm.connectionError).thenReturn(channelError)

        recordVm = Mockito.mock(RecordViewModel::class.java)
        Mockito.`when`(recordVm.recentRecordings).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(recordVm.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))

        epgVm = Mockito.mock(EpgViewModel::class.java)
        Mockito.`when`(epgVm.isInitialLoadComplete).thenReturn(MutableStateFlow(true))

        reserveVm = Mockito.mock(ReserveViewModel::class.java)
        Mockito.`when`(reserveVm.conditions)
            .thenReturn(MutableStateFlow(emptyList<ReservationCondition>()))
        Mockito.`when`(reserveVm.reserves)
            .thenReturn(MutableStateFlow(emptyList<ReserveItem>()))

        aiVm = Mockito.mock(AiConciergeViewModel::class.java)
        Mockito.`when`(aiVm.pendingAction).thenReturn(MutableSharedFlow<AiConciergeAction>())

        settingsVm = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settingsVm.timeFormat).thenReturn(MutableStateFlow("24H"))
        Mockito.`when`(settingsVm.geminiApiKey).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.appTheme).thenReturn(MutableStateFlow("MONOTONE"))
        Mockito.`when`(settingsVm.isSettingsInitialized).thenReturn(MutableStateFlow(true))
        Mockito.`when`(settingsVm.startupChannel).thenReturn(startup)
        Mockito.`when`(settingsVm.mirakurunIp).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.mirakurunPort).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.konomiIp).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.konomiPort).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.edcbIp).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.edcbPort).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settingsVm.liveQuality).thenReturn(MutableStateFlow("720p"))
        Mockito.`when`(settingsVm.videoQuality).thenReturn(MutableStateFlow("720p"))
        Mockito.doReturn("ホーム").`when`(settingsVm).getStartupTabOnce()
    }

    private fun channel(id: String) = Channel(
        id = id, displayChannelId = id, name = id, channelNumber = "101",
        networkId = 1L, serviceId = 2L, transportStreamId = 3L, type = "GR",
        isWatchable = true, isDisplay = true, programPresent = null,
        programFollowing = null, remocon_Id = 1
    )

    private fun recorded() = RecordedProgram(
        id = 99, title = "previous backend recording", description = "",
        startTime = "2030-01-01T00:00:00+09:00",
        endTime = "2030-01-01T01:00:00+09:00",
        duration = 3600.0, isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = 99, status = "Recorded", filePath = "recorded.ts",
            recordingStartTime = "2030-01-01T00:00:00+09:00",
            recordingEndTime = "2030-01-01T01:00:00+09:00",
            duration = 3600.0, containerFormat = "ts",
            videoCodec = "H264", audioCodec = "AAC"
        )
    )

    private fun intercept(className: String, answer: (org.mockito.invocation.InvocationOnMock) -> Any?) {
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val klass = Class.forName(className) as Class<Any>
            mocked += Mockito.mockStatic(klass, Answer { call -> answer(call) })
        }
    }

    private fun installPlayerIsolation() {
        intercept("com.beeregg2001.komorebi.ui.main.MainRootStateKt") {
            if (it.method.name == "rememberMainRootState") state else it.callRealMethod()
        }
        intercept("com.beeregg2001.komorebi.ui.main.MainRootBackgroundKt") {
            if (it.method.name == "MainRootBackground") {
                renderedRoot.set(it.arguments[0] as MainRootState)
                exposedEpoch.set(it.arguments[1] as MutableIntState)
                null
            } else it.callRealMethod()
        }
        intercept("com.beeregg2001.komorebi.ui.main.MainRootDialogsKt") {
            if (it.method.name == "MainRootDialogs") null else it.callRealMethod()
        }
        intercept("com.beeregg2001.komorebi.ui.video.player.VideoPlayerScreenKt") {
            if (it.method.name == "VideoPlayerScreen") null else it.callRealMethod()
        }
        intercept("com.beeregg2001.komorebi.ui.live.LivePlayerScreenKt") {
            if (it.method.name == "LivePlayerScreen") {
                @Suppress("UNCHECKED_CAST")
                liveSelect.set(it.arguments[13] as (Channel) -> Unit)
                null
            } else it.callRealMethod()
        }
        intercept("com.beeregg2001.komorebi.ui.video.smb.player.SmbVlcPlayerScreenKt") {
            if (it.method.name == "SmbVlcPlayerScreen") {
                smbProgram.set(it.arguments[0] as RecordedProgram)
                null
            } else it.callRealMethod()
        }
    }

    private fun render() {
        installPlayerIsolation()
        compose.setContent {
            MainRootScreen(
                channelViewModel = channelVm, epgViewModel = epgVm,
                homeViewModel = home, settingsViewModel = settingsVm,
                recordViewModel = recordVm, reserveViewModel = reserveVm,
                aiConciergeViewModel = aiVm, onExitApp = {}
            )
        }
    }

    @After
    fun tearDown() {
        compose.runOnUiThread {
            mocked.asReversed().forEach { it.close() }
            mocked.clear()
        }
    }

    @Test
    fun startupShellUsesActualMainRootStateAfterSuccessfulInitialization() {
        render()
        compose.waitUntil(10_000L) { renderedRoot.get() != null }
        assertSame(state, renderedRoot.get())
        assertTrue(state.hasAppliedStartupTab)
        assertTrue(state.isDataReady)
        assertFalse(state.showConnectionErrorDialog)
    }

    @Test
    fun backendChangeRevokesPreviousRecordingPlaybackAndSelection() {
        state.selectedProgram = recorded()
        state.lastSelectedProgramId = "99"
        state.isMiniPlayerMode = true
        render()
        compose.waitUntil(10_000L) { renderedRoot.get() != null }
        compose.runOnIdle { backend.value = "EDCB" }
        compose.waitUntil(10_000L) { state.selectedProgram == null }
        assertNull(state.lastSelectedProgramId)
        assertNull(state.selectedChannel)
        assertFalse(state.isMiniPlayerMode)
        assertFalse(state.playerShowOverlay)
    }

    @Test
    fun initialLastWatchedChannelSelectionUsesValidatedFullIds(): Unit = runBlocking {
        startup.value = "LAST_WATCHED"
        val first = channel("edcb-gr-101")
        val second = channel("edcb-gr-202")
        channels.value = mapOf("GR" to listOf(first, second))
        Mockito.doReturn(listOf(second.id)).`when`(home)
            .recentStartupChannelIds(listOf(first.id, second.id))
        render()
        compose.waitUntil(10_000L) { state.selectedChannel != null }
        assertSame(second, state.selectedChannel)
        assertEquals(second.id, state.lastSelectedChannelId)
        Mockito.verify(home).saveLastChannel(second)
        assertEquals(1, exposedEpoch.get()?.intValue)
    }

    @Test
    fun channelTransportErrorIsNotMisreportedAsSuccessfulEmptyChannelList() {
        channelError.value = true
        render()
        compose.waitUntil(10_000L) { state.showConnectionErrorDialog }
        assertFalse(state.isDataReady)
        compose.runOnIdle { channelError.value = false }
        compose.waitUntil(10_000L) { state.isDataReady && !state.showConnectionErrorDialog }
    }

    @Test
    fun smbPlaybackModelIsBuiltFromSmbItemEvenWhenRecordedHistoryIsEmpty() {
        val smb = SmbItem(
            name = "movie.mkv", path = "smb://test-share/videos/movie.mkv",
            isDirectory = false, size = 1024L, lastModified = 125000L
        )
        state.selectedSmbItem = smb
        render()
        compose.waitUntil(10_000L) { smbProgram.get() != null }
        val model = requireNotNull(smbProgram.get())
        assertEquals(smb.name, model.title)
        assertEquals(smb.path, model.recordedVideo.filePath)
        assertEquals("SMB", model.recordedVideo.status)
        assertEquals(smb.path.hashCode(), model.id)
        assertEquals("mkv", model.recordedVideo.containerFormat)
        assertTrue("SMB must not use a synthetic network recording from Room", model.description.contains(smb.path))
    }

    @Test
    fun livePlayerSubselectionIncrementsEpochBeforeReplacingChannel() {
        val first = channel("EDCB:channel:A")
        val second = channel("EDCB:channel:B")
        state.selectedChannel = first
        render()
        compose.waitUntil(10_000L) { liveSelect.get() != null && exposedEpoch.get() != null }
        compose.runOnIdle { liveSelect.get()!!.invoke(second) }
        assertSame(second, state.selectedChannel)
        assertEquals(second.id, state.lastSelectedChannelId)
        assertEquals(1, requireNotNull(exposedEpoch.get()).intValue)
        Mockito.verify(home).saveLastChannel(second)
    }

    @Test
    fun backendChangeClearsSelectedLiveChannelAndLastChannelIdentity() {
        val old = channel("KONOMITV:old-live")
        state.selectedChannel = old
        state.lastSelectedChannelId = old.id
        state.isMiniPlayerMode = true
        render()
        compose.waitUntil(10_000L) { renderedRoot.get() != null }
        compose.runOnIdle { backend.value = "EDCB" }
        compose.waitUntil(10_000L) { state.selectedChannel == null }
        assertNull(state.lastSelectedChannelId)
        assertFalse(state.isMiniPlayerMode)
    }

    @Test
    fun backendChangeRetainsIndependentSmbItemAndMiniPlayerSelection() {
        val smb = SmbItem(
            name = "independent.mkv", path = "smb://media/independent.mkv",
            isDirectory = false, size = 512L, lastModified = 125000L
        )
        state.selectedSmbItem = smb
        state.isMiniPlayerMode = true
        render()
        compose.waitUntil(10_000L) { smbProgram.get() != null }
        compose.runOnIdle { backend.value = "EDCB" }
        compose.waitForIdle()
        assertSame(smb, state.selectedSmbItem)
        assertEquals("smb://media/independent.mkv", smbProgram.get()?.recordedVideo?.filePath)
        assertTrue(state.isMiniPlayerMode)
        assertNull(state.selectedChannel)
    }

    @Test
    fun smbAdapterRebuildsFromSelectedItemNotNonemptyRecordedList() {
        Mockito.`when`(recordVm.recentRecordings).thenReturn(
            MutableStateFlow(listOf(recorded()))
        )
        val first = SmbItem(
            name = "first.mkv", path = "smb://media/first.mkv",
            isDirectory = false, size = 512L, lastModified = 125000L
        )
        val second = SmbItem(
            name = "next.ts", path = "smb://media/next.ts",
            isDirectory = false, size = 999L, lastModified = 345000L
        )
        state.selectedSmbItem = first
        render()
        compose.waitUntil(10_000L) { smbProgram.get()?.title == "first.mkv" }
        assertEquals(java.time.Instant.ofEpochMilli(125000L).toString(), smbProgram.get()?.startTime)
        compose.runOnIdle { state.selectedSmbItem = second }
        compose.waitUntil(10_000L) { smbProgram.get()?.title == "next.ts" }
        val model = requireNotNull(smbProgram.get())
        assertEquals(second.path.hashCode(), model.id)
        assertEquals(second.path, model.recordedVideo.filePath)
        assertEquals("next.ts", model.title)
        assertEquals("ts", model.recordedVideo.containerFormat)
        assertEquals(java.time.Instant.ofEpochMilli(345000L).toString(), model.startTime)
        assertEquals(model.startTime, model.endTime)
        assertEquals(0.0, model.duration, 0.0)
        assertEquals(0.0, model.recordedVideo.duration, 0.0)
        assertEquals(0.0, model.recordedVideo.duration, 0.0)
        assertFalse(model.description.contains("previous backend recording"))
    }

}
