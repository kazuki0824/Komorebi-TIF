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
import kotlinx.coroutines.flow.first
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
    private lateinit var epg: EpgRepository
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
        Mockito.`when`(settings.favoriteBaseballTeams).thenReturn(flowOf("[\"巨人\"]"))
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        Mockito.`when`(history.getOwnedLocalWatchHistory())
            .thenReturn(flowOf("KONOMITV" to emptyList()))
        Mockito.`when`(history.getLastChannels()).thenReturn(flowOf(emptyList()))
        epg = Mockito.mock(EpgRepository::class.java)
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
        val live = Mockito.mock(LiveProvider::class.java)
        runBlocking { Mockito.doReturn("baseball-logo").`when`(live).getChannelLogoUrl("channel-1") }
        vm = HomeViewModel(
            live,
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
    private fun baseballTomorrow() {
        val now = OffsetDateTime.now()
        val base = now.withHour(4).withMinute(0).withSecond(0).withNano(0).let {
            if (now.hour < 4) it.minusDays(1) else it
        }
        val tomorrow = base.plusDays(1).plusHours(6)
        program = program.copy(
            id = "baseball-tomorrow", title = "巨人 vs 阪神 生中継",
            description = "巨人公式戦", genres = listOf(EpgGenre("スポーツ", "野球")),
            start_time = tomorrow.toString(), end_time = tomorrow.plusHours(3).toString()
        )
    }

    @Test
    fun dateChangedWhileEpgFetchIsBlockedUsesLatestDateWhenPublishingBaseball() = runBlocking {
        baseballTomorrow()
        withTimeout(10_000L) { vm.favoriteBaseballTeams.first { "巨人" in it } }
        val job = startAndGetJob()
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        vm.updateBaseballDateOffset(1)
        release.countDown()
        withTimeout(15_000L) { job.join() }
        assertEquals(1, vm.baseballDateOffset.value)
        val items = vm.favoriteBaseballGames.value
        assertEquals(listOf("巨人"), items.map { it.first })
        assertEquals(listOf("baseball-tomorrow"), items.single().second.map { it.program.id })
    }

    @Test
    fun changingDateAfterFirstFetchReusesCachedBaseballProgramWithoutOldDayLeak() = runBlocking {
        baseballTomorrow()
        withTimeout(10_000L) { vm.favoriteBaseballTeams.first { "巨人" in it } }
        val job = startAndGetJob()
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(15_000L) { job.join() }
        assertTrue("today has no fixture game", vm.favoriteBaseballGames.value.isEmpty())
        vm.updateBaseballDateOffset(1)
        withTimeout(10_000L) { vm.favoriteBaseballGames.first { it.any { pair -> pair.first == "巨人" } } }
        assertEquals("baseball-tomorrow", vm.favoriteBaseballGames.value.single().second.single().program.id)
        vm.updateBaseballDateOffset(0)
        withTimeout(10_000L) { vm.favoriteBaseballGames.first { it.isEmpty() } }
        assertEquals(0, vm.baseballDateOffset.value)
    }


    @Test
    fun switchingBackendRevokesAlreadyPublishedGenreAndBaseballCardsBeforeNewFetch() = runBlocking {
        baseballTomorrow()
        withTimeout(10_000L) { vm.favoriteBaseballTeams.first { "巨人" in it } }
        val completed = startAndGetJob()
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(15_000L) { completed.join() }
        assertTrue("existing backend must have visible genre cards", vm.genrePickupPrograms.value.isNotEmpty())
        vm.updateBaseballDateOffset(1)
        withTimeout(10_000L) {
            vm.favoriteBaseballGames.first { it.isNotEmpty() }
        }
        assertTrue("existing backend must have visible baseball cards", vm.favoriteBaseballGames.value.isNotEmpty())
        backend.value = "EDCB"
        withTimeout(10_000L) {
            vm.genrePickupPrograms.first { it.isEmpty() }
            vm.favoriteBaseballGames.first { it.isEmpty() }
        }
        assertTrue("old genre cards must vanish on backend change", vm.genrePickupPrograms.value.isEmpty())
        assertTrue("old baseball cards must vanish on backend change", vm.favoriteBaseballGames.value.isEmpty())
        val field = HomeViewModel::class.java.getDeclaredField("cachedBaseballPrograms")
            .apply { isAccessible = true }
        assertTrue((field.get(vm) as List<*>).isEmpty())
    }


    @Test
    fun switchingBackendClearsVisibleGenreThenAutomaticallyFetchesReplacementOwner() = runBlocking {
        val oldFetch = startAndGetJob()
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(15_000L) { oldFetch.join() }
        assertEquals(listOf("own-program"), vm.genrePickupPrograms.value.map { it.first.id })
        val replacement = program.copy(id = "new-edcb-program", title = "アニメ新バックエンド")
        val ch = EpgChannel(
            id = "edcb-1", display_channel_id = "edcb-1", network_id = 2,
            service_id = 2, transport_stream_id = 2, remocon_id = 2,
            channel_number = "2", type = "GR", name = "New owner",
            jikkyo_force = null, is_subchannel = false, is_radiochannel = false,
            is_watchable = true
        )
        Mockito.doReturn(flowOf(Result.success(listOf(EpgChannelWrapper(ch, listOf(replacement))))))
            .`when`(epg).getEpgDataStream(
                Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.now(),
                Mockito.any(OffsetDateTime::class.java) ?: OffsetDateTime.now(),
                Mockito.eq("GR") ?: "GR"
            )
        backend.value = "EDCB"
        withTimeout(10_000L) { vm.genrePickupPrograms.first { it.isEmpty() } }
        assertTrue(vm.favoriteBaseballGames.value.isEmpty())
        withTimeout(15_000L) {
            vm.genrePickupPrograms.first {
                it.any { pair -> pair.first.id == "new-edcb-program" }
            }
        }
        assertTrue(vm.genrePickupPrograms.value.none { it.first.id == "own-program" })
        assertTrue(vm.genrePickupPrograms.value.all { it.first.id == "new-edcb-program" })
    }

}
