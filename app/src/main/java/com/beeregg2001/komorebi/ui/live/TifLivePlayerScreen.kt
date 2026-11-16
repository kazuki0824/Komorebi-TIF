package com.beeregg2001.komorebi.ui.live

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import kotlinx.coroutines.delay

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
    val input = remember(context) { TifInput.findInput(context) }
    val uri = remember(channel.id) { TifInput.channelUri(channel.id) }
    var videoStatus by remember(channel.id) { mutableStateOf("映像の受信を開始しています…") }
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
        if (input == null || uri == null) {
            Text(
                text = "Maleicacid TV入力またはチャンネルが見つかりません。設定でTV入力のチャンネルスキャンを実施してください。",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp)
            )
        } else {
            key(input.id, channel.id) {
                AndroidView(
                    factory = { viewContext ->
                        TvView(viewContext).apply {
                            // リモコンのフォーカスはCompose側のチャンネル選択UIが所有する。
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setCallback(object : TvView.TvInputCallback() {
                                override fun onVideoAvailable(inputId: String) {
                                    videoStatus = ""
                                    setCaptionEnabled(captions)
                                }
                                override fun onVideoUnavailable(inputId: String, reason: Int) {
                                    videoStatus = if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY) {
                                        "音声のみの放送です"
                                    } else {
                                        "映像を利用できません (TIF reason=$reason)"
                                    }
                                }
                                override fun onConnectionFailed(inputId: String) {
                                    videoStatus = "TV入力との接続に失敗しました"
                                }
                                override fun onDisconnected(inputId: String) {
                                    videoStatus = "TV入力との接続が切れました"
                                }
                            })
                            tune(input.id, uri)
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
