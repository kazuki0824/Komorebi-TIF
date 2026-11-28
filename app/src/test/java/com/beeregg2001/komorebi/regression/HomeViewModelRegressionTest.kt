package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.LastChannelRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.stubbing.Answer
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real HomeViewModel entry points for startup channel selection and user history actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelRegressionTest {
    private val main = UnconfinedTestDispatcher()
    private lateinit var vm: HomeViewModel
    private lateinit var last: LastChannelRepository
    private val lastSaved = java.util.concurrent.atomic.AtomicReference<LastChannelEntity?>()

    @Before
    fun setup() {
        Dispatchers.setMain(main)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(MutableStateFlow("EDCB"))
        Mockito.`when`(settings.homePickupGenre).thenReturn(flowOf("アニメ"))
        Mockito.`when`(settings.homePickupTime).thenReturn(flowOf("自動"))
        Mockito.`when`(settings.excludePaidBroadcasts).thenReturn(flowOf("ON"))
        Mockito.`when`(settings.favoriteBaseballTeams).thenReturn(flowOf("[]"))
        Mockito.`when`(settings.receiveBetaUpdates).thenReturn(flowOf(false))
        lastSaved.set(null)
        last = Mockito.mock(LastChannelRepository::class.java, Answer { invocation ->
            if (invocation.method.name == "saveLastChannel") {
                lastSaved.set(invocation.arguments[0] as LastChannelEntity)
                Unit
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        Mockito.`when`(history.getOwnedLocalWatchHistory()).thenReturn(
            flowOf("EDCB" to emptyList())
        )
        Mockito.`when`(history.getLastChannels()).thenReturn(flowOf(emptyList()))
        val updater = Mockito.mock(AppUpdater::class.java)
        Mockito.`when`(updater.updateState).thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        vm = HomeViewModel(
            Mockito.mock(LiveProvider::class.java),
            Mockito.mock(KonomiRepository::class.java),
            Mockito.mock(EpgRepository::class.java),
            settings, last, history, updater
        )
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun row(id: String) = LastChannelEntity(
        channelId = id, name = id, type = "GR",
        channelNumber = "1", networkId = 1L, serviceId = 2L, updatedAt = 100L
    )

    @Test
    fun startupChannelIdsAreTakenFromValidatedRecentHistoryNotRawHistory() : Unit = runBlocking {
        val available = listOf("valid-a", "valid-b")
        Mockito.doReturn(listOf(row("valid-b"), row("valid-a")))
            .`when`(last).getRecentForChannels(available)
        assertEquals(listOf("valid-b", "valid-a"), vm.recentStartupChannelIds(available))
        Mockito.verify(last).getRecentForChannels(available)
    }

    @Test
    fun saveLastChannelPreservesBackendQualifiedFullChannelId() : Unit = runBlocking {
        val channel = Mockito.mock(Channel::class.java)
        Mockito.`when`(channel.id).thenReturn("edcb:network:service")
        Mockito.`when`(channel.name).thenReturn("EDCB test")
        Mockito.`when`(channel.type).thenReturn("GR")
        Mockito.`when`(channel.channelNumber).thenReturn("1")
        Mockito.`when`(channel.networkId).thenReturn(10L)
        Mockito.`when`(channel.serviceId).thenReturn(20L)
        vm.saveLastChannel(channel)
        val saved = kotlinx.coroutines.withTimeout(10_000L) {
            while (lastSaved.get() == null) kotlinx.coroutines.delay(10)
            lastSaved.get()!!
        }
        assertEquals("edcb:network:service", saved.channelId)
        assertEquals(10L, saved.networkId)
        assertEquals(20L, saved.serviceId)
    }

    @Test
    fun clearLastChannelsDelegatesToOnlyTheLastChannelRepository() : Unit = runBlocking {
        vm.clearLastChannelHistory()
        Mockito.verify(last, Mockito.timeout(10_000L)).clearLastChannels()
    }
}
