package com.beeregg2001.komorebi.regression

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.live.ChannelListOverlay
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the PR #3 empty-tab and absent-current-channel focus fallback in Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelListOverlayUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    private fun channel(id: String) = Channel(
        id = id, displayChannelId = id, name = "Network channel $id",
        channelNumber = "1", networkId = 1L, serviceId = 2L,
        type = "GR", isWatchable = true, isDisplay = true,
        programPresent = null, programFollowing = null, remocon_Id = 1
    )

    @Test
    fun noAvailableTabRendersExplicitEmptyState() {
        compose.setContent {
            KomorebiTheme {
                ChannelListOverlay(
                    groupedChannels = emptyMap(),
                    currentChannelId = "missing",
                    onChannelSelect = {},
                    getLogoUrl = { "" },
                    shouldCropLogo = false,
                    focusRequester = FocusRequester()
                )
            }
        }
        compose.onNodeWithText("表示可能なチャンネルがありません").assertIsDisplayed()
    }

    @Test
    fun absentCurrentChannelAttachesFocusRequesterToFirstAvailableChannel() {
        val requester = FocusRequester()
        compose.setContent {
            KomorebiTheme {
                ChannelListOverlay(
                    groupedChannels = mapOf("GR" to listOf(channel("edcb:full:1"), channel("edcb:full:2"))),
                    currentChannelId = "old-backend:missing",
                    onChannelSelect = {},
                    getLogoUrl = { "" },
                    shouldCropLogo = false,
                    focusRequester = requester
                )
            }
        }
        compose.onNodeWithText("Network channel edcb:full:1").assertIsDisplayed()
        compose.runOnIdle {
            // PR #3 fallback must attach the caller-supplied focus requester.
            assertTrue("missing current ID should still have a focus target", requester.requestFocus())
        }
        compose.onNode(hasClickAction() and (
            hasText("Network channel edcb:full:1") or
                hasAnyDescendant(hasText("Network channel edcb:full:1"))
        )).assertIsFocused()
    }
}
