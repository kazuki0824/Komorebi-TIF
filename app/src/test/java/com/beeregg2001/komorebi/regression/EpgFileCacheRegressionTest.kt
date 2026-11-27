package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Actual EpgRepository file/Room cache protocol after PR #3 backend qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgFileCacheRegressionTest {
    private lateinit var context: Context
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var dao: EpgCacheDao
    private lateinit var provider: DtvProviderProxy
    private lateinit var repo: EpgRepository
    private val gson = Gson()
    private val start = OffsetDateTime.now().plusDays(1).withNano(0)
    private val end = start.plusDays(1)
    private val konomiPrograms = listOf(epgFixture("konomi", "Konomi news", start))
    private val edcbPrograms = listOf(epgFixture("edcb", "EDCB documentary", start))
    private fun filename(key: String) = File(context.cacheDir, "epg_cache_${key}.json.gz")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("GR", "KONOMITV_GR", "EDCB_GR").forEach { filename(it).delete() }
        backend = MutableStateFlow("KONOMITV")
        dao = Mockito.mock(EpgCacheDao::class.java)
        provider = Mockito.mock(DtvProviderProxy::class.java)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        repo = EpgRepository(context, provider, dao, gson, settings)
    }

    @After
    fun cleanup() {
        listOf("GR", "KONOMITV_GR", "EDCB_GR").forEach { filename(it).delete() }
    }

    private fun writeCompressed(key: String, text: String) {
        GZIPOutputStream(filename(key).outputStream()).use { it.write(text.toByteArray()) }
    }

    private fun readCompressed(key: String): String =
        GZIPInputStream(filename(key).inputStream()).bufferedReader().use { it.readText() }

    private suspend fun fetch(backendName: String, programs: List<EpgChannelWrapper> = konomiPrograms) {
        Mockito.doReturn(programs).`when`(provider).getEpgProgramsForBackend(
            backendName, start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
        repo.fetchAndCacheEpgDataSilently(start, end, "GR")
    }

    private fun assertStoredPrograms(key: String, programs: List<EpgChannelWrapper>) {
        val stored = gson.fromJson(readCompressed(key), JsonElement::class.java)
        assertEquals(gson.toJsonTree(programs), stored)
    }

    @Test
    fun unqualifiedLegacyCacheIsNeverReadAsSelectedBackend() {
        runBlocking {
            writeCompressed("GR", "[{\"legacy\":true}]")
            Mockito.doReturn(EpgCacheEntity("GR", "FILE_BASED", 10L))
                .`when`(dao).getCache("GR")
            fetch("KONOMITV")
            Mockito.verify(dao, Mockito.never()).getCache("GR")
            Mockito.verify(provider).getEpgProgramsForBackend(
                "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
            )
            assertEquals("[{\"legacy\":true}]", readCompressed("GR"))
            assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        }
    }

    @Test
    fun cacheFileAndRoomMarkerContainBackendQualifiedKey() = runBlocking {
        fetch("KONOMITV")
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        Mockito.verify(dao).insertOrUpdate(
            Mockito.argThat<EpgCacheEntity> { it.channelType == "KONOMITV_GR" && it.dataJson == "FILE_BASED" } ?: EpgCacheEntity("", "", 0L)
        )
    }

    @Test
    fun switchingBackendCreatesIndependentFilesInsteadOfOverwriting(): Unit = runBlocking {
        fetch("KONOMITV")
        backend.value = "EDCB"
        fetch("EDCB", edcbPrograms)
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        assertStoredPrograms("EDCB_GR", edcbPrograms)
        Mockito.verify(provider).getEpgProgramsForBackend(
            "EDCB", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
    }

    @Test
    fun corruptedQualifiedGzipIsNotReturnedAsValidCache() {
        runBlocking {
            filename("KONOMITV_GR").writeText("not gzip")
            Mockito.doReturn(EpgCacheEntity("KONOMITV_GR", "FILE_BASED", 10L))
                .`when`(dao).getCache("KONOMITV_GR")
            fetch("KONOMITV")
            Mockito.verify(provider).getEpgProgramsForBackend(
                "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
            )
            assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        }
    }
    @Test
    fun sqliteCacheReadFailureForQualifiedBackendFallsBackToProvider(): Unit = runBlocking {
        Mockito.doAnswer { throw SQLiteException("injected large legacy blob") }
            .`when`(dao).getCache("KONOMITV_GR")
        fetch("KONOMITV")
        Mockito.verify(dao).getCache("KONOMITV_GR")
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        Mockito.verify(provider).getEpgProgramsForBackend(
            "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
        Mockito.verify(dao, Mockito.never()).getCache("GR")
    }

    @Test
    fun roomCacheMarkerWriteFailureDoesNotDiscardFetchedPrograms() = runBlocking {
        Mockito.doAnswer { throw SQLiteException("marker insert failed") }
            .`when`(dao).insertOrUpdate(
                Mockito.any(EpgCacheEntity::class.java)
                    ?: EpgCacheEntity("", "", 0L)
            )
        fetch("KONOMITV")
        Mockito.verify(dao).insertOrUpdate(
            Mockito.argThat<EpgCacheEntity> { it.channelType == "KONOMITV_GR" && it.dataJson == "FILE_BASED" }
                ?: EpgCacheEntity("", "", 0L)
        )
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        assertEquals(
            konomiPrograms.single().programs.single(),
            repo.searchFuturePrograms(query = "Konomi news").single().program
        )
        assertFalse(filename("EDCB_GR").exists())
        // The failed marker must not be mistaken for a new backend's cache.
        backend.value = "EDCB"
        fetch("EDCB", edcbPrograms)
        assertStoredPrograms("EDCB_GR", edcbPrograms)
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        assertTrue(repo.searchFuturePrograms(query = "Konomi news").isEmpty())
        assertEquals(
            edcbPrograms.single().programs.single(),
            repo.searchFuturePrograms(query = "EDCB documentary").single().program
        )
    }

    @Test
    fun diskReadCompletingAfterBackendSwitchCannotPopulateOldSelection() = runBlocking {
        writeCompressed("KONOMITV_GR", gson.toJson(konomiPrograms))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            EpgCacheEntity("KONOMITV_GR", "FILE_BASED", System.currentTimeMillis())
        }.`when`(dao).getCache("KONOMITV_GR")
        try {
            val pending = async(Dispatchers.Default) {
                repo.fetchAndCacheEpgDataSilently(start, end, "GR")
            }
            assertTrue("old backend disk query did not start", entered.await(15, TimeUnit.SECONDS))
            backend.value = "EDCB"
            release.countDown()
            withTimeout(10_000L) { pending.await() }
            Mockito.verifyNoInteractions(provider)
            assertFalse("backend change must not adopt an obsolete disk snapshot",
                filename("EDCB_GR").exists())
            val cacheField = EpgRepository::class.java.getDeclaredField("memoryCache")
            cacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(repo) as Map<String, List<EpgChannelWrapper>>
            assertFalse(cache.containsKey("KONOMITV_GR"))
            assertFalse(cache.containsKey("EDCB_GR"))
        } finally { release.countDown() }
    }


    private fun reopenedRepository(): EpgRepository {
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        return EpgRepository(context, provider, dao, gson, settings)
    }

    @Test
    fun freshQualifiedGzipReusedAfterRepositoryRecreationWithoutProviderCall() = runBlocking {
        val fresh = listOf(epgFixture("fresh", "Fresh disk program", start.plusDays(3)))
        writeCompressed("KONOMITV_GR", gson.toJson(fresh))
        Mockito.doReturn(EpgCacheEntity("KONOMITV_GR", "FILE_BASED", 100L))
            .`when`(dao).getCache("KONOMITV_GR")
        val reopened = reopenedRepository()
        reopened.fetchAndCacheEpgDataSilently(start, end, "GR")
        Mockito.verifyNoInteractions(provider)
        assertEquals("Fresh disk program",
            reopened.searchFuturePrograms(query = "Fresh disk program").single().program.title)
        assertStoredPrograms("KONOMITV_GR", fresh)
    }

    @Test
    fun expiredQualifiedDiskCacheIsRefreshedFromSelectedProvider() = runBlocking {
        val expired = listOf(epgFixture("old", "Expired old show", start.minusDays(6)))
        writeCompressed("KONOMITV_GR", gson.toJson(expired))
        Mockito.doReturn(EpgCacheEntity("KONOMITV_GR", "FILE_BASED", 100L))
            .`when`(dao).getCache("KONOMITV_GR")
        Mockito.doReturn(konomiPrograms).`when`(provider).getEpgProgramsForBackend(
            "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
        val reopened = reopenedRepository()
        reopened.fetchAndCacheEpgDataSilently(start, end, "GR")
        Mockito.verify(provider, Mockito.times(1)).getEpgProgramsForBackend(
            "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
        assertTrue(reopened.searchFuturePrograms(query = "Expired old show").isEmpty())
    }

    @Test
    fun repeatedSilentPrefetchUsesMemoryCacheWithoutSecondProviderRequest() = runBlocking {
        fetch("KONOMITV")
        repo.fetchAndCacheEpgDataSilently(start, end, "GR")
        Mockito.verify(provider, Mockito.times(1)).getEpgProgramsForBackend(
            "KONOMITV", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
        )
        assertStoredPrograms("KONOMITV_GR", konomiPrograms)
    }

}
