package com.beeregg2001.komorebi.ui.live

import android.media.tv.TvContentRating
import android.media.tv.TvInputManager
import android.media.tv.TvView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * URL/ExoPlayerを迂回してTIFのTvViewへ選局する。
 * TvViewの寿命は選局対象と一致させ、画面終了/再選局時にresetする。
 */
@Composable
fun TifLivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // STOP時にAndroidViewをcompositionから除去してsessionを解放する。
    // START時はViewを再生成し、reset済みsessionを再利用せず再tuneする。
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) isStarted = false
            if (event == Lifecycle.Event.ON_START) isStarted = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var target by remember(channel.id, isStarted) { mutableStateOf<TifInput.TuneTarget?>(null) }
    var resolutionComplete by remember(channel.id, isStarted) { mutableStateOf(false) }
    var resolveError by remember(channel.id, isStarted) { mutableStateOf<String?>(null) }
    LaunchedEffect(context, channel.id, isStarted) {
        target = null
        resolutionComplete = false
        resolveError = null
        if (isStarted) {
            try {
                // TvProviderの所有行照合はI/O。Compose main threadでqueryしない。
                target = withContext(Dispatchers.IO) { TifInput.resolveTarget(context, channel.id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                resolveError = "TV入力を確認できません: " + e.message
            }
        }
        resolutionComplete = true
    }
    var videoStatus by remember(channel.id, isStarted) { mutableStateOf("映像の受信を開始しています…") }
    var videoAvailable by remember(channel.id, isStarted) { mutableStateOf(false) }
    var contentBlocked by remember(channel.id, isStarted) { mutableStateOf(false) }
    var captions by rememberSaveable { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }

    val allChannels = remember(groupedChannels) {
        groupedChannels.values.flatten().filter(Channel::isWatchable)
    }

    BackHandler(enabled = isMiniListOpen) { onMiniListToggle(false) }

    LaunchedEffect(isMiniListOpen, allChannels) {
        delay(100)
        runCatching {
            if (isMiniListOpen && allChannels.isNotEmpty()) listFocus.requestFocus()
            else if (!isMiniListOpen) rootFocus.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || isMiniListOpen) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onMiniListToggle(true)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val currentIndex = allChannels.indexOfFirst { it.id == channel.id }
                        if (currentIndex < 0 || allChannels.isEmpty()) false
                        else {
                            val step = if (event.key == Key.DirectionRight) 1 else -1
                            val next = (currentIndex + step + allChannels.size) % allChannels.size
                            onChannelSelect(allChannels[next])
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
                    else -> "TV入力またはチャンネルが見つかりません。TV入力の設定とチャンネル一覧を確認してください。"
                },
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp)
            )
        } else {
            // null分岐を通過した瞬間のtargetをView世代で固定する。
            val tuneTarget = requireNotNull(target)
            key(tuneTarget.inputId, channel.id) {
                AndroidView(
                    factory = { viewContext ->
                        TvView(viewContext).apply {
                            // リモコンのフォーカスはCompose側のチャンネル選択UIが所有する。
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setCallback(object : TvView.TvInputCallback() {
                                override fun onVideoAvailable(inputId: String) {
                                    videoAvailable = true
                                    if (!contentBlocked) videoStatus = ""
                                }
                                override fun onContentBlocked(inputId: String, rating: TvContentRating) {
                                    contentBlocked = true
                                    videoStatus = "視聴年齢制限により、この番組はブロックされています"
                                }
                                override fun onContentAllowed(inputId: String) {
                                    val wasBlocked = contentBlocked
                                    contentBlocked = false
                                    if (wasBlocked) {
                                        // 映像の初回出力未確認なら再生成功表示へ移行しない。
                                        videoStatus = if (videoAvailable) "" else "映像の受信を開始しています…"
                                    }
                                }
                                override fun onVideoUnavailable(inputId: String, reason: Int) {
                                    videoAvailable = false
                                    if (!contentBlocked) {
                                        videoStatus = if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY) {
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
                            })
                            try {
                                tune(tuneTarget.inputId, tuneTarget.channelUri)
                            } catch (error: Exception) {
                                videoStatus = "TV入力の選局に失敗しました: " + error.message
                                reset()
                            }
                        }
                    },
                    update = { view -> view.setCaptionEnabled(captions) },
                    onRelease = { view ->
                        view.setCallback(null)
                        view.reset()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channel.name, color = Color.White)
            Text(
                "チャンネル一覧",
                color = Color.White,
                modifier = Modifier.clickable { onMiniListToggle(true) }
            )
            Text(
                if (captions) "字幕: ON" else "字幕: OFF",
                color = Color.White,
                modifier = Modifier.clickable { captions = !captions }
            )
        }

        if (videoStatus.isNotEmpty()) {
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
