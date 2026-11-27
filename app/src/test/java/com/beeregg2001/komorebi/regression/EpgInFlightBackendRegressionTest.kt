package com.beeregg2001.komorebi.regression

import android.content.Context
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.io.File
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
    private val programs = listOf(epgFixture("konomi", "Original EPG program", start))

    private data class Fixture(
        val repo: EpgRepository,
        val backend: MutableStateFlow<String>,
        val enteredProvider: CountDownLatch,
        val releaseProvider: CountDownLatch,
        val dao: EpgCacheDao,
        val cacheFile: File
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
            programs
        }.`when`(provider).getEpgProgramsForBackend("KONOMITV", startStr, endStr, "GR")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = Mockito.mock(EpgCacheDao::class.java)
        val cacheFile = File(context.cacheDir, "epg_cache_KONOMITV_GR.json.gz")
        cacheFile.delete()
        val repo = EpgRepository(context, provider, dao, Gson(), settings)
        Fixture(repo, backend, enteredProvider, releaseProvider, dao, cacheFile)
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
            assertFalse("obsolete prefetch must not write a cache file", f.cacheFile.exists())
            Mockito.verify(f.dao, Mockito.never()).insertOrUpdate(
                Mockito.any(EpgCacheEntity::class.java) ?: EpgCacheEntity("", "", 0L)
            )
        } finally {
            f.releaseProvider.countDown()
        }
    }
    @Test
    fun unchangedBackendPublishesAndCachesItsOwnStreamResult() = runBlocking {
        val f = fixture()
        try {
            val result = async(Dispatchers.IO) {
                f.repo.getEpgDataStream(start, end, "GR").toList()
            }
            assertTrue("provider request must start", f.enteredProvider.await(15, TimeUnit.SECONDS))
            f.releaseProvider.countDown()
            val emissions = withTimeout(15_000L) { result.await() }
            assertEquals(1, emissions.size)
            assertEquals(programs, emissions.single().getOrThrow())
            assertEquals(programs, memoryCache(f.repo)["KONOMITV_GR"])
            assertFalse(memoryCache(f.repo).containsKey("EDCB_GR"))
        } finally {
            f.releaseProvider.countDown()
        }
    }


    @Test
    fun memoryCacheIsEmittedBeforeProviderFailureAndRetained() = runBlocking {
        val backend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        val provider = Mockito.mock(DtvProviderProxy::class.java)
        val startStr = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val endStr = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        Mockito.doAnswer { throw IllegalStateException("refresh failed") }
            .`when`(provider).getEpgProgramsForBackend("KONOMITV", startStr, endStr, "GR")
        val repo = EpgRepository(
            ApplicationProvider.getApplicationContext(), provider,
            Mockito.mock(EpgCacheDao::class.java), Gson(), settings
        )
        val old = listOf(epgFixture("memory-own", "Cached EPG", start))
        @Suppress("UNCHECKED_CAST")
        val field = EpgRepository::class.java.getDeclaredField("memoryCache").apply { isAccessible = true }
        val cache = field.get(repo) as MutableMap<String, List<EpgChannelWrapper>>
        cache["KONOMITV_GR"] = old
        val emissions = repo.getEpgDataStream(start, end, "GR").toList()
        assertEquals(1, emissions.size)
        assertEquals(old, emissions.single().getOrThrow())
        assertEquals(old, cache["KONOMITV_GR"])
    }

    @Test
    fun providerFailureWithoutCacheProducesResultFailure() = runBlocking {
        val backend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        val provider = Mockito.mock(DtvProviderProxy::class.java)
        val startStr = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val endStr = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        Mockito.doAnswer { throw IllegalStateException("network failure injected") }
            .`when`(provider).getEpgProgramsForBackend("KONOMITV", startStr, endStr, "GR")
        val repo = EpgRepository(
            ApplicationProvider.getApplicationContext(), provider,
            Mockito.mock(EpgCacheDao::class.java), Gson(), settings
        )
        val emissions = repo.getEpgDataStream(start, end, "GR").toList()
        assertEquals(1, emissions.size)
        assertTrue(emissions.single().isFailure)
        assertTrue(emissions.single().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun providerCancellationPropagatesInsteadOfReturningAnEmptyEpg() = runBlocking {
        val backend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        val provider = Mockito.mock(DtvProviderProxy::class.java)
        val startStr = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val endStr = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        Mockito.doAnswer { throw CancellationException("cancel injected") }
            .`when`(provider).getEpgProgramsForBackend("KONOMITV", startStr, endStr, "GR")
        val repo = EpgRepository(
            ApplicationProvider.getApplicationContext(), provider,
            Mockito.mock(EpgCacheDao::class.java), Gson(), settings
        )
        try {
            repo.getEpgDataStream(start, end, "GR").toList()
            fail("Cancellation must not become an empty or successful result")
        } catch (expected: CancellationException) {
            assertEquals("cancel injected", expected.message)
        }
    }


    @Test
    fun foregroundStreamPersistsQualifiedGzipAndCanBeReopened() = runBlocking {
        val f = fixture()
        val marker = java.util.concurrent.atomic.AtomicReference<EpgCacheEntity?>()
        Mockito.doAnswer { invocation ->
            marker.set(invocation.arguments[0] as EpgCacheEntity)
            Unit
        }.`when`(f.dao).insertOrUpdate(
            Mockito.any(EpgCacheEntity::class.java) ?: EpgCacheEntity("", "", 0L)
        )
        Mockito.doAnswer { marker.get() }.`when`(f.dao).getCache("KONOMITV_GR")
        try {
            val pending = async(Dispatchers.IO) { f.repo.getEpgDataStream(start, end, "GR").toList() }
            assertTrue(f.enteredProvider.await(15, TimeUnit.SECONDS))
            f.releaseProvider.countDown()
            assertEquals(programs, withTimeout(15_000L) { pending.await() }.single().getOrThrow())
            withTimeout(15_000L) {
                while (marker.get() == null) delay(25)
            }
            assertEquals("KONOMITV_GR", marker.get()?.channelType)
            assertEquals("FILE_BASED", marker.get()?.dataJson)
            assertTrue("foreground stream must persist compressed file", f.cacheFile.isFile)
            val settings = Mockito.mock(SettingsRepository::class.java)
            Mockito.`when`(settings.backendType).thenReturn(f.backend)
            val provider = Mockito.mock(DtvProviderProxy::class.java)
            val reopened = EpgRepository(
                ApplicationProvider.getApplicationContext(), provider, f.dao, Gson(), settings
            )
            reopened.fetchAndCacheEpgDataSilently(start, end, "GR")
            Mockito.verifyNoInteractions(provider)
            assertEquals(programs, memoryCache(reopened)["KONOMITV_GR"])
        } finally {
            f.releaseProvider.countDown()
            f.cacheFile.delete()
        }
    }


    @Test
    fun diskCacheIsEmittedBeforeBlockedNetworkResponse() = runBlocking {
        val f = fixture()
        val disk = listOf(epgFixture("disk-own", "Disk before network", start))
        GZIPOutputStream(f.cacheFile.outputStream()).use {
            it.write(Gson().toJson(disk).toByteArray(Charsets.UTF_8))
        }
        Mockito.doReturn(EpgCacheEntity("KONOMITV_GR", "FILE_BASED", 10L))
            .`when`(f.dao).getCache("KONOMITV_GR")
        val observed = Channel<Result<List<EpgChannelWrapper>>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            f.repo.getEpgDataStream(start, end, "GR").collect { observed.send(it) }
        }
        try {
            val first = withTimeout(15_000L) { observed.receive() }
            assertEquals(disk, first.getOrThrow())
            assertTrue(f.enteredProvider.await(15, TimeUnit.SECONDS))
            f.releaseProvider.countDown()
            val second = withTimeout(15_000L) { observed.receive() }
            assertEquals(programs, second.getOrThrow())
        } finally {
            f.releaseProvider.countDown()
            collector.cancelAndJoin()
            observed.close()
            f.cacheFile.delete()
        }
    }

}
