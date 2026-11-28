package com.beeregg2001.komorebi.regression

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.main.MainRootDialogs
import com.beeregg2001.komorebi.ui.main.MainRootState
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.AiConciergeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import org.mockito.MockedStatic
import org.mockito.stubbing.Answer
import org.junit.After

/** Direct UI assertion of PR #3 epoch bump on EPG ProgramDetail -> live selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class MainRootDialogEpochUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val selectedLive = AtomicReference<((EpgProgram) -> Unit)?>(null)
    private var programDialogMock: MockedStatic<Any>? = null

    @After fun teardown() {
        compose.runOnUiThread { programDialogMock?.close(); programDialogMock = null }
    }

    @Test
    fun epgProgramLiveSelectionAdvancesRecordingEpochBeforePublishingNewLiveChannel() {
        val channel = Channel(
            id = "edcb:gr:1", displayChannelId = "101",
            name = "network channel", channelNumber = "101",
            networkId = 1L, serviceId = 101L, transportStreamId = 500L,
            type = "GR", isWatchable = true, isDisplay = true,
            programPresent = null, programFollowing = null, remocon_Id = 1
        )
        val now = OffsetDateTime.now()
        val program = EpgProgram(
            id = "program-1", channel_id = channel.id,
            network_id = 1, service_id = 101, event_id = 1,
            title = "Current network broadcast", description = "",
            detail = null, start_time = now.minusMinutes(5).toString(),
            end_time = now.plusMinutes(55).toString(),
            duration = 3600, is_free = true, genres = null,
            video_type = null, audio_type = null, audio_sampling_rate = null
        )
        // Exercise MainRootDialogs onPlay callback directly; the child dialog's
        // focus-delay animation is unrelated to PR3's epoch invalidation.
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val klass = Class.forName(
                "com.beeregg2001.komorebi.ui.epg.ProgramDetailScreenKt"
            ) as Class<Any>
            programDialogMock = Mockito.mockStatic(klass, Answer { invocation ->
                if (invocation.method.name == "ProgramDetailScreen") {
                    @Suppress("UNCHECKED_CAST")
                    selectedLive.set(invocation.arguments[4] as (EpgProgram) -> Unit)
                    null
                } else invocation.callRealMethod()
            })
        }
        val state = MainRootState()
        state.epgSelectedProgram = program
        val epoch = mutableIntStateOf(7)
        val homeVm = Mockito.mock(HomeViewModel::class.java)
        val aiVm = Mockito.mock(AiConciergeViewModel::class.java)
        Mockito.`when`(aiVm.chatHistory).thenReturn(MutableStateFlow(emptyList()))
        compose.setContent {
            KomorebiTheme {
                MainRootDialogs(
                    state = state,
                    recordedSelectionEpoch = epoch,
                    channelViewModel = Mockito.mock(ChannelViewModel::class.java),
                    epgViewModel = Mockito.mock(EpgViewModel::class.java),
                    homeViewModel = homeVm,
                    recordViewModel = Mockito.mock(RecordViewModel::class.java),
                    reserveViewModel = Mockito.mock(ReserveViewModel::class.java),
                    aiConciergeViewModel = aiVm,
                    groupedChannels = mapOf("GR" to listOf(channel)),
                    reserves = emptyList(),
                    updateState = UpdateState.Idle,
                    timeFormat = "24H",
                    isSettingsInitialized = true, hasSyncError = false,
                    detailFocusRequester = FocusRequester(), apiKey = "",
                    onExitApp = {}, closeSettingsAndRefresh = {},
                    closeAiConcierge = {}, onGoToSettings = {}
                )
            }
        }
        compose.waitUntil(10_000L) { selectedLive.get() != null }
        compose.runOnIdle { selectedLive.get()!!.invoke(program) }
        compose.runOnIdle {
            assertEquals(8, epoch.intValue)
            assertSame(channel, state.selectedChannel)
            assertNull(state.epgSelectedProgram)
            assertEquals(channel.id, state.lastSelectedChannelId)
        }
        Mockito.verify(homeVm).saveLastChannel(channel)
    }
}
