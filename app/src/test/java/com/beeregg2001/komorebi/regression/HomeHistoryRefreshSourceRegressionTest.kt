package com.beeregg2001.komorebi.regression

import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Production HomeViewModel must attach the fetched backend to both history lookup and save. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeHistoryRefreshSourceRegressionTest {
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var konomi: KonomiRepository
    private lateinit var api: KonomiApi
    private lateinit var history: WatchHistoryRepository
    private lateinit var vm: HomeViewModel
    private val lookupOwners = CopyOnWriteArrayList<String>()
    private val saved = CopyOnWriteArrayList<Pair<String, List<WatchHistoryEntity>>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        backend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        Mockito.`when`(settings.homePickupGenre).thenReturn(flowOf("アニメ"))
        Mockito.`when`(settings.homePickupTime).thenReturn(flowOf("昼"))
        Mockito.`when`(settings.excludePaidBroadcasts).thenReturn(flowOf("OFF"))
        Mockito.`when`(settings.favoriteBaseballTeams).thenReturn(flowOf("[]"))
        api = Mockito.mock(KonomiApi::class.java)
        konomi = KonomiRepository(api, settings)
        history = Mockito.mock(WatchHistoryRepository::class.java, Answer { call ->
            when (call.method.name) {
                "getOwnedLocalWatchHistory" -> flowOf("KONOMITV" to emptyList<WatchHistoryEntity>())
                "getLastChannels" -> flowOf(emptyList<Any>())
                "getHistoryEntitiesByIds" -> {
                    lookupOwners.add(call.arguments[1] as String)
                    emptyList<WatchHistoryEntity>()
                }
                "saveAllToLocalHistory" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rows = call.arguments[0] as List<WatchHistoryEntity>
                    saved.add(call.arguments[1] as String to rows)
                    Unit
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        })
        val epg = Mockito.mock(EpgRepository::class.java, Answer { call ->
            if (call.method.name == "getEpgDataStream")
                flowOf(Result.success(emptyList<EpgChannelWrapper>()))
            else Answers.RETURNS_DEFAULTS.answer(call)
        })
        val updater = Mockito.mock(AppUpdater::class.java)
        Mockito.`when`(updater.updateState).thenReturn(MutableStateFlow<UpdateState>(UpdateState.Idle))
        vm = HomeViewModel(
            Mockito.mock(LiveProvider::class.java), konomi, epg,
            settings, Mockito.mock(LastChannelRepository::class.java),
            history, updater
        )
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun awaitRefresh() {
        val field = HomeViewModel::class.java.getDeclaredField("homeRefreshJob")
        field.isAccessible = true
        withTimeout(15_000L) {
            while (field.get(vm) !is Job) kotlinx.coroutines.yield()
            (field.get(vm) as Job).join()
        }
    }

    private fun sampleHistory() = KonomiHistoryProgram(
        program = KonomiProgram(
            id = "42", title = "Original Konomi Recording", description = "",
            detail = null, start_time = "2026-11-20T00:00:00+09:00",
            end_time = "2026-11-20T01:00:00+09:00", channel_id = "gr-1"
        ),
        playback_position = 8.0, last_watched_at = "2026-11-21T00:00:00+09:00"
    )

    @Test
    fun refreshHistoryFetchAndSaveUseSameOriginalBackendEvenAfterSelectionChanges() = runBlocking {
        val reachedProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        Mockito.doAnswer {
            reachedProvider.countDown()
            check(releaseProvider.await(12, TimeUnit.SECONDS))
            listOf(sampleHistory())
        }.`when`(api).getWatchHistory()
        try {
            vm.refreshHomeData(force = true)
            assertTrue("history fetch did not begin", reachedProvider.await(12, TimeUnit.SECONDS))
            backend.value = "EDCB"
            releaseProvider.countDown()
            awaitRefresh()
            assertEquals(listOf("KONOMITV"), lookupOwners.toList())
            assertEquals(1, saved.size)
            assertEquals("KONOMITV", saved.single().first)
            assertEquals(42, saved.single().second.single().id)
            assertEquals("Original Konomi Recording", saved.single().second.single().title)
        } finally {
            releaseProvider.countDown()
        }
    }

    @Test
    fun edcbRefreshDoesNotImportKonomiWatchHistoryAtAll() = runBlocking {
        backend.value = "EDCB"
        vm.refreshHomeData(force = true)
        awaitRefresh()
        Mockito.verify(api, Mockito.never()).getWatchHistory()
        assertTrue(saved.isEmpty())
        assertTrue(lookupOwners.isEmpty())
    }
}
