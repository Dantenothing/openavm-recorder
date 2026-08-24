package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.player.PlaybackDiagnostics
import com.dante.zeekrcapabilitylab.player.PlaybackInspector
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PlaybackControls(
    val toggle: () -> Boolean,
    val seekTo: (Long) -> Unit,
    val currentPosition: () -> Long,
    val duration: () -> Long,
    val isPlaying: () -> Boolean,
)

private data class PlaybackSurfaceCallbacks(
    val onControlsReady: (PlaybackControls?) -> Unit,
    val onPrepared: (Long) -> Unit,
    val onFirstFrame: () -> Unit,
    val onCompleted: () -> Unit,
    val onError: (String) -> Unit,
)

/**
 * Product playback surface. It deliberately mirrors the live-preview path:
 * one ordinary TextureView receives the 1280x5140 composite and the shared
 * FourLaneTextureContainer redraws its four vertical lanes as a 2x2 grid.
 */
@Composable
fun FourLanePlayerDialog(
    file: File,
    layoutKind: RecordingLayoutKind? = RecordingLayoutKind.FOUR_LANE_V1,
    sourceRole: RecordingSourceRole? = RecordingSourceRole.SURROUND,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onSendToPhone: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val languageMode by AppLanguage.mode.collectAsState()
    val diagnostics by produceState<PlaybackDiagnostics?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { PlaybackInspector.inspect(file) }
    }
    val directionLabels = productDirectionLabels()
    val isFourLane = layoutKind != RecordingLayoutKind.SINGLE_V1

    var controls by remember(file) { mutableStateOf<PlaybackControls?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var firstFrame by remember(file) { mutableStateOf(false) }
    var positionMs by remember(file) { mutableStateOf(0L) }
    var durationMs by remember(file) { mutableStateOf(0L) }
    var dragging by remember(file) { mutableStateOf(false) }
    var draggedPositionMs by remember(file) { mutableStateOf(0L) }
    var status by remember(file, languageMode) {
        mutableStateOf(Utils.t("Opening recording…", "正在打开录像…"))
    }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var displayMode by remember(file) { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
    var lensMode by remember(file) { mutableStateOf(settings.lensMode) }
    var zoom by remember(file) { mutableStateOf(1f) }
    // AndroidView keeps the attached View across recompositions. Reuse that
    // same attached container when previous/next changes the media source.
    val playbackContainer = remember(context) { FourLaneTextureContainer(context) }
    val singleTextureView = remember(context) { TextureView(context) }

    DisposableEffect(file.absolutePath) {
        PlaybackPinRegistry.acquire(file)
        onDispose { PlaybackPinRegistry.release(file) }
    }

    BackHandler(enabled = isFourLane && displayMode.singleLane != null) {
        displayMode = FourLaneDisplayMode.FOUR_GRID
        zoom = playbackContainer.resetViewport()
    }

    LaunchedEffect(controls) {
        val active = controls ?: return@LaunchedEffect
        while (true) {
            positionMs = runCatching { active.currentPosition() }.getOrDefault(positionMs)
            durationMs = runCatching { active.duration() }.getOrDefault(durationMs)
            playing = runCatching { active.isPlaying() }.getOrDefault(false)
            delay(250L)
        }
    }

    Dialog(
        onDismissRequest = {
            if (isFourLane && displayMode.singleLane != null) {
                displayMode = FourLaneDisplayMode.FOUR_GRID
                zoom = playbackContainer.resetViewport()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Utils.t("Playback", "录像回放") + " · " + playbackSourceLabel(sourceRole),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            Utils.formatEpoch(file.lastModified()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1.75f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val previewSide = minOf(
                            (maxWidth - 124.dp).coerceAtLeast(160.dp),
                            maxHeight,
                        )
                        val singlePreviewWidth = minOf(
                            (maxWidth - 124.dp).coerceAtLeast(160.dp),
                            maxHeight * (16f / 9f),
                        )
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaybackSequenceButton(
                                previous = true,
                                enabled = onPrevious != null,
                                onClick = { onPrevious?.invoke() },
                            )
                            Spacer(Modifier.size(10.dp))
                            Box(
                                (if (isFourLane) {
                                    Modifier.size(previewSide)
                                } else {
                                    Modifier.width(singlePreviewWidth).aspectRatio(16f / 9f)
                                })
                                    .background(Color.Black, RoundedCornerShape(14.dp)),
                            ) {
                                val playbackCallbacks = PlaybackSurfaceCallbacks(
                                    onControlsReady = { controls = it },
                                    onPrepared = { playerDuration ->
                                        durationMs = playerDuration
                                        playing = true
                                        status = Utils.t("Playing", "正在播放")
                                    },
                                    onFirstFrame = {
                                        firstFrame = true
                                        error = null
                                        status = Utils.t("Playing", "正在播放")
                                    },
                                    onCompleted = {
                                        positionMs = durationMs
                                        playing = false
                                        status = Utils.t("Playback complete", "播放完成")
                                    },
                                    onError = { message ->
                                        error = message
                                        playing = false
                                        status = Utils.t("Unable to play", "无法播放")
                                    },
                                )
                                if (isFourLane) {
                                    FourLanePlaybackSurface(
                                        file = file,
                                        container = playbackContainer,
                                        displayMode = displayMode,
                                        lensMode = lensMode,
                                        correctionConfig = settings.fisheyeCorrection,
                                        callbacks = playbackCallbacks,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    FourLaneDirectionOverlay(
                                        labels = directionLabels,
                                        displayMode = displayMode,
                                        interactionEnabled = firstFrame,
                                        zoom = zoom,
                                        onLaneTapped = { lane ->
                                            displayMode = displayMode.toggleLane(lane)
                                            zoom = playbackContainer.resetViewport()
                                        },
                                        onTransformGesture = { zoomChange, panX, panY ->
                                            zoom = playbackContainer.applyViewportGesture(zoomChange, panX, panY)
                                        },
                                    )
                                    FourLaneLensToggle(
                                        mode = lensMode,
                                        onModeChanged = { selected ->
                                            lensMode = selected
                                            settings.setLensMode(selected)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                    )
                                } else {
                                    SinglePlaybackSurface(
                                        file = file,
                                        textureView = singleTextureView,
                                        callbacks = playbackCallbacks,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (!firstFrame && error == null) {
                                    Text(
                                        Utils.t("Loading video…", "正在载入画面…"),
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        color = Color.White,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            PlaybackSequenceButton(
                                previous = false,
                                enabled = onNext != null,
                                onClick = { onNext?.invoke() },
                            )
                        }
                    }

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PlaybackInfoCard(
                            diagnostics = diagnostics,
                            file = file,
                            status = status,
                        )
                        error?.let {
                            Text(
                                it,
                                modifier = Modifier.padding(top = 10.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                onSendToPhone?.invoke()
                                status = Utils.t("Added to phone transfer queue", "已加入手机传输队列")
                            },
                            enabled = onSendToPhone != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Text(Utils.t("Send to phone", "发送到手机"), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onDelete?.invoke() },
                            enabled = onDelete != null,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text(Utils.t("Delete recording", "删除录像"))
                        }
                    }
                }

                PlaybackTimeline(
                    positionMs = if (dragging) draggedPositionMs else positionMs,
                    durationMs = durationMs,
                    playing = playing,
                    enabled = controls != null,
                    onDragStart = {
                        dragging = true
                        draggedPositionMs = it
                    },
                    onDrag = { draggedPositionMs = it },
                    onDragFinished = {
                        controls?.seekTo(draggedPositionMs)
                        positionMs = draggedPositionMs
                        dragging = false
                    },
                    onSeekBy = { delta ->
                        val target = (positionMs + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                        controls?.seekTo(target)
                        positionMs = target
                    },
                    onToggle = {
                        playing = controls?.toggle?.invoke() ?: false
                        status = if (playing) Utils.t("Playing", "正在播放") else Utils.t("Paused", "已暂停")
                    },
                )
            }
        }
    }

}

@Composable
private fun PlaybackSequenceButton(
    previous: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(52.dp)
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                CircleShape,
            ),
    ) {
        Icon(
            imageVector = if (previous) Icons.AutoMirrored.Filled.ArrowBack
            else Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = if (previous) {
                Utils.t("Previous recording", "上一个录像")
            } else {
                Utils.t("Next recording", "下一个录像")
            },
        )
    }
}

@Composable
private fun PlaybackInfoCard(
    diagnostics: PlaybackDiagnostics?,
    file: File,
    status: String,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                Utils.t("Recording information", "录像信息"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            PlaybackInfoRow(Utils.t("Status", "状态"), status)
            PlaybackInfoRow(Utils.t("Recorded", "录像时间"), Utils.formatEpoch(file.lastModified()))
            PlaybackInfoRow(Utils.t("Duration", "时长"), formatPlaybackTime(diagnostics?.durationMs ?: 0L))
            PlaybackInfoRow(Utils.t("File size", "文件大小"), formatPlaybackBytes(file.length()))
            PlaybackInfoRow(
                Utils.t("Resolution", "分辨率"),
                if (diagnostics?.width != null && diagnostics.height != null) {
                    "${diagnostics.width}×${diagnostics.height}"
                } else {
                    Utils.t("Reading…", "读取中")
                },
            )
        }
    }
}

@Composable
private fun PlaybackInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlaybackTimeline(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    enabled: Boolean,
    onDragStart: (Long) -> Unit,
    onDrag: (Long) -> Unit,
    onDragFinished: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggle: () -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatPlaybackTime(positionMs), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = positionMs.coerceIn(0L, safeDuration).toFloat(),
                onValueChange = {
                    val next = it.toLong().coerceIn(0L, safeDuration)
                    onDragStart(next)
                    onDrag(next)
                },
                onValueChangeFinished = onDragFinished,
                valueRange = 0f..safeDuration.toFloat(),
                enabled = enabled && durationMs > 0L,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text(formatPlaybackTime(durationMs), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onSeekBy(-10_000L) },
                enabled = enabled,
            ) { Text(Utils.t("Back 10s", "后退 10 秒")) }
            Button(
                onClick = onToggle,
                enabled = enabled,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Text(if (playing) Utils.t("Pause", "暂停") else Utils.t("Play", "播放"), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { onSeekBy(10_000L) },
                enabled = enabled,
            ) { Text(Utils.t("Forward 10s", "前进 10 秒")) }
        }
    }
}

@Composable
private fun FourLanePlaybackSurface(
    file: File,
    container: FourLaneTextureContainer,
    displayMode: FourLaneDisplayMode,
    lensMode: FourLaneLensMode,
    correctionConfig: FisheyeCorrectionConfig,
    callbacks: PlaybackSurfaceCallbacks,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { container },
        update = {
            it.displayMode = displayMode
            it.lensMode = lensMode
            it.correctionConfig = correctionConfig
        },
        modifier = modifier,
    )

    PlaybackMediaBinding(file, container.textureView, callbacks)
}

@Composable
private fun SinglePlaybackSurface(
    file: File,
    textureView: TextureView,
    callbacks: PlaybackSurfaceCallbacks,
    modifier: Modifier = Modifier,
) {
    AndroidView(factory = { textureView }, modifier = modifier)
    PlaybackMediaBinding(file, textureView, callbacks)
}

@Composable
private fun PlaybackMediaBinding(
    file: File,
    textureView: TextureView,
    callbacks: PlaybackSurfaceCallbacks,
) {
    DisposableEffect(file, textureView) {
        var player: MediaPlayer? = null
        var outputSurface: Surface? = null

        fun releasePlayer() {
            callbacks.onControlsReady(null)
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            runCatching { outputSurface?.release() }
            outputSurface = null
        }

        fun openPlayer(texture: SurfaceTexture?) {
            if (texture == null || player != null) return
            try {
                val surface = Surface(texture)
                outputSurface = surface
                val mediaPlayer = MediaPlayer()
                player = mediaPlayer
                mediaPlayer.setSurface(surface)
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.isLooping = false
                mediaPlayer.setOnPreparedListener {
                    val duration = runCatching { it.duration.toLong() }.getOrDefault(0L)
                    callbacks.onControlsReady(
                        PlaybackControls(
                            toggle = {
                                runCatching {
                                    if (it.isPlaying) {
                                        it.pause()
                                        false
                                    } else {
                                        if (it.duration > 0 && it.currentPosition >= it.duration - 250) {
                                            it.seekTo(0)
                                        }
                                        it.start()
                                        true
                                    }
                                }.getOrDefault(false)
                            },
                            seekTo = { target ->
                                runCatching { it.seekTo(target.coerceAtLeast(0L).toInt()) }
                            },
                            currentPosition = {
                                runCatching { it.currentPosition.toLong() }.getOrDefault(0L)
                            },
                            duration = {
                                runCatching { it.duration.toLong() }.getOrDefault(duration)
                            },
                            isPlaying = {
                                runCatching { it.isPlaying }.getOrDefault(false)
                            },
                        ),
                    )
                    it.start()
                    callbacks.onPrepared(duration)
                }
                mediaPlayer.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        callbacks.onFirstFrame()
                    }
                    false
                }
                mediaPlayer.setOnCompletionListener { callbacks.onCompleted() }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    callbacks.onError(Utils.t("This recording cannot currently be played on the head unit. Send it to your phone to view it.", "这段录像暂时无法在车机上播放，可以发送到手机查看。"))
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (_: Throwable) {
                callbacks.onError(Utils.t("This recording cannot currently be played on the head unit. Send it to your phone to view it.", "这段录像暂时无法在车机上播放，可以发送到手机查看。"))
                releasePlayer()
            }
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                openPlayer(texture)
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }

        textureView.surfaceTextureListener = listener
        if (textureView.isAvailable) openPlayer(textureView.surfaceTexture)

        onDispose {
            if (textureView.surfaceTextureListener === listener) {
                textureView.surfaceTextureListener = null
            }
            releasePlayer()
        }
    }
}

private fun formatPlaybackTime(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatPlaybackBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun playbackSourceLabel(role: RecordingSourceRole?): String = when (role) {
    RecordingSourceRole.CABIN -> "Cabin"
    RecordingSourceRole.IR -> "IR"
    else -> "360°"
}
