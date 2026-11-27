package com.beeregg2001.komorebi.regression

import androidx.compose.ui.unit.Density
import com.beeregg2001.komorebi.ui.epg.engine.EpgConfig
import com.beeregg2001.komorebi.ui.epg.engine.EpgState
import com.beeregg2001.komorebi.ui.theme.MonotonePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

/** PR #3 resets the old backend's rendered EPG state on switch/empty result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EpgStateRegressionTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun state() = EpgState(EpgConfig(
        density = Density(1f), colors = MonotonePalette,
        screenWidthPx = 1920f, screenHeightPx = 1080f
    ))

    @Test
    fun clearDataResetsFocusAndAllScrollTargets() = runBlocking {
        val epg = state()
        val time = OffsetDateTime.parse("2026-11-20T19:00:00+09:00")
        val old = epgFixture("old-channel", "Previous program", time)
        epg.updateData(listOf(old), time, "GR")
        assertTrue(epg.hasData)
        assertTrue(epg.isInitialized)
        epg.currentFocusedProgram = old.programs.single()
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
        assertNull(epg.currentFocusedProgram)
        assertTrue(epg.filledChannelWrappers.isEmpty())

        val replacement = epgFixture("new-channel", "Replacement program", time)
        epg.updateData(listOf(replacement), time, "GR")
        assertEquals("new-channel", epg.filledChannelWrappers.single().channel.id)
    }

    @Test
    fun emptyEpgUpdateDiscardsOldFocusAndSetsRequestedTimeWindow() = runBlocking {
        val epg = state()
        val requested = OffsetDateTime.parse("2026-11-20T19:00:00+09:00")
        val old = epgFixture("old-channel", "Previous program", requested.minusHours(1))
        epg.updateData(listOf(old), requested.minusHours(1), "KONOMITV_GR")
        assertTrue(epg.hasData)
        epg.currentFocusedProgram = old.programs.single()
        epg.focusedMin = 120
        epg.targetScrollY = -200f
        epg.updateData(emptyList(), requested, "KONOMITV_GR")
        assertEquals(requested, epg.baseTime)
        assertEquals(requested, epg.limitTime)
        assertEquals(0, epg.focusedMin)
        assertEquals(0f, epg.targetScrollY, 0f)
        assertTrue(epg.uiChannels.isEmpty())
        assertTrue(epg.filledChannelWrappers.isEmpty())
        assertNull(epg.currentFocusedProgram)
    }
}
