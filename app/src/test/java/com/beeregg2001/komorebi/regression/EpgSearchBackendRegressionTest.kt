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
}
