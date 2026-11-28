package com.beeregg2001.komorebi.regression

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.ui.home.VideoTabContent
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Existing video tab must expose SMB navigation without a network recording history. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoTabSmbNavigationUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun smbLibraryIsReachableWhenThereAreNoRecordingsOrHistory() {
        val records = Mockito.mock(RecordViewModel::class.java)
        Mockito.`when`(records.recentRecordings)
            .thenReturn(MutableStateFlow(emptyList<RecordedProgram>()))
        Mockito.`when`(records.groupedSeries).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(records.availableGenres).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.selectedSeriesGenre).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.programDetail).thenReturn(MutableStateFlow(null))
        val settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(MutableStateFlow("EDCB"))
        val selectedSmb = AtomicInteger()
        val selectedRecorded = AtomicInteger()
        compose.setContent {
            KomorebiTheme {
                VideoTabContent(
                    konomiIp = "", konomiPort = "",
                    tabFocusRequester = FocusRequester(),
                    contentFirstItemRequester = FocusRequester(),
                    onProgramClick = { selectedRecorded.incrementAndGet() },
                    onShowAllRecordings = {},
                    onShowSeriesList = {},
                    openedSeriesTitle = null,
                    onOpenedSeriesTitleChange = {},
                    recordViewModel = records,
                    settingViewModel = settings,
                    watchHistory = emptyList(),
                    onShowSmbLibrary = { selectedSmb.incrementAndGet() }
                )
            }
        }
        compose.onNodeWithText("ファイルライブラリ", substring = true).assertIsDisplayed()
        val smbButton = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("ファイルライブラリ")),
            useUnmergedTree = true
        )
        smbButton.performSemanticsAction(SemanticsActions.RequestFocus)
        smbButton.performKeyInput { pressKey(Key.DirectionCenter) }
        if (selectedSmb.get() == 0) println("SMB button semantics:\n" + compose.onRoot(useUnmergedTree = true).printToString())
        compose.runOnIdle {
            assertEquals(1, selectedSmb.get())
            assertEquals(0, selectedRecorded.get())
        }
    }
    @Test
    @Config(qualifiers = "w1280dp-h720dp")
    fun historyCardClickInvokesVerifiedHistoryCallbackNotDirectRecordingPlayback() {
        val records = Mockito.mock(RecordViewModel::class.java)
        Mockito.`when`(records.recentRecordings)
            .thenReturn(MutableStateFlow(emptyList<RecordedProgram>()))
        Mockito.`when`(records.groupedSeries).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(records.availableGenres).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(records.selectedSeriesGenre).thenReturn(MutableStateFlow(null))
        Mockito.`when`(records.programDetail).thenReturn(MutableStateFlow(null))
        val settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(MutableStateFlow("EPGSTATION"))
        val history = KonomiHistoryProgram(
            program = KonomiProgram(
                id = "42", title = "History verification sentinel",
                description = "", detail = null,
                start_time = "2026-11-20T00:00:00+09:00",
                end_time = "2026-11-20T01:00:00+09:00",
                channel_id = "network-42"
            ),
            playback_position = 30.0,
            last_watched_at = "2026-11-21T00:00:00+09:00"
        )
        var delegated: KonomiHistoryProgram? = null
        val directRecordedPlayback = AtomicInteger()
        compose.setContent {
            KomorebiTheme {
                VideoTabContent(
                    konomiIp = "", konomiPort = "",
                    tabFocusRequester = FocusRequester(),
                    contentFirstItemRequester = FocusRequester(),
                    onProgramClick = { directRecordedPlayback.incrementAndGet() },
                    onHistoryClick = { delegated = it },
                    onShowAllRecordings = {},
                    onShowSeriesList = {},
                    openedSeriesTitle = null,
                    onOpenedSeriesTitleChange = {},
                    recordViewModel = records,
                    settingViewModel = settings,
                    watchHistory = listOf(history)
                )
            }
        }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("続きから見る"))
        compose.onNodeWithText("History verification sentinel").assertIsDisplayed()
        val historyCard = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("History verification sentinel")),
            useUnmergedTree = true
        )
        historyCard.performSemanticsAction(SemanticsActions.RequestFocus)
        historyCard.performKeyInput { pressKey(Key.DirectionCenter) }
        if (delegated == null) println("History card semantics:\n" + compose.onRoot(useUnmergedTree = true).printToString())
        compose.runOnIdle {
            assertSame("history click must be delegated for owner validation", history, delegated)
            assertEquals("unverified history must not play immediately", 0, directRecordedPlayback.get())
        }
    }

}
