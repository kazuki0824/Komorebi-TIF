package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises real EPG repository generation checks while the selected backend changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgInFlightBackendRegressionTest {
    private val start = OffsetDateTime.parse("2026-12-01T04:00:00+09:00")
    private val end = OffsetDateTime.parse("2026-12-02T04:00:00+09:00")

    private data class Fixture(
        val repo: EpgRepository,
        val backend: MutableStateFlow<String>,
        val enteredProvider: CountDownLatch,
        val releaseProvider: CountDownLatch
    )

    private fun fixture(): Fixture = runBlocking {
        val backend = MutableStateFlow("KONOMITV")
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        val provider = Mockito.mock(DtvProviderProxy::class.java)
        val startStr = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val endStr = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        Mockito.doAnswer {
            enteredProvider.countDown()
            check(releaseProvider.await(15, TimeUnit.SECONDS)) { "test release timed out" }
            emptyList<EpgChannelWrapper>()
        }.`when`(provider).getEpgProgramsForBackend("KONOMITV", startStr, endStr, "GR")
        val repo = EpgRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            provider, Mockito.mock(EpgCacheDao::class.java), Gson(), settings
        )
        Fixture(repo, backend, enteredProvider, releaseProvider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun memoryCache(repo: EpgRepository): Map<String, List<EpgChannelWrapper>> {
        val field = EpgRepository::class.java.getDeclaredField("memoryCache")
        field.isAccessible = true
        return field.get(repo) as ConcurrentHashMap<String, List<EpgChannelWrapper>>
    }

    @Test
    fun streamDoesNotPublishResultFromPreviousBackendAfterSwitch() = runBlocking {
        val f = fixture()
        try {
            val result = async(Dispatchers.IO) {
                f.repo.getEpgDataStream(start, end, "GR").toList()
            }
            assertTrue("provider call never started", f.enteredProvider.await(15, TimeUnit.SECONDS))
            f.backend.value = "EDCB"
            f.releaseProvider.countDown()
            val values = withTimeout(15_000L) { result.await() }
            assertTrue("old backend result must not publish", values.isEmpty())
            assertFalse(memoryCache(f.repo).containsKey("KONOMITV_GR"))
        } finally {
            f.releaseProvider.countDown()
        }
    }

    @Test
    fun silentPrefetchDoesNotWritePreviousBackendResultAfterSwitch() = runBlocking {
        val f = fixture()
        try {
            val result = async(Dispatchers.IO) {
                f.repo.fetchAndCacheEpgDataSilently(start, end, "GR")
            }
            assertTrue("provider call never started", f.enteredProvider.await(15, TimeUnit.SECONDS))
            f.backend.value = "EDCB"
            f.releaseProvider.countDown()
            withTimeout(15_000L) { result.await() }
            assertFalse(memoryCache(f.repo).containsKey("KONOMITV_GR"))
        } finally {
            f.releaseProvider.countDown()
        }
    }
}
