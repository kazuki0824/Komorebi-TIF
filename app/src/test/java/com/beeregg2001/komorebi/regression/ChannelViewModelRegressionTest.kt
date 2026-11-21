package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests real ChannelViewModel fetch and backend-switch error handling.
 * The mocked provider models the upstream network response; no live tuner required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelViewModelRegressionTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun settings(backend: MutableStateFlow<String>): SettingsRepository {
        val result = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(result.backendType).thenReturn(backend)
        Mockito.`when`(result.hideSubChannels).thenReturn(flowOf(false))
        return result
    }

    private fun viewModel(live: LiveProvider, backend: MutableStateFlow<String>) =
        ChannelViewModel(
            live, Mockito.mock(RecordProvider::class.java),
            Mockito.mock(WatchHistoryRepository::class.java), settings(backend)
        )

    @Test
    fun successfulEmptyChannelListIsNotMisclassifiedAsConnectionFailure() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        Mockito.doReturn(ChannelApiResponse()).`when`(live).getChannels()
        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        try {
            vm.fetchChannels(force = true)
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertFalse(vm.connectionError.value)
            assertTrue(vm.groupedChannels.value.isEmpty())
            assertTrue(vm.liveRows.value.isEmpty())
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun changingBackendClearsPreviousTransportFailureAndAcceptsNewEmptyResult() = runBlocking {
        val live = Mockito.mock(LiveProvider::class.java)
        val shouldFail = AtomicBoolean(true)
        Mockito.doAnswer {
            if (shouldFail.get()) throw IllegalStateException("old backend unavailable")
            ChannelApiResponse()
        }.`when`(live).getChannels()

        val backend = MutableStateFlow("KONOMITV")
        val vm = viewModel(live, backend)
        try {
            vm.fetchChannels(force = true)
            withTimeout(10_000L) { vm.connectionError.first { it } }
            shouldFail.set(false)
            backend.value = "EDCB"
            withTimeout(10_000L) { vm.isLoading.first { !it } }
            assertFalse(vm.connectionError.value)
            assertTrue(vm.groupedChannels.value.isEmpty())
        } finally {
            vm.stopPolling()
            vm.viewModelScope.cancel()
        }
    }
}
