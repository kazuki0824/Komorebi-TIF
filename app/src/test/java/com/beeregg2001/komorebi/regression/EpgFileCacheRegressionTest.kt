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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    private val start = OffsetDateTime.parse("2026-12-01T04:00:00+09:00")
    private val end = start.plusDays(1)
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
        repo = EpgRepository(context, provider, dao, Gson(), settings)
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

    private fun fetch(backendName: String) {
        runBlocking {
            Mockito.doReturn(emptyList<EpgChannelWrapper>()).`when`(provider).getEpgProgramsForBackend(
                backendName, start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "GR"
            )
            repo.fetchAndCacheEpgDataSilently(start, end, "GR")
        }
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
            assertEquals("[]", readCompressed("KONOMITV_GR"))
        }
    }

    @Test
    fun cacheFileAndRoomMarkerContainBackendQualifiedKey() {
        fetch("KONOMITV")
        assertTrue(filename("KONOMITV_GR").exists())
        Mockito.verify(dao).insertOrUpdate(
            Mockito.argThat { it.channelType == "KONOMITV_GR" && it.dataJson == "FILE_BASED" }
        )
    }

    @Test
    fun switchingBackendCreatesIndependentFilesInsteadOfOverwriting() {
        fetch("KONOMITV")
        backend.value = "EDCB"
        fetch("EDCB")
        assertTrue(filename("KONOMITV_GR").exists())
        assertTrue(filename("EDCB_GR").exists())
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
            assertEquals("[]", readCompressed("KONOMITV_GR"))
        }
    }
}
