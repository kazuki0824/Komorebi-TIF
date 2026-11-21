package com.beeregg2001.komorebi.regression

import com.beeregg2001.komorebi.ui.main.MainRootState
import com.beeregg2001.komorebi.ui.main.visibleHomeTabs
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression of existing network backend tab navigation and SMB player selection
 * semantics; it does not validate real VLC playback or Compose focus routing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExistingHomeNavigationRegressionTest {
    private val baseTabs = listOf("ホーム", "ライブ", "ビデオ", "番組表", "録画予約")

    @Test
    fun allExistingBackendsRetainTheirNonBaseballTabs() {
        for (backend in listOf("KONOMITV", "EDCB", "EPGSTATION")) {
            assertEquals(backend, baseTabs, visibleHomeTabs(backend, false))
        }
    }

    @Test
    fun allExistingBackendsRetainTheirBaseballTabWhenConfigured() {
        for (backend in listOf("KONOMITV", "EDCB", "EPGSTATION")) {
            assertEquals(backend, baseTabs + "プロ野球", visibleHomeTabs(backend, true))
        }
    }

    @Test
    fun smbSelectionIsFullscreenEvenWithoutAnyRecordedProgram() {
        val state = MainRootState()
        state.selectedSmbItem = Mockito.mock(SmbItem::class.java)
        state.selectedProgram = null
        assertTrue(state.isFullScreen(
            channel = null, program = null, epgProgram = null,
            settingsOpen = false, recordListOpen = false, reserveOverlayOpen = false
        ))
    }

    @Test
    fun smbSelectionDoesNotDisableMiniplayerSemantics() {
        val state = MainRootState()
        state.selectedSmbItem = Mockito.mock(SmbItem::class.java)
        state.selectedProgram = null
        state.isMiniPlayerMode = true
        assertFalse(state.isFullScreen(
            channel = null, program = null, epgProgram = null,
            settingsOpen = false, recordListOpen = false, reserveOverlayOpen = false
        ))
    }
}
