package com.beeregg2001.komorebi.regression

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recording playback must preserve the session's backend identity when saving history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPlaybackHistoryOwnershipRegressionTest {
    @Test
    fun edcbRecordingHistoryIsSavedWithExplicitEdcbOwner() = runBlocking {
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        val viewModel = VideoPlayerViewModel(
            Mockito.mock(RecordProvider::class.java), history,
            Mockito.mock(SettingsRepository::class.java),
            Mockito.mock(RecordSyncEngine::class.java)
        )
        val recorded = Mockito.mock(RecordedProgram::class.java)
        viewModel.updateWatchHistory(recorded, 32.5, "EDCB")
        Mockito.verify(history, Mockito.timeout(10_000L)).saveWatchHistory(recorded, 32.5, "EDCB")
    }

    @Test
    fun konomiRecordingHistoryDoesNotInheritAnOldEdcbOwner() = runBlocking {
        val history = Mockito.mock(WatchHistoryRepository::class.java)
        val viewModel = VideoPlayerViewModel(
            Mockito.mock(RecordProvider::class.java), history,
            Mockito.mock(SettingsRepository::class.java),
            Mockito.mock(RecordSyncEngine::class.java)
        )
        val recorded = Mockito.mock(RecordedProgram::class.java)
        viewModel.updateWatchHistory(recorded, 12.0, "KONOMITV")
        Mockito.verify(history, Mockito.timeout(10_000L)).saveWatchHistory(recorded, 12.0, "KONOMITV")
    }
}
