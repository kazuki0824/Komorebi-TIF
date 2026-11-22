package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

/** Exercises ModernEpgCanvasEngine_Smooth directly, not only its state engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class EpgCanvasOverlayUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
    }

    @After
    fun tearDown() { store.clear() }

    private fun setContent(uiState: androidx.compose.runtime.State<EpgUiState>) {
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
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KomorebiTheme {
                    ModernEpgCanvasEngine_Smooth(
                        uiState = uiState.value,
                        logoUrls = emptyList(),
                        topTabFocusRequester = FocusRequester(),
                        headerFocusRequester = FocusRequester(),
                        jumpButtonFocusRequester = FocusRequester(),
                        gridFocusRequester = FocusRequester(),
                        onProgramSelected = {},
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
    }
}
