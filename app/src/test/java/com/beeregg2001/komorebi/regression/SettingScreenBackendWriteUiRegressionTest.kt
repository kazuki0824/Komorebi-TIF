package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise SettingsScreen's real backend selection; only SettingsViewModel owns persistence. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class SettingScreenBackendWriteUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SettingsRepository(context)

    @After
    fun clean() = runBlocking {
        store.saveString(SettingsRepository.BACKEND_TYPE, "KONOMITV")
    }

    @Test
    fun selectingBackendCallsViewModelWithoutAnyIndependentUiDataStoreWrite(): Unit = runBlocking {
        store.saveString(SettingsRepository.BACKEND_TYPE, "KONOMITV")
        val vm = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(vm.totalRecordCount).thenReturn(MutableStateFlow(0))
        Mockito.`when`(vm.lastSyncedAt).thenReturn(MutableStateFlow(0L))
        Mockito.`when`(vm.receiveBetaUpdates).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.updateCheckState).thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        Mockito.`when`(vm.hasManuallyCheckedForUpdate).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.isValidatingGeminiApiKey).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.playerUiMode).thenReturn(MutableStateFlow("NORMAL"))
        Mockito.`when`(vm.autoCmSkip).thenReturn(MutableStateFlow("OFF"))
        Mockito.`when`(vm.availableQualities).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.liveAvailableQualities).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.smbServerAddedEvent).thenReturn(MutableSharedFlow<String>())
        val channelVm = Mockito.mock(ChannelViewModel::class.java)
        Mockito.`when`(channelVm.groupedChannels).thenReturn(MutableStateFlow(emptyMap()))

        compose.setContent {
            KomorebiTheme {
                SettingsScreen(
                    onBack = {}, initialCategoryIndex = 1,
                    viewModel = vm, channelViewModel = channelVm
                )
            }
        }
        compose.onNodeWithText("利用するシステム").assertExists()
        val backendItem = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("利用するシステム")),
            useUnmergedTree = true
        )
        backendItem.performSemanticsAction(SemanticsActions.RequestFocus)
        backendItem.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasText("EDCB (EpgTimerSrv)"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val selectedOption = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("EDCB (EpgTimerSrv)")),
            useUnmergedTree = true
        )
        selectedOption.performSemanticsAction(SemanticsActions.RequestFocus)
        selectedOption.performKeyInput { pressKey(Key.DirectionCenter) }
        Mockito.verify(vm, Mockito.timeout(10_000L).times(1)).updateBackendType("EDCB")
        // The mock deliberately does NOT write. The previous UI's second write
        // would still have changed DataStore independently on Dispatchers.IO.
        delay(650L)
        assertEquals("KONOMITV", store.backendType.first())
    }
}
