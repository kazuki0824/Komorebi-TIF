package com.beeregg2001.komorebi.regression

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import kotlinx.coroutines.CancellationException
import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbEpgRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbLiveRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbRecordRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbReserveRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationEpgRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationLiveRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationRecordRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationReserveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests the real network backend proxy routing, without calling a remote server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DtvProviderProxyRoutingRegressionTest {
    private lateinit var selectedBackend: MutableStateFlow<String>
    private lateinit var konomi: KonomiRepository
    private lateinit var edcbEpg: EdcbEpgRepository
    private lateinit var epgstationEpg: EpgStationEpgRepository
    private lateinit var edcbLive: EdcbLiveRepository
    private lateinit var proxy: DtvProviderProxy

    @Before
    fun setUp() {
        selectedBackend = MutableStateFlow("KONOMITV")
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(selectedBackend)
        konomi = Mockito.mock(KonomiRepository::class.java)
        edcbEpg = Mockito.mock(EdcbEpgRepository::class.java)
        epgstationEpg = Mockito.mock(EpgStationEpgRepository::class.java)
        edcbLive = Mockito.mock(EdcbLiveRepository::class.java)
        proxy = DtvProviderProxy(
            settings, konomi,
            Mockito.mock(EpgStationLiveRepository::class.java),
            Mockito.mock(EpgStationRecordRepository::class.java),
            Mockito.mock(EpgStationReserveRepository::class.java),
            epgstationEpg, edcbLive,
            Mockito.mock(EdcbRecordRepository::class.java),
            Mockito.mock(EdcbReserveRepository::class.java),
            edcbEpg
        )
    }

    @Test
    fun fixedEdcbEpgRequestIgnoresCurrentKonomiSelection() = runBlocking {
        proxy.getEpgProgramsForBackend("EDCB", "start", "end", "GR")
        Mockito.verify(edcbEpg).getEpgPrograms("start", "end", "GR")
        Mockito.verifyNoInteractions(konomi, epgstationEpg)
    }

    @Test
    fun fixedEpgstationEpgRequestIgnoresCurrentEdcbSelection() = runBlocking {
        selectedBackend.value = "EDCB"
        proxy.getEpgProgramsForBackend("EPGSTATION", "start", "end", "BS")
        Mockito.verify(epgstationEpg).getEpgPrograms("start", "end", "BS")
        Mockito.verifyNoInteractions(konomi, edcbEpg)
    }

    @Test
    fun fixedKonomiEpgRequestIgnoresCurrentEpgstationSelection() = runBlocking {
        selectedBackend.value = "EPGSTATION"
        proxy.getEpgProgramsForBackend("KONOMITV", "start", "end", "CS")
        Mockito.verify(konomi).getEpgPrograms("start", "end", "CS")
        Mockito.verifyNoInteractions(edcbEpg, epgstationEpg)
    }

    @Test
    fun liveStreamErrorIsNotSilentlyReplacedByEmptyUrl() = runBlocking {
        selectedBackend.value = "EDCB"
        val cause = IllegalStateException("EDCB connection configuration failed")
        Mockito.`when`(edcbLive.getLiveStreamUrl("ch", "original", 1)).thenThrow(cause)
        val thrown = runCatching {
            proxy.getLiveStreamUrl("ch", "original", 1)
        }.exceptionOrNull()
        assertSame(cause, thrown)
    }
    @Test
    fun liveChannelsRequestIsRoutedToCurrentlySelectedBackend() = runBlocking {
        selectedBackend.value = "EDCB"
        val expected = ChannelApiResponse()
        Mockito.doReturn(expected).`when`(edcbLive).getChannels()
        assertSame(expected, proxy.getChannels())
        Mockito.verify(edcbLive).getChannels()
        Mockito.verifyNoInteractions(konomi)
    }

    @Test
    fun liveChannelsTransportFailureReturnsEmptyFallback() = runBlocking {
        selectedBackend.value = "EDCB"
        Mockito.doAnswer { throw IllegalStateException("network unavailable") }
            .`when`(edcbLive).getChannels()
        val actual = proxy.getChannels()
        assertTrue(actual.terrestrial.isNullOrEmpty())
        assertTrue(actual.bs.isNullOrEmpty())
        assertTrue(actual.cs.isNullOrEmpty())
    }

    @Test
    fun liveChannelsCancellationIsNeverSwallowedAsEmptySuccess() = runBlocking {
        selectedBackend.value = "EDCB"
        Mockito.doAnswer { throw CancellationException("backend switched") }
            .`when`(edcbLive).getChannels()
        val error = runCatching { proxy.getChannels() }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

}
