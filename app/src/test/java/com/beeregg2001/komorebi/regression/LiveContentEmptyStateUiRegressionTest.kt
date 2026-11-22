package com.beeregg2001.komorebi.regression

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.ui.home.LiveContent
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real LiveContent Compose tree and Android semantics under Robolectric.
 * No external TV service is contacted; these checks only cover empty/loading UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveContentEmptyStateUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun existingNetworkBackendEmptyListShowsMessageExceptWhileLoading() {
        val channelVm = Mockito.mock(ChannelViewModel::class.java)
        val loading = MutableStateFlow(false)
        Mockito.`when`(channelVm.liveRows).thenReturn(
            MutableStateFlow(emptyList<LiveRowState>())
        )
        Mockito.`when`(channelVm.isLoading).thenReturn(loading)
        val settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(MutableStateFlow("EDCB"))

        compose.setContent {
            KomorebiTheme {
                LiveContent(
                    channelViewModel = channelVm,
                    epgViewModel = Mockito.mock(EpgViewModel::class.java),
                    groupedChannels = emptyMap(),
                    selectedChannel = null,
                    onChannelClick = {},
                    onFocusChannelChange = {},
                    mirakurunIp = "", mirakurunPort = "",
                    konomiIp = "", konomiPort = "",
                    topNavFocusRequester = FocusRequester(),
                    contentFirstItemRequester = FocusRequester(),
                    onPlayerStateChanged = {},
                    reserveViewModel = Mockito.mock(ReserveViewModel::class.java),
                    settingsViewModel = settings
                )
            }
        }

        compose.onNodeWithText("表示できるチャンネルがありません。").assertIsDisplayed()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("表示できるチャンネルがありません。").assertDoesNotExist()
    }
}
