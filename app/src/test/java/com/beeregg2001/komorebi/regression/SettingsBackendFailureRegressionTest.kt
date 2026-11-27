package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.dao.SyncMetaDao
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationLiveRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failure injection against the production SettingsViewModel backend-selection path.
 * All unrelated DataStore getters use inert Flow stubs solely to construct the VM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackendFailureRegressionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var selectedBackend: MutableStateFlow<String>
    private lateinit var settingsRepositoryUnderTest: SettingsRepository
    private lateinit var sync: RecordSyncEngine
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        selectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "getBackendType" -> selectedBackend
                "getIsInitialized", "getHideSubChannels", "getReceiveBetaUpdates" -> flowOf(false)
                else -> {
                    if (Flow::class.java.isAssignableFrom(invocation.method.returnType)) flowOf("")
                    else Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        })
        settingsRepositoryUnderTest = settings
        sync = Mockito.mock(RecordSyncEngine::class.java)
        val db = Mockito.mock(AppDatabase::class.java)
        val recordedDao = Mockito.mock(RecordedProgramDao::class.java)
        Mockito.`when`(recordedDao.getTotalCountFlow()).thenReturn(flowOf(0))
        Mockito.`when`(db.recordedProgramDao()).thenReturn(recordedDao)
        val syncDao = Mockito.mock(SyncMetaDao::class.java)
        Mockito.`when`(syncDao.getSyncMetaFlow()).thenReturn(flowOf(null))
        Mockito.`when`(db.syncMetaDao()).thenReturn(syncDao)
        val updater = Mockito.mock(AppUpdater::class.java)
        Mockito.`when`(updater.updateState).thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        viewModel = SettingsViewModel(
            ApplicationProvider.getApplicationContext<Context>(),
            settings, sync, Mockito.mock(RecordProvider::class.java),
            Mockito.mock(EpgStationLiveRepository::class.java), updater, db
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun failureBeforeSettingsCommitRetainsOriginalBackendAndReportsRollback() = runBlocking {
        Mockito.doAnswer { throw IllegalStateException("injected before persistence") }
            .`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        viewModel.updateBackendType("EDCB")
        val error = withTimeout(15_000L) { viewModel.backendSwitchError.first { it != null } }
        assertTrue(error!!.contains("元の設定"))
        assertEquals("KONOMITV", selectedBackend.value)
        Mockito.verify(sync, Mockito.never()).launchSyncAllRecords(forceFullSync = true)
    }

    @Test
    fun failureAfterSettingsCommitReportsPartialSwitchAndRequestsFullResync() = runBlocking {
        Mockito.doAnswer {
            selectedBackend.value = "EDCB"
            throw IllegalStateException("injected after setting persistence")
        }.`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        viewModel.updateBackendType("EDCB")
        val error = withTimeout(15_000L) { viewModel.backendSwitchError.first { it != null } }
        assertTrue(error!!.contains("切替済み"))
        assertTrue(error.contains("再同期"))
        assertEquals("EDCB", selectedBackend.value)
        Mockito.verify(sync, Mockito.timeout(10_000L).times(1))
            .launchSyncAllRecords(forceFullSync = true)
    }
    @Test
    fun selectingAlreadyActiveBackendDoesNotStartAnotherSwitch() = runBlocking {
        viewModel.updateBackendType("KONOMITV")
        // Wait for the ViewModel's IO coroutine to acquire/release its mutex.
        val mutexField = SettingsViewModel::class.java.getDeclaredField("backendSelectionMutex")
        mutexField.isAccessible = true
        val mutex = mutexField.get(viewModel) as kotlinx.coroutines.sync.Mutex
        withTimeout(15_000L) { mutex.lock(); mutex.unlock() }
        Mockito.verifyNoInteractions(sync)
        assertEquals("KONOMITV", selectedBackend.value)
    }

    @Test
    fun twoConsecutiveSelectionsOfSameBackendDoNotDoubleSwitchAfterCommit() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            selectedBackend.value = "EDCB"
            completed.incrementAndGet()
            null
        }.`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        viewModel.updateBackendType("EDCB")
        assertTrue("first switch never started", entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
        viewModel.updateBackendType("EDCB")
        release.countDown()
        val mutexField = SettingsViewModel::class.java.getDeclaredField("backendSelectionMutex")
        mutexField.isAccessible = true
        val mutex = mutexField.get(viewModel) as kotlinx.coroutines.sync.Mutex
        withTimeout(15_000L) { mutex.lock(); mutex.unlock() }
        assertEquals("EDCB", selectedBackend.value)
        assertEquals(1, completed.get())
        Mockito.verify(sync, Mockito.times(1))
            .switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
    }

    @Test
    fun confirmedBackendFlowUsesPersistedSelectionWithoutSyntheticStartupBackend() = runBlocking {
        selectedBackend.value = "EPGSTATION"
        assertEquals(
            "EPGSTATION",
            viewModel.confirmedBackendType.first()
        )
        selectedBackend.value = "EDCB"
        assertEquals(
            "EDCB",
            viewModel.confirmedBackendType.first()
        )
        Mockito.verify(settingsRepositoryUnderTest, Mockito.atLeastOnce()).backendType
        Mockito.verifyNoInteractions(sync)
    }

    @Test
    fun successfulRetryClearsPriorBackendSwitchErrorAndRequestsExactlyOneFullSync() = runBlocking {
        val calls = AtomicInteger()
        Mockito.doAnswer {
            if (calls.incrementAndGet() == 1) {
                throw IllegalStateException("initial setting write refused")
            }
            selectedBackend.value = "EDCB"
            null
        }.`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        viewModel.updateBackendType("EDCB")
        withTimeout(10_000L) { viewModel.backendSwitchError.first { it != null } }
        assertEquals("KONOMITV", selectedBackend.value)

        viewModel.updateBackendType("EDCB")
        Mockito.verify(sync, Mockito.timeout(10_000L).times(1))
            .launchSyncAllRecords(forceFullSync = true)
        assertNull("successful retry must clear old error", viewModel.backendSwitchError.value)
        assertEquals("EDCB", viewModel.confirmedBackendType.first())
        assertEquals(2, calls.get())
    }

    @Test
    fun repeatedSelectionOfPersistedBackendDoesNotReissueExistingErrorOrSync() = runBlocking {
        Mockito.doAnswer { throw IllegalStateException("cannot switch") }
            .`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        viewModel.updateBackendType("EDCB")
        val reported = withTimeout(10_000L) { viewModel.backendSwitchError.first { it != null } }
        viewModel.updateBackendType("KONOMITV")
        val mutexField = SettingsViewModel::class.java.getDeclaredField("backendSelectionMutex")
        mutexField.isAccessible = true
        val mutex = mutexField.get(viewModel) as kotlinx.coroutines.sync.Mutex
        withTimeout(10_000L) { mutex.lock(); mutex.unlock() }
        assertEquals("KONOMITV", selectedBackend.value)
        assertEquals(reported, viewModel.backendSwitchError.value)
        Mockito.verify(sync, Mockito.times(1))
            .switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        Mockito.verify(sync, Mockito.never()).launchSyncAllRecords(forceFullSync = true)
    }


    @Test
    fun realSettingsSaveCallbackRunsBeforeForcedSyncAndDifferentSelectionsSerialize() = runBlocking {
        val firstCommitted = CompletableDeferred<Unit>()
        val firstExit = CompletableDeferred<Unit>()
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        Mockito.doAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as String
            if (key == SettingsRepository.BACKEND_TYPE) {
                order.add("save:$value")
                selectedBackend.value = value
            }
            Unit
        }.`when`(settingsRepositoryUnderTest)
            .saveString(Mockito.eq(SettingsRepository.BACKEND_TYPE) ?: SettingsRepository.BACKEND_TYPE,
                Mockito.anyString() ?: "EDCB")
        val calls = AtomicInteger(0)
        Mockito.doAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val commit = invocation.arguments[0] as suspend () -> Unit
            runBlocking { commit() }
            if (call == 1) {
                firstCommitted.complete(Unit)
                runBlocking { firstExit.await() }
            }
            order.add("switch-commit:$call")
            null
        }.`when`(sync).switchBackend(Mockito.any<suspend () -> Unit>() ?: {})
        Mockito.doAnswer { order.add("full-sync"); null }
            .`when`(sync).launchSyncAllRecords(forceFullSync = true)
        viewModel.updateBackendType("EDCB")
        withTimeout(10_000L) { firstCommitted.await() }
        viewModel.updateBackendType("EPGSTATION")
        firstExit.complete(Unit)
        Mockito.verify(sync, Mockito.timeout(10_000L).times(2))
            .launchSyncAllRecords(forceFullSync = true)
        assertEquals("EPGSTATION", selectedBackend.value)
        assertEquals(2, calls.get())
        assertEquals(listOf(
            "save:EDCB", "switch-commit:1", "full-sync",
            "save:EPGSTATION", "switch-commit:2", "full-sync"
        ), order.toList())
    }

}
