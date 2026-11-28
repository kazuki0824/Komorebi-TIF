package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.setting.SettingsScreen
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import org.mockito.stubbing.Answer
import org.mockito.MockedStatic
import org.mockito.MockedConstruction
import org.mockito.AdditionalAnswers
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
    private val backendDialogSelection = AtomicReference<((String) -> Unit)?>(null)
    private var dialogMock: MockedStatic<Any>? = null
    private var uiSettingsConstruction: MockedConstruction<SettingsRepository>? = null
    private var scopeMock: MockedStatic<Any>? = null
    private val screenScope = AtomicReference<CoroutineScope?>()

    @After
    fun clean() = runBlocking {
        compose.runOnUiThread {
            dialogMock?.close(); dialogMock = null
            scopeMock?.close(); scopeMock = null
            uiSettingsConstruction?.close(); uiSettingsConstruction = null
        }
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

        // Keep the actual SettingsScreen selection callback, but replace the
        // animated TV dialog with a captured callback (not the persistence path).
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val owner = Class.forName(
                "com.beeregg2001.komorebi.ui.setting.SettingComponentsKt"
            ) as Class<Any>
            dialogMock = Mockito.mockStatic(owner, Answer { invocation ->
                if (invocation.method.name == "SelectionDialog") {
                    @Suppress("UNCHECKED_CAST")
                    backendDialogSelection.set(invocation.arguments[4] as (String) -> Unit)
                    null
                } else invocation.callRealMethod()
            })
        }
        // rememberCoroutineScope() is inline in Compose, so intercept its
        // non-inline createCompositionCoroutineScope() factory instead.
        // Jobs launched on Dispatchers.IO are still its children and can be joined.
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val effects = Class.forName("androidx.compose.runtime.EffectsKt") as Class<Any>
            scopeMock = Mockito.mockStatic(effects, Answer { invocation ->
                val value = invocation.callRealMethod()
                if (invocation.method.name == "createCompositionCoroutineScope" &&
                    value is CoroutineScope &&
                    Thread.currentThread().stackTrace.any {
                        it.className.contains("SettingScreenKt")
                    }) {
                    screenScope.compareAndSet(null, value)
                }
                value
            })
        }
        // Intercept SettingsRepository constructed inside the real screen.
        // Delegate all production reads to the real store, but count any independent UI writes.
        compose.runOnUiThread {
            uiSettingsConstruction = Mockito.mockConstruction(
                SettingsRepository::class.java,
                Mockito.withSettings().defaultAnswer(AdditionalAnswers.delegatesTo<Any>(store))
            )
        }
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
        backendItem.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000L) { backendDialogSelection.get() != null }
        val activeScope = requireNotNull(screenScope.get()) {
            "must observe the exact SettingsScreen rememberCoroutineScope"
        }
        val owningJob = requireNotNull(activeScope.coroutineContext[Job])
        val selectionJobs = AtomicReference<List<Job>>(emptyList())
        // Take both snapshots inside a single UI-idle callback. No unrelated
        // recomposition can add long-lived collectors between the snapshots.
        compose.runOnIdle {
            val before = owningJob.children.toSet()
            backendDialogSelection.get()!!.invoke("EDCB")
            selectionJobs.set(owningJob.children.filter { it !in before }.toList())
        }
        Mockito.verify(vm, Mockito.timeout(10_000L).times(1)).updateBackendType("EDCB")
        // The only relevant Dispatchers.IO jobs are those launched by the
        // selection callback itself; joining all screen descendants can hang
        // on collectors intentionally alive throughout the composition.
        withTimeout(10_000L) { selectionJobs.get().joinAll() }
        compose.waitForIdle()
        val created = requireNotNull(uiSettingsConstruction).constructed()
        org.junit.Assert.assertTrue("the real screen must construct its repository", created.isNotEmpty())
        created.forEach { uiStore ->
            Mockito.verify(uiStore, Mockito.never()).saveString(
                SettingsRepository.BACKEND_TYPE, "EDCB"
            )
        }
        assertEquals("KONOMITV", store.backendType.first())
    }
}
