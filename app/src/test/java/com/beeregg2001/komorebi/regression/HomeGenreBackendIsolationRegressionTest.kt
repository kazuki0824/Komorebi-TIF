package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgGenre
import com.beeregg2001.komorebi.data.model.EpgProgram
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tests the actual asynchronous HomeViewModel genre-pickup publication guard. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeGenreBackendIsolationRegressionTest {
    private val main = UnconfinedTestDispatcher()
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var vm: HomeViewModel
    private lateinit var entered: CountDownLatch
    private lateinit var release: CountDownLatch
    private lateinit var program: EpgProgram

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        backend = MutableStateFlow("KONOMITV")
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
        val future = OffsetDateTime.now().plusMinutes(5)
        val slot = when (future.hour) {
            in 5..10 -> "朝"
            in 11..17 -> "昼"
            else -> "夜"
        }
        program = EpgProgram(
            id = "own-program", channel_id = "channel-1", network_id = 1,
            service_id = 1, event_id = 2, title = "アニメ回帰検証番組",
            description = "test", detail = null,
            start_time = future.toString(), end_time = future.plusHours(1).toString(),
            duration = 3600, is_free = true, genres = listOf(EpgGenre("アニメ", "一般")),
            video_type = null, audio_type = null, audio_sampling_rate = null
        )
        val channel = EpgChannel(
            id = "channel-1", display_channel_id = "channel-1", network_id = 1,
            service_id = 1, transport_stream_id = 1, remocon_id = 1,
            channel_number = "1", type = "GR", name = "Network TV",
            jikkyo_force = null, is_subchannel = false, is_radiochannel = false,
            is_watchable = true
        )
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        Mockito.`when`(settings.homePickupGenre).thenReturn(flowOf("アニメ"))
        Mockito.`when`(settings.homePickupTime).thenReturn(flowOf(slot))
        Mockito.`when`(settings.excludePaidBroadcasts).thenReturn(flowOf("OFF"))
        Mockito.`when`(settings.favoriteBaseballTeams).thenReturn(flowOf("[]"))
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        Mockito.`when`(history.getOwnedLocalWatchHistory())
            .thenReturn(flowOf("KONOMITV" to emptyList()))
        Mockito.`when`(history.getLastChannels()).thenReturn(flowOf(emptyList()))
        val epg = Mockito.mock(EpgRepository::class.java)
        Mockito.doAnswer { invocation ->
            if (invocation.arguments[2] == "GR") {
                flow {
                    withContext(Dispatchers.IO) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "GR fetch never released" }
                    }
                    emit(Result.success(listOf(EpgChannelWrapper(channel, listOf(program)))))
                }
            } else flowOf(Result.success(emptyList<EpgChannelWrapper>()))
        }.`when`(epg).getEpgDataStream(
            Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.now(),
            Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.now(),
            Mockito.anyString()
        )
        val updater = Mockito.mock(AppUpdater::class.java)
        Mockito.`when`(updater.updateState)
            .thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        vm = HomeViewModel(
            Mockito.mock(LiveProvider::class.java),
            Mockito.mock(KonomiRepository::class.java),
            epg, settings,
            Mockito.mock(LastChannelRepository::class.java),
            history, updater
        )
    }

    @After
    fun tearDown() {
        release.countDown()
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun startAndGetJob(): Job {
        val method = HomeViewModel::class.java.getDeclaredMethod(
            "fetchAllTypeGenrePickup", java.lang.Boolean.TYPE
        )
        method.isAccessible = true
        method.invoke(vm, true)
        val field = HomeViewModel::class.java.getDeclaredField("genrePickupJob")
        field.isAccessible = true
        return field.get(vm) as Job
    }

    @Test
    fun unchangedBackendPublishesFetchedGenreResults() = runBlocking {
        val job = startAndGetJob()
        assertTrue("provider was not reached", entered.await(15, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(15_000L) { job.join() }
        assertEquals(
            listOf("own-program"), vm.genrePickupPrograms.value.map { it.first.id }
        )
    }

    @Test
    fun switchedBackendMustNotPublishPreviousBackendGenreResults() = runBlocking {
        val job = startAndGetJob()
        assertTrue("provider was not reached", entered.await(15, TimeUnit.SECONDS))
        backend.value = "EDCB"
        release.countDown()
        withTimeout(15_000L) { job.join() }
        assertTrue("old backend's results leaked after switch", vm.genrePickupPrograms.value.isEmpty())
    }
}
