@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.regression

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.exoplayer.ExoPlayer
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.player.ManagedPlayerHandles
import com.beeregg2001.komorebi.ui.video.player.VideoPlayerScreen
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct production VideoPlayerScreen: capture its onStopOrDispose lambda by
 * replacing only the ExoPlayer manager, not the playback backend decision.
 * No media playback or actual SMB connection occurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class VideoPlayerSessionBackendUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: VideoPlayerViewModel
    private lateinit var settings: SettingsViewModel
    private lateinit var backend: MutableStateFlow<String>
    private lateinit var confirmed: MutableStateFlow<String?>
    private lateinit var player: ExoPlayer
    private val stopCallback = AtomicReference<((ExoPlayer) -> Unit)?>(null)
    private var managerMock: MockedStatic<Any>? = null

    private fun recording() = RecordedProgram(
        id = 99, title = "played recording", description = "",
        startTime = "2030-01-01T00:00:00+09:00",
        endTime = "2030-01-01T01:00:00+09:00",
        duration = 3600.0, isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = 99, status = "Recorded", filePath = "recording.ts",
            recordingStartTime = "2030-01-01T00:00:00+09:00",
            recordingEndTime = "2030-01-01T01:00:00+09:00",
            duration = 3600.0, containerFormat = "ts",
            videoCodec = "H264", audioCodec = "AAC"
        )
    )

    @Before
    fun setUp(): Unit = runBlocking {
        vm = Mockito.mock(VideoPlayerViewModel::class.java)
        Mockito.`when`(vm.programDetail).thenReturn(MutableStateFlow(null))
        Mockito.`when`(vm.tiledThumbnailUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(vm.chapters).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.isOffsetBasedStream).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.availableQualities).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(vm.isQualitiesLoaded).thenReturn(MutableStateFlow(false))
        Mockito.`when`(vm.perProgramExcludedQualities)
            .thenReturn(MutableStateFlow(emptySet()))
        Mockito.doReturn(emptyList<ArchivedComment>()).`when`(vm).getArchivedComments(99)

        backend = MutableStateFlow("KONOMITV")
        confirmed = MutableStateFlow<String?>("KONOMITV")
        settings = Mockito.mock(SettingsViewModel::class.java)
        Mockito.`when`(settings.backendType).thenReturn(backend)
        Mockito.`when`(settings.confirmedBackendType).thenReturn(confirmed)
        Mockito.`when`(settings.videoQuality).thenReturn(MutableStateFlow("720p"))
        Mockito.`when`(settings.playerUiMode).thenReturn(MutableStateFlow("NORMAL"))
        Mockito.`when`(settings.autoCmSkip).thenReturn(MutableStateFlow("OFF"))
        Mockito.`when`(settings.commentSpeed).thenReturn(MutableStateFlow("1.0"))
        Mockito.`when`(settings.commentFontSize).thenReturn(MutableStateFlow("1.0"))
        Mockito.`when`(settings.commentOpacity).thenReturn(MutableStateFlow("1.0"))
        Mockito.`when`(settings.commentMaxLines).thenReturn(MutableStateFlow("0"))
        Mockito.`when`(settings.commentDefaultDisplay).thenReturn(MutableStateFlow("OFF"))
        Mockito.`when`(settings.subtitleCommentLayer).thenReturn(MutableStateFlow("SubtitleOnTop"))
        Mockito.`when`(settings.videoSubtitleDefault).thenReturn(MutableStateFlow("OFF"))
        Mockito.`when`(settings.cfAccessClientId).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settings.cfAccessClientSecret).thenReturn(MutableStateFlow(""))
        Mockito.`when`(settings.edcbRecordPlayMethod).thenReturn(MutableStateFlow("API"))

        player = Mockito.mock(ExoPlayer::class.java, Mockito.RETURNS_DEEP_STUBS)
        Mockito.`when`(player.currentPosition).thenReturn(120_000L)
    }

    private fun installTestPlayer() {
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val klass = Class.forName(
                "com.beeregg2001.komorebi.ui.video.player.VideoPlayerManagerKt"
            ) as Class<Any>
            managerMock = Mockito.mockStatic(klass, Answer { invocation ->
                if (invocation.method.name == "rememberManagedExoPlayer") {
                    @Suppress("UNCHECKED_CAST")
                    stopCallback.set(invocation.arguments[9] as (ExoPlayer) -> Unit)
                    ManagedPlayerHandles(player, AtomicLong(0L), AtomicLong(-1L))
                } else invocation.callRealMethod()
            })
        }
    }

    private fun render(program: RecordedProgram, smb: SmbItem? = null) {
        installTestPlayer()
        compose.setContent {
            KomorebiTheme {
                VideoPlayerScreen(
                    program = program,
                    smbItem = smb,
                    showControls = false,
                    onShowControlsChange = {},
                    isSubMenuOpen = false,
                    onSubMenuToggle = {},
                    isSceneSearchOpen = false,
                    onSceneSearchToggle = {},
                    onBackPressed = {},
                    onShowToast = {},
                    isPiPMode = true,
                    videoPlayerViewModel = vm,
                    settingsViewModel = settings
                )
            }
        }
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { managerMock?.close(); managerMock = null }
    }

    @Test
    fun backendChangeDuringPlaybackDoesNotRelabelRecordedSessionHistory() {
        val program = recording()
        render(program)
        compose.waitUntil(10_000L) { stopCallback.get() != null }
        compose.runOnIdle {
            backend.value = "EDCB"
            confirmed.value = "EDCB"
        }
        compose.waitForIdle()
        compose.runOnIdle { stopCallback.get()!!.invoke(player) }
        Mockito.verify(vm).updateWatchHistory(program, 120.0, "KONOMITV")
        Mockito.verify(vm, Mockito.never()).updateWatchHistory(program, 120.0, "EDCB")
    }

    @Test
    fun smbPlaybackNeverWritesNetworkRecordingHistory() {
        val program = recording()
        val smb = SmbItem(
            name = "movie.mkv", path = "smb://test-share/movie.mkv",
            isDirectory = false, size = 123L, lastModified = 500L
        )
        render(program, smb)
        compose.waitUntil(10_000L) { stopCallback.get() != null }
        compose.runOnIdle { stopCallback.get()!!.invoke(player) }
        Mockito.verify(vm, Mockito.never()).updateWatchHistory(
            Mockito.any(RecordedProgram::class.java),
            Mockito.anyDouble(), Mockito.anyString()
        )
    }
}
