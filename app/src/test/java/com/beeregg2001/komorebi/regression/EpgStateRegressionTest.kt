package com.beeregg2001.komorebi.regression

import androidx.compose.ui.unit.Density
import com.beeregg2001.komorebi.ui.epg.engine.EpgConfig
import com.beeregg2001.komorebi.ui.epg.engine.EpgState
import com.beeregg2001.komorebi.ui.theme.MonotonePalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

/** PR #3 resets the old backend's rendered EPG state on switch/empty result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgStateRegressionTest {
    private fun state() = EpgState(EpgConfig(
        density = Density(1f), colors = MonotonePalette,
        screenWidthPx = 1920f, screenHeightPx = 1080f
    ))

    @Test
    fun clearDataResetsFocusAndAllScrollTargets() {
        val epg = state()
        epg.focusedCol = 4
        epg.focusedMin = 90
        epg.targetScrollX = -400f
        epg.targetScrollY = -700f
        epg.targetAnimX = 50f
        epg.targetAnimY = 100f
        epg.clearData()
        assertEquals(0, epg.focusedCol)
        assertEquals(0, epg.focusedMin)
        assertEquals(0f, epg.targetScrollX, 0f)
        assertEquals(0f, epg.targetScrollY, 0f)
        assertEquals(0f, epg.targetAnimX, 0f)
        assertEquals(0f, epg.targetAnimY, 0f)
        assertFalse(epg.hasData)
        assertFalse(epg.isCalculating)
        assertFalse(epg.isInitialized)
    }

    @Test
    fun emptyEpgUpdateDiscardsOldFocusAndSetsRequestedTimeWindow() = runBlocking {
        val epg = state()
        epg.focusedMin = 120
        epg.targetScrollY = -200f
        val requested = OffsetDateTime.parse("2026-11-20T19:00:00+09:00")
        epg.updateData(emptyList(), requested, "KONOMITV_GR")
        assertEquals(requested, epg.baseTime)
        assertEquals(requested, epg.limitTime)
        assertEquals(0, epg.focusedMin)
        assertEquals(0f, epg.targetScrollY, 0f)
        assertTrue(epg.uiChannels.isEmpty())
        assertTrue(epg.filledChannelWrappers.isEmpty())
    }
}
