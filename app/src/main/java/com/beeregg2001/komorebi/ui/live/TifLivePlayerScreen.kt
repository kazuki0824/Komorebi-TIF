package com.beeregg2001.komorebi.ui.live

import android.media.tv.TvContentRating
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** TvTrackInfoの説明を優先し、主副音声の意味をtrack IDから推測しない。 */
private fun audioLabel(track: TvTrackInfo, index: Int): String =
    track.description?.toString()?.takeIf { it.isNotBlank() }
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: "音声トラック ${index + 1}"

/**
 * Android TV Input Frameworkのライブ視聴画面。
 * ネットワーク配信/Media3/Komorebiネイティブライブラリには依存しない。
 */
@Composable
fun TifLivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    showOverlay: Boolean,
    onShowOverlayChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> isStarted = false
                Lifecycle.Event.ON_START -> isStarted = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 明示的な再選局は新セッションを作成し、古いTvViewを解放する。
    var retuneGeneration by remember(channel.id) { mutableIntStateOf(0) }
    var target by remember(channel.id, isStarted, retuneGeneration) {
        mutableStateOf<TifInput.TuneTarget?>(null)
    }
    var resolutionComplete by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf(false) }
    var resolveError by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf<String?>(null) }
    LaunchedEffect(context, channel.id, isStarted, retuneGeneration) {
        target = null
        resolutionComplete = false
        resolveError = null
        if (isStarted) {
            try {
                target = withContext(Dispatchers.IO) { TifInput.resolveTarget(context, channel.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                resolveError = "TV入力の確認に失敗しました: " + error.message
            }
        }
        resolutionComplete = true
    }

    var videoStatus by remember(channel.id, isStarted, retuneGeneration) {
        mutableStateOf("映像の受信を開始しています…")
    }
    var videoAvailable by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf(false) }
    var contentBlocked by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf(false) }
    var audioTracks by remember(channel.id, isStarted, retuneGeneration) {
        mutableStateOf<List<TvTrackInfo>>(emptyList())
    }
    var selectedAudioId by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf<String?>(null) }
    var activeView by remember(channel.id, isStarted, retuneGeneration) { mutableStateOf<TvView?>(null) }
    var captions by rememberSaveable { mutableStateOf(true) }

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val firstControlFocus = remember { FocusRequester() }
    var controlHasFocus by remember { mutableStateOf(false) }

    val allChannels = remember(groupedChannels) {
        groupedChannels.values.flatten().filter(Channel::isWatchable)
    }

    BackHandler(enabled = isMiniListOpen) { onMiniListToggle(false) }

    // 操作帯を隠した場合は必ず画面本体へフォーカスを戻し、決定キーで再表示できる。
    // ボタン操作中はフォーカスを移さず、子要素の決定/左右キーを奪わない。
    LaunchedEffect(isMiniListOpen, showOverlay) {
        if (!showOverlay || isMiniListOpen) controlHasFocus = false
        delay(60)
        runCatching {
            when {
                isMiniListOpen && allChannels.isNotEmpty() -> listFocus.requestFocus()
                !isMiniListOpen && showOverlay -> firstControlFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || isMiniListOpen || controlHasFocus) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (showOverlay) firstControlFocus.requestFocus()
                        else onShowOverlayChange(true)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (showOverlay) return@onKeyEvent false
                        val currentIndex = allChannels.indexOfFirst { it.id == channel.id }
                        if (currentIndex < 0 || allChannels.isEmpty()) false
                        else {
                            val step = if (event.key == Key.DirectionRight) 1 else -1
                            onChannelSelect(allChannels[(currentIndex + step + allChannels.size) % allChannels.size])
                            true
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        if (target == null) {
            Text(
                text = when {
                    !isStarted -> "バックグラウンドのためTV入力を停止しています"
                    !resolutionComplete -> "TV入力を確認しています…"
                    resolveError != null -> resolveError.orEmpty()
                    else -> "TV入力またはチャンネルが見つかりません。TV入力設定をご確認ください。"
                },
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp)
            )
        } else {
            val tuneTarget = requireNotNull(target)
            key(tuneTarget.inputId, channel.id, retuneGeneration) {
                AndroidView(
                    factory = { viewContext ->
                        TvView(viewContext).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setCallback(object : TvView.TvInputCallback() {
                                override fun onVideoAvailable(inputId: String) {
                                    videoAvailable = true
                                    if (!contentBlocked) videoStatus = ""
                                }

                                override fun onContentBlocked(inputId: String, rating: TvContentRating) {
                                    contentBlocked = true
                                    videoStatus =
                                        "視聴年齢制限によりブロックされています。端末のTV入力・保護者による使用制限の設定を確認し、許可後に「再選局」を選択してください。"
                                }

                                override fun onContentAllowed(inputId: String) {
                                    if (contentBlocked) {
                                        contentBlocked = false
                                        videoStatus = if (videoAvailable) "" else "映像の受信を開始しています…"
                                    }
                                }

                                override fun onVideoUnavailable(inputId: String, reason: Int) {
                                    videoAvailable = false
                                    if (!contentBlocked) {
                                        videoStatus =
                                            if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY) {
                                                "音声のみの放送です"
                                            } else {
                                                "映像を利用できません (TIF reason=$reason)"
                                            }
                                    }
                                }

                                override fun onConnectionFailed(inputId: String) {
                                    videoAvailable = false
                                    videoStatus = "TV入力との接続に失敗しました"
                                }

                                override fun onDisconnected(inputId: String) {
                                    videoAvailable = false
                                    videoStatus = "TV入力との接続が切れました"
                                }

                                override fun onTracksChanged(inputId: String, tracks: MutableList<TvTrackInfo>) {
                                    audioTracks = tracks.filter { it.type == TvTrackInfo.TYPE_AUDIO }
                                    if (audioTracks.none { it.id == selectedAudioId }) selectedAudioId = null
                                }

                                override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
                                    if (type == TvTrackInfo.TYPE_AUDIO) selectedAudioId = trackId
                                }
                            })
                            try {
                                tune(tuneTarget.inputId, tuneTarget.channelUri)
                                activeView = this
                            } catch (error: Exception) {
                                videoStatus = "TV入力の選局に失敗しました: " + error.message
                                reset()
                            }
                        }
                    },
                    update = { view ->
                        runCatching { view.setCaptionEnabled(captions) }
                    },
                    onRelease = { view ->
                        view.setCallback(null)
                        view.reset()
                        if (activeView === view) activeView = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showOverlay && !isMiniListOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .onFocusChanged { controlHasFocus = it.hasFocus }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(channel.name, color = Color.White)
                Button(
                    onClick = { onMiniListToggle(true) },
                    modifier = Modifier.focusRequester(firstControlFocus)
                ) { Text("チャンネル一覧") }

                Button(onClick = { captions = !captions }) {
                    Text(if (captions) "字幕: ON" else "字幕: OFF")
                }

                if (audioTracks.isNotEmpty()) {
                    val selectedIndex = audioTracks.indexOfFirst { it.id == selectedAudioId }
                    val selection = if (selectedIndex >= 0) {
                        audioLabel(audioTracks[selectedIndex], selectedIndex)
                    } else "選択未通知"
                    Button(
                        enabled = audioTracks.size > 1,
                        onClick = {
                            val tracks = audioTracks
                            val nextIndex = (selectedIndex + 1) % tracks.size
                            val wanted = tracks[nextIndex].id
                            try {
                                activeView?.selectTrack(TvTrackInfo.TYPE_AUDIO, wanted)
                                // 選択状態はTISのonTrackSelectedを正本とする。
                            } catch (error: Exception) {
                                videoStatus = "音声切替に失敗しました: " + error.message
                            }
                        }
                    ) { Text("音声: " + selection + (if (audioTracks.size > 1) "（切替）" else "")) }
                }

                Button(onClick = { retuneGeneration++ }) { Text("再選局") }
                Button(onClick = { onShowOverlayChange(false) }) { Text("操作を隠す") }
            }
        }

        // input/URI未解決時は解決失敗だけを表示し、映像受信待ちを重ねない。
        if (target != null && videoStatus.isNotEmpty()) {
            Text(
                text = videoStatus,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp)
            )
        }

        if (isMiniListOpen && allChannels.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ChannelListOverlay(
                    groupedChannels = groupedChannels,
                    currentChannelId = channel.id,
                    onChannelSelect = {
                        onChannelSelect(it)
                        onMiniListToggle(false)
                    },
                    getLogoUrl = { "" },
                    shouldCropLogo = false,
                    focusRequester = listFocus
                )
            }
        }
    }
}
