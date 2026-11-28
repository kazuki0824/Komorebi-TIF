package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import android.graphics.Canvas
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.beeregg2001.komorebi.ui.epg.ModernEpgCanvasEngine_Smooth
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.OffsetDateTime

/** Exercises ModernEpgCanvasEngine_Smooth directly, not only its state engine. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpgCanvasOverlayUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val rootAndroidView = AtomicReference<android.view.View?>()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
    }

    @After
    fun tearDown() { store.clear() }

    private fun setContent(
        uiState: androidx.compose.runtime.State<EpgUiState>,
        gridFocus: FocusRequester = FocusRequester(),
        onSelection: (EpgProgram) -> Unit = {}
    ) {
        val vm = Mockito.mock(EpgViewModel::class.java)
        store.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:${EpgViewModel::class.java.canonicalName}",
            vm
        )
        val settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.hideSubChannels).thenReturn(MutableStateFlow(false))
        Mockito.`when`(settings.epgColumnCount).thenReturn(MutableStateFlow("7"))
        Mockito.`when`(settings.epgFontSizeScale).thenReturn(MutableStateFlow("1.0"))
        Mockito.`when`(settings.epgVisibleHours).thenReturn(MutableStateFlow("6"))
        compose.setContent {
            rootAndroidView.set(LocalView.current)
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KomorebiTheme {
                    ModernEpgCanvasEngine_Smooth(
                        uiState = uiState.value,
                        logoUrls = emptyList(),
                        topTabFocusRequester = FocusRequester(),
                        headerFocusRequester = FocusRequester(),
                        jumpButtonFocusRequester = FocusRequester(),
                        gridFocusRequester = gridFocus,
                        onProgramSelected = onSelection,
                        onEpgJumpMenuStateChanged = {},
                        currentType = "GR",
                        onTypeChanged = {},
                        availableTypes = listOf("GR"),
                        onUpdateTargetTime = {},
                        onRequestJumpToNow = {},
                        searchButtonFocusRequester = FocusRequester(),
                        onSearchClick = {},
                        timeFormat = "24H",
                        settingsViewModel = settings
                    )
                }
            }
        }
    }

    private fun drawRootBitmap(): Bitmap {
        return compose.runOnIdle {
            val v = requireNotNull(rootAndroidView.get())
            assertTrue("Android view must have been laid out", v.width > 0 && v.height > 0)
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                v.draw(Canvas(bitmap))
            }
        }
    }

    private fun emptySuccess() = EpgUiState.Success(
        data = emptyList(), logoUrls = emptyList(), mirakurunIp = "",
        mirakurunPort = "", targetTime = OffsetDateTime.parse("2030-01-02T04:00:00+09:00")
    )

    @Test
    fun emptySuccessfulEpgShowsExplicitNoScheduleMessage() {
        val state = mutableStateOf<EpgUiState>(emptySuccess())
        setContent(state)
        compose.onNodeWithText("番組表がありません").assertIsDisplayed()
        compose.onNodeWithText("番組表を取得できません").assertDoesNotExist()
    }

    @Test
    fun epgErrorShowsFailureMessageInsteadOfStaleEmptySuccess() {
        val state = mutableStateOf<EpgUiState>(EpgUiState.Error("injected offline"))
        setContent(state)
        compose.onNodeWithText("番組表を取得できません").assertIsDisplayed()
        compose.onNodeWithText("番組表がありません").assertDoesNotExist()
    }

    @Test
    fun loadingStateRemovesPreviousEmptySuccessOverlayImmediately() {
        val state = mutableStateOf<EpgUiState>(emptySuccess())
        setContent(state)
        compose.onNodeWithText("番組表がありません").assertIsDisplayed()
        compose.runOnIdle { state.value = EpgUiState.Loading }
        compose.onNodeWithText("番組表がありません").assertDoesNotExist()
        compose.onNodeWithText("番組表を取得できません").assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun errorAfterPopulatedEpgClearsOldFocusAndReplacesCanvas() {
        val now = OffsetDateTime.now()
        val channel = EpgChannel(
            id = "GR_FAIL", display_channel_id = "GR_FAIL", network_id = 1,
            service_id = 1, transport_stream_id = 2, remocon_id = 1,
            channel_number = "101", type = "GR", name = "Old EPG Channel",
            jikkyo_force = null, is_subchannel = false, is_radiochannel = false,
            is_watchable = true
        )
        val program = EpgProgram(
            id = "OLD_EVENT", channel_id = channel.id, network_id = 1,
            service_id = 1, event_id = 101, title = "Old selectable program",
            description = "", detail = null, start_time = now.minusMinutes(30).toString(),
            end_time = now.plusMinutes(30).toString(),
            duration = 3600, is_free = true, genres = emptyList(),
            video_type = null, audio_type = null, audio_sampling_rate = null
        )
        val loaded = EpgUiState.Success(
            data = listOf(EpgChannelWrapper(channel, listOf(program))),
            logoUrls = emptyList(), mirakurunIp = "", mirakurunPort = "", targetTime = now
        )
        val state = mutableStateOf<EpgUiState>(loaded)
        val focus = FocusRequester()
        val selections = AtomicInteger(0)
        setContent(state, focus) { if (it.id == program.id) selections.incrementAndGet() }
        compose.runOnIdle { focus.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000L) { selections.get() > 0 }
        val before = selections.get()
        compose.runOnIdle { state.value = EpgUiState.Error("network rejected") }
        compose.onNodeWithText("番組表を取得できません").assertIsDisplayed()
        compose.onNodeWithText("Old selectable program").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("Error must make the former focused EPG cell inert", before, selections.get())
    }

    @Test
    fun loadingAfterPopulatedEpgClearsOldFocusedProgramSelection() {
        val now = OffsetDateTime.now()
        val channel = EpgChannel(
            id = "GR_TEST", display_channel_id = "GR_TEST",
            network_id = 1, service_id = 1, transport_stream_id = 2,
            remocon_id = 1, channel_number = "101", type = "GR",
            name = "Test Channel", jikkyo_force = null,
            is_subchannel = false, is_radiochannel = false, is_watchable = true
        )
        val program = EpgProgram(
            id = "EPG_EVENT_01", channel_id = channel.id,
            network_id = 1, service_id = 1, event_id = 101,
            title = "Actual selectable program", description = "",
            detail = null, start_time = now.minusMinutes(30).toString(),
            end_time = now.plusMinutes(30).toString(),
            duration = 3600, is_free = true, genres = emptyList(),
            video_type = null, audio_type = null, audio_sampling_rate = null
        )
        val loaded = EpgUiState.Success(
            data = listOf(EpgChannelWrapper(channel, listOf(program))),
            logoUrls = emptyList(), mirakurunIp = "", mirakurunPort = "",
            targetTime = now
        )
        val state = mutableStateOf<EpgUiState>(loaded)
        val requestedFocus = FocusRequester()
        val selections = AtomicInteger(0)
        setContent(state, requestedFocus) {
            if (it.id == program.id) selections.incrementAndGet()
        }
        compose.waitForIdle()
        compose.runOnIdle { requestedFocus.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000L) { selections.get() > 0 }
        val before = selections.get()

        compose.runOnIdle { state.value = EpgUiState.Loading }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(
            "Loading must clear the focused program, not merely hide old drawing",
            before, selections.get()
        )
    }


    @Test
    fun opaqueLoadingBackgroundMatchesErrorBackgroundOverPopulatedCanvas() {
        val now = OffsetDateTime.now()
        val channel = EpgChannel(
            id = "GR_OPAQUE", display_channel_id = "GR_OPAQUE",
            network_id = 1, service_id = 2, transport_stream_id = 3,
            remocon_id = 1, channel_number = "101", type = "GR",
            name = "Opaque check", jikkyo_force = null,
            is_subchannel = false, is_radiochannel = false, is_watchable = true
        )
        val program = EpgProgram(
            id = "OPAQUE_PROGRAM", channel_id = channel.id,
            network_id = 1, service_id = 2, event_id = 301,
            title = "Previously rendered show", description = "old",
            detail = null, start_time = now.minusMinutes(15).toString(),
            end_time = now.plusMinutes(45).toString(),
            duration = 3600, is_free = true, genres = null,
            video_type = null, audio_type = null, audio_sampling_rate = null
        )
        val state = mutableStateOf<EpgUiState>(EpgUiState.Success(
            data = listOf(EpgChannelWrapper(channel, listOf(program))),
            logoUrls = emptyList(), mirakurunIp = "", mirakurunPort = "", targetTime = now
        ))
        setContent(state)
        compose.waitForIdle()
        val original = drawRootBitmap()
        compose.runOnIdle { state.value = EpgUiState.Loading }
        compose.waitForIdle()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        val loading = drawRootBitmap()
        compose.runOnIdle { state.value = EpgUiState.Error("failed") }
        compose.waitForIdle()
        val error = drawRootBitmap()
        // Compare unaffected grid cells, not the spinner/error message in the middle.
        // A translucent Loading overlay would reveal stale program rectangles.
        // First program column occupies roughly 4.7%-18.3% of screen
        // (60dp time rail + one column of the 7-column schedule).
        // Sample INSIDE that program column, not unrelated headers/lines.
        var paintedProgramPixels = 0
        for (xFraction in listOf(0.09f, 0.12f, 0.15f)) {
            for (yFraction in listOf(0.63f, 0.72f, 0.81f)) {
                val x = (loading.width * xFraction).toInt()
                val y = (loading.height * yFraction).toInt()
                val old = original.getPixel(x, y)
                val loadingBackground = loading.getPixel(x, y)
                val errorBackground = error.getPixel(x, y)
                if (old != errorBackground) {
                    paintedProgramPixels++
                    assertEquals("Loading must hide each painted Success cell at $x,$y",
                        errorBackground, loadingBackground)
                }
            }
        }
        assertTrue("Success fixture must actually paint program pixels inside the first grid column",
            paintedProgramPixels > 0)
    }

}
