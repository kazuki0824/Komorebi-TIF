package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** Verifies the production EpgRepository, not a reimplemented search filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgSearchBackendRegressionTest {
    @Suppress("UNCHECKED_CAST")
    private fun cache(repo: EpgRepository): MutableMap<String, List<EpgChannelWrapper>> =
        EpgRepository::class.java.getDeclaredField("memoryCache").let { field ->
            field.isAccessible = true
            field.get(repo) as ConcurrentHashMap<String, List<EpgChannelWrapper>>
        }

    private fun program(title: String): EpgProgram {
        val p = Mockito.mock(EpgProgram::class.java)
        Mockito.`when`(p.title).thenReturn(title)
        Mockito.`when`(p.start_time).thenReturn(OffsetDateTime.now().plusDays(2).toString())
        return p
    }

    private fun wrapper(channelName: String, title: String): EpgChannelWrapper {
        val channel = Mockito.mock(EpgChannel::class.java)
        Mockito.`when`(channel.name).thenReturn(channelName)
        val wrap = Mockito.mock(EpgChannelWrapper::class.java)
        Mockito.`when`(wrap.channel).thenReturn(channel)
        val mockedProgram = program(title)
        Mockito.`when`(wrap.programs).thenReturn(listOf(mockedProgram))
        return wrap
    }

    private fun repository(backend: String): EpgRepository {
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf(backend))
        return EpgRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            Mockito.mock(DtvProviderProxy::class.java),
            Mockito.mock(EpgCacheDao::class.java),
            Gson(),
            settings
        )
    }

    @Test
    fun searchNeverReturnsOtherBackendsCachedPrograms() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(wrapper("channel one", "needle-own"))
        cache(repo)["EDCB_GR"] = listOf(wrapper("channel two", "needle-foreign"))
        cache(repo)["EPGSTATION_GR"] = listOf(wrapper("channel three", "needle-foreign-2"))

        val hits = repo.searchFuturePrograms(query = "needle")
        assertEquals(listOf("needle-own"), hits.map { it.program.title })
    }

    @Test
    fun searchingWithNoMatchingBackendEntriesIsEmptyNotStale() = runBlocking {
        val repo = repository("EPGSTATION")
        cache(repo)["KONOMITV_GR"] = listOf(wrapper("channel one", "needle"))
        cache(repo)["EDCB_GR"] = listOf(wrapper("channel two", "needle"))
        assertTrue(repo.searchFuturePrograms(query = "needle").isEmpty())
    }

    @Test
    fun blankSearchWithNoFiltersMustReturnEmpty() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(wrapper("channel one", "needle"))
        assertTrue(repo.searchFuturePrograms(query = "").isEmpty())
    }
    @Test
    fun andSearchRequiresEveryKeywordWithinTheSameProgram() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(
            wrapper("NHK", "baseball live"),
            wrapper("NHK", "baseball digest"),
            wrapper("NHK", "soccer live")
        )
        assertEquals(listOf("baseball live"),
            repo.searchFuturePrograms(query = "baseball live").map { it.program.title })
    }

    @Test
    fun commaDelimitedOrSearchAcceptsEitherKeyword() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(
            wrapper("NHK", "baseball live"),
            wrapper("NHK", "soccer live"),
            wrapper("NHK", "drama")
        )
        assertEquals(listOf("baseball live", "soccer live"),
            repo.searchFuturePrograms(query = "baseball,soccer").map { it.program.title })
    }

    @Test
    fun channelNameRestrictionDoesNotLeakOtherChannels() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(
            wrapper("Tokyo One", "news"),
            wrapper("Osaka Two", "news")
        )
        assertEquals(listOf("Tokyo One"),
            repo.searchFuturePrograms(query = "news", channelName = "TOKYO")
                .map { it.channel.name })
    }

    @Test
    fun backendSelectionDoesNotLoseOtherChannelTypesOfTheSelectedBackend() = runBlocking {
        val repo = repository("KONOMITV")
        cache(repo)["KONOMITV_GR"] = listOf(wrapper("ground", "news one"))
        cache(repo)["KONOMITV_BS"] = listOf(wrapper("satellite", "news two"))
        cache(repo)["EDCB_GR"] = listOf(wrapper("foreign", "news three"))
        assertEquals(setOf("news one", "news two"),
            repo.searchFuturePrograms(query = "news").map { it.program.title }.toSet())
    }


    @Test
    fun sameRepositorySwitchesSearchScopeWithCurrentBackend() = runBlocking {
        val selected = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selected)
        val repo = EpgRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            Mockito.mock(DtvProviderProxy::class.java),
            Mockito.mock(EpgCacheDao::class.java),
            Gson(),
            settings
        )
        cache(repo)["KONOMITV_GR"] = listOf(wrapper("Network A", "needle-kon"))
        cache(repo)["EDCB_GR"] = listOf(wrapper("Network B", "needle-edcb"))
        assertEquals(listOf("needle-kon"), repo.searchFuturePrograms(query = "needle").map { it.program.title })
        selected.value = "EDCB"
        assertEquals(listOf("needle-edcb"), repo.searchFuturePrograms(query = "needle").map { it.program.title })
        selected.value = "KONOMITV"
        assertEquals(listOf("needle-kon"), repo.searchFuturePrograms(query = "needle").map { it.program.title })
    }

}
