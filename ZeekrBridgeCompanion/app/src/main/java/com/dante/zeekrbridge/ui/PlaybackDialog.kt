package com.dante.zeekrbridge.ui

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.dante.zeekrbridge.core.IndexedLayoutKind
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.player.FourLaneGlDiagnostic
import com.dante.zeekrbridge.player.FourLaneGlView
import com.dante.zeekrbridge.player.PlaybackTimeline
import com.dante.zeekrbridge.player.SurroundOutputPath
import com.dante.zeekrbridge.player.SurroundPlaybackBoundary
import com.dante.zeekrbridge.player.canPreparePlayback
import com.dante.zeekrbridge.player.classifySurroundPlayback
import com.dante.zeekrbridge.player.probeSurroundCodec
import java.io.File
import kotlinx.coroutines.delay

private data class PlaybackEntry(
    val id: String,
    val file: File,
    val durationMs: Long,
    val sourceRole: IndexedSourceRole,
    val layoutKind: IndexedLayoutKind,
    val laneLabels: List<String>,
    val originalWidth: Int?,
    val originalHeight: Int?,
)

@Composable
fun MediaPlaybackDialog(
    file: File,
    laneLabels: List<String>,
    onDismiss: () -> Unit,
) {
    PlaylistPlaybackDialog(
        title = file.name,
        entries = listOf(
            PlaybackEntry(
                id = file.absolutePath,
                file = file,
                durationMs = 0L,
                sourceRole = IndexedSourceRole.UNKNOWN,
                layoutKind = if (laneLabels.size == 4) IndexedLayoutKind.FOUR_LANE_V1 else IndexedLayoutKind.UNKNOWN,
                laneLabels = laneLabels,
                originalWidth = null,
                originalHeight = null,
            ),
        ),
        initialIndex = 0,
        onDismiss = onDismiss,
    )
}

@Composable
fun MediaSessionPlaybackDialog(
    segments: List<IndexedMediaSegment>,
    initialIndex: Int,
    isEvent: Boolean,
    onDismiss: () -> Unit,
) {
    val entries = remember(segments) {
        segments.map { segment ->
            PlaybackEntry(
                id = segment.id,
                file = segment.file,
                durationMs = segment.durationMs,
                sourceRole = segment.sourceRole,
                layoutKind = segment.layoutKind,
                laneLabels = segment.playbackLabels,
                originalWidth = segment.originalWidth,
                originalHeight = segment.originalHeight,
            )
        }
    }
    PlaylistPlaybackDialog(
        title = if (isEvent) t("Incident playback", "事件连续播放") else t("Session playback", "Session 连续播放"),
        entries = entries,
        initialIndex = initialIndex,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PlaylistPlaybackDialog(
    title: String,
    entries: List<PlaybackEntry>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playable = entries.filter { it.file.isFile }
    val missingCount = entries.size - playable.size
    val first = playable.firstOrNull()
    val compatible = first != null && playable.all {
        it.sourceRole == first.sourceRole && it.layoutKind == first.layoutKind
    }
    if (!compatible) {
        SimplePlaybackMessage(
            title = title,
            message = if (first == null) {
                t("The recording files are missing.", "录像文件已缺失。")
            } else {
                t(
                    "This session contains mixed camera layouts and cannot be played continuously.",
                    "这个 Session 包含不同摄像头布局，无法安全连续播放。",
                )
            },
            onDismiss = onDismiss,
        )
        return
    }

    val player = remember(playable) { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(playable.indices)) }
    var currentGlobalMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(playable.sumOf { it.durationMs.coerceAtLeast(0L) }) }
    var sliderMs by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableIntStateOf(FourLaneGlView.MODE_GRID) }
    var surroundFirstFrameRendered by remember(first.id) { mutableStateOf(false) }
    var useRawSurroundSurface by remember(first.id) { mutableStateOf(false) }
    var playerPlaybackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playerFirstFrameCount by remember(first.id) { mutableIntStateOf(0) }
    var playerVideoWidth by remember(first.id) { mutableIntStateOf(0) }
    var playerVideoHeight by remember(first.id) { mutableIntStateOf(0) }
    var surfaceTextureFrameCount by remember(first.id) { mutableLongStateOf(0L) }
    var glMaxTextureSize by remember(first.id) { mutableIntStateOf(0) }
    var glRenderError by remember(first.id) { mutableStateOf<String?>(null) }
    var glDiagnostic by remember(first.id) { mutableStateOf<FourLaneGlDiagnostic?>(null) }
    val codecProbe = remember(first.file.absolutePath) { probeSurroundCodec(first.file) }

    DisposableEffect(player, playable) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex.coerceIn(playable.indices)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playerPlaybackState = playbackState
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerVideoWidth = videoSize.width
                playerVideoHeight = videoSize.height
            }

            override fun onRenderedFirstFrame() {
                playerFirstFrameCount += 1
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = t(
                    "Playback failed: ${playbackError.errorCodeName}",
                    "播放失败：${playbackError.errorCodeName}",
                )
            }
        }
        player.addListener(listener)
        playerPlaybackState = player.playbackState
        player.setMediaItems(
            playable.map { entry ->
                MediaItem.Builder().setMediaId(entry.id).setUri(Uri.fromFile(entry.file)).build()
            },
            currentIndex,
            0L,
        )
        player.playWhenReady = true
        if (canPreparePlayback(first.layoutKind, customSurfaceAttached = false)) {
            player.prepare()
        }
        onDispose {
            player.removeListener(listener)
            player.clearVideoSurface()
            player.release()
        }
    }

    LaunchedEffect(player, playable, dragging) {
        while (true) {
            val playerIndex = player.currentMediaItemIndex.coerceIn(playable.indices)
            val timeline = PlaybackTimeline(resolvedDurations(playable, player, playerIndex))
            currentIndex = playerIndex
            currentGlobalMs = timeline.globalPosition(playerIndex, player.currentPosition.coerceAtLeast(0L))
            totalDurationMs = timeline.totalDurationMs
            if (!dragging) sliderMs = currentGlobalMs.toFloat()
            delay(250L)
        }
    }

    fun seekGlobal(targetMs: Long) {
        val playerIndex = player.currentMediaItemIndex.coerceIn(playable.indices)
        val timeline = PlaybackTimeline(resolvedDurations(playable, player, playerIndex))
        val position = timeline.resolve(targetMs)
        player.seekTo(position.mediaItemIndex, position.positionMs)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MaterialSurface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(t("Back", "返回")) }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${currentIndex + 1}/${playable.size}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                if (missingCount > 0) {
                    Text(
                        t(
                            "$missingCount missing segments were skipped.",
                            "已跳过 $missingCount 个缺失片段。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (first.layoutKind == IndexedLayoutKind.FOUR_LANE_V1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!useRawSurroundSurface) {
                            Button(onClick = {}) { Text(t("GL four-view", "GL 四路")) }
                        } else {
                            OutlinedButton(onClick = {
                                useRawSurroundSurface = false
                                playerFirstFrameCount = 0
                                surroundFirstFrameRendered = false
                                surfaceTextureFrameCount = 0L
                                glRenderError = null
                                glDiagnostic = null
                                error = null
                            }) { Text(t("GL four-view", "GL 四路")) }
                        }
                        if (useRawSurroundSurface) {
                            Button(onClick = {}) { Text(t("Raw decoder test", "原始解码测试")) }
                        } else {
                            OutlinedButton(onClick = {
                                useRawSurroundSurface = true
                                playerFirstFrameCount = 0
                                surroundFirstFrameRendered = false
                                surfaceTextureFrameCount = 0L
                                glRenderError = null
                                glDiagnostic = null
                                error = null
                            }) { Text(t("Raw decoder test", "原始解码测试")) }
                        }
                    }

                    if (useRawSurroundSurface) {
                        RawDiagnosticVideoSurface(
                            player = player,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    } else {
                        FourLaneVideoSurface(
                            player = player,
                            entry = first,
                            mode = mode,
                            onFirstFrame = { surroundFirstFrameRendered = true },
                            onSurfaceFrame = { count -> surfaceTextureFrameCount = count },
                            onGlMaxTextureSize = { size -> glMaxTextureSize = size },
                            onGlDiagnostic = { snapshot -> glDiagnostic = snapshot },
                            onRenderError = { message ->
                                glRenderError = message
                                error = t(
                                    "360° rendering failed: $message",
                                    "360° 渲染失败：$message",
                                )
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(if (mode == FourLaneGlView.MODE_GRID) 2f else 1.6f),
                        )
                    }
                    if ((!useRawSurroundSurface && !surroundFirstFrameRendered) ||
                        (useRawSurroundSurface && playerFirstFrameCount == 0)
                    ) {
                        Text(
                            if (useRawSurroundSurface) {
                                t("Waiting for raw decoder frame…", "正在等待原始解码首帧…")
                            } else {
                                t("Preparing 360° video…", "正在准备 360° 画面…")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    if (!useRawSurroundSurface) {
                        val labels = first.laneLabels.takeIf { it.size == 4 }
                            ?: listOf(t("Front", "前"), t("Rear", "后"), t("Left", "左"), t("Right", "右"))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(onClick = { mode = FourLaneGlView.MODE_GRID }) { Text("2×2") }
                            labels.forEachIndexed { index, label ->
                                OutlinedButton(onClick = { mode = FourLaneGlView.MODE_LANE_1 + index }) { Text(label) }
                            }
                        }
                    }

                    val outputPath = if (useRawSurroundSurface) {
                        SurroundOutputPath.RAW_SURFACE
                    } else {
                        SurroundOutputPath.GL_CROPPED
                    }
                    val boundary = classifySurroundPlayback(
                        outputPath = outputPath,
                        playerFirstFrames = playerFirstFrameCount,
                        surfaceTextureFrames = surfaceTextureFrameCount,
                        glFirstFrame = surroundFirstFrameRendered,
                    )
                    SurroundDiagnosticPanel(
                        outputPath = outputPath,
                        boundary = boundary,
                        playerState = playerPlaybackState,
                        playerFirstFrames = playerFirstFrameCount,
                        playerVideoWidth = playerVideoWidth,
                        playerVideoHeight = playerVideoHeight,
                        surfaceTextureFrames = surfaceTextureFrameCount,
                        glFirstFrame = surroundFirstFrameRendered,
                        glMaxTextureSize = glMaxTextureSize,
                        glRenderError = glRenderError,
                        glDiagnostic = glDiagnostic,
                        codecMime = codecProbe.mime,
                        codecWidth = codecProbe.width,
                        codecHeight = codecProbe.height,
                        process64Bit = codecProbe.process64Bit,
                        supportedDecoders = codecProbe.supportedDecoders,
                        rejectedDecoders = codecProbe.rejectedDecoders,
                        codecProbeError = codecProbe.error,
                    )
                } else {
                    PlainVideoSurface(
                        player = player,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                }

                error?.let { Text(it, color = Color(0xFFEF5350), modifier = Modifier.padding(top = 8.dp)) }
                if (totalDurationMs > 0L) {
                    Slider(
                        value = sliderMs.coerceIn(0f, totalDurationMs.toFloat()),
                        onValueChange = {
                            dragging = true
                            sliderMs = it
                        },
                        onValueChangeFinished = {
                            seekGlobal(sliderMs.toLong())
                            dragging = false
                        },
                        valueRange = 0f..totalDurationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            formatPlayerTime(if (dragging) sliderMs.toLong() else currentGlobalMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatPlayerTime(totalDurationMs), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        seekGlobal((currentGlobalMs - 10_000L).coerceAtLeast(0L))
                    }) { Text("−10s") }
                    Button(onClick = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                            player.play()
                        }
                    }) { Text(if (isPlaying) t("Pause", "暂停") else t("Play", "播放")) }
                    OutlinedButton(onClick = {
                        seekGlobal((currentGlobalMs + 10_000L).coerceAtMost(totalDurationMs))
                    }) { Text("+10s") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("Speed", "速度"), style = MaterialTheme.typography.bodySmall)
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { candidate ->
                        val label = if (candidate % 1f == 0f) "${candidate.toInt()}×" else "${candidate}×"
                        if (speed == candidate) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(onClick = {
                                speed = candidate
                                player.setPlaybackSpeed(candidate)
                            }) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FourLaneVideoSurface(
    player: ExoPlayer,
    entry: PlaybackEntry,
    mode: Int,
    onFirstFrame: () -> Unit,
    onSurfaceFrame: (Long) -> Unit,
    onGlMaxTextureSize: (Int) -> Unit,
    onGlDiagnostic: (FourLaneGlDiagnostic) -> Unit,
    onRenderError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val glView = remember { FourLaneGlView(context) }
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentOnSurfaceFrame by rememberUpdatedState(onSurfaceFrame)
    val currentOnGlMaxTextureSize by rememberUpdatedState(onGlMaxTextureSize)
    val currentOnGlDiagnostic by rememberUpdatedState(onGlDiagnostic)
    val currentOnRenderError by rememberUpdatedState(onRenderError)
    LaunchedEffect(mode) {
        glView.setMode(mode)
        glView.requestRender()
    }
    DisposableEffect(player, glView, entry) {
        var disposed = false
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        glView.setPlaybackCallbacks(
            onFirstFrame = { currentOnFirstFrame() },
            onSurfaceFrame = { count -> currentOnSurfaceFrame(count) },
            onGlMaxTextureSize = { size -> currentOnGlMaxTextureSize(size) },
            onGlDiagnostic = { snapshot -> currentOnGlDiagnostic(snapshot) },
            onRenderError = { message -> currentOnRenderError(message) },
        )
        glView.createSurfaceTexture { created ->
            if (disposed) {
                created.release()
                return@createSurfaceTexture
            }
            texture = created
            val size = readVideoSize(entry)
            if (size.first > 0 && size.second > 0) {
                created.setDefaultBufferSize(size.first, size.second)
                glView.setVideoSize(size.first, size.second)
            }
            val nextSurface = Surface(created)
            surface = nextSurface
            glView.setSource(created)
            player.setVideoSurface(nextSurface)
            if (
                canPreparePlayback(entry.layoutKind, customSurfaceAttached = true) &&
                player.playbackState == Player.STATE_IDLE &&
                player.mediaItemCount > 0
            ) {
                player.prepare()
            }
        }
        onDispose {
            disposed = true
            glView.setPlaybackCallbacks(null, null, null, null, null)
            surface?.let { runCatching { player.clearVideoSurface(it) } }
            glView.setSource(null)
            surface?.release()
            texture?.release()
            glView.onPause()
        }
    }
    AndroidView(factory = { glView }, modifier = modifier)
}

@Composable
private fun RawDiagnosticVideoSurface(player: ExoPlayer, modifier: Modifier) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }
    DisposableEffect(player, surfaceView) {
        player.setVideoSurfaceView(surfaceView)
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        onDispose { player.clearVideoSurfaceView(surfaceView) }
    }
    AndroidView(factory = { surfaceView }, modifier = modifier)
}

@Composable
private fun SurroundDiagnosticPanel(
    outputPath: SurroundOutputPath,
    boundary: SurroundPlaybackBoundary,
    playerState: Int,
    playerFirstFrames: Int,
    playerVideoWidth: Int,
    playerVideoHeight: Int,
    surfaceTextureFrames: Long,
    glFirstFrame: Boolean,
    glMaxTextureSize: Int,
    glRenderError: String?,
    glDiagnostic: FourLaneGlDiagnostic?,
    codecMime: String,
    codecWidth: Int,
    codecHeight: Int,
    process64Bit: Boolean,
    supportedDecoders: List<String>,
    rejectedDecoders: List<String>,
    codecProbeError: String?,
) {
    MaterialSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(t("360° diagnostic", "360° 诊断"), style = MaterialTheme.typography.titleSmall)
            Text("Output: ${outputPath.name} · Boundary: ${boundary.name}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Player: ${playerStateLabel(playerState)} · firstFrame=$playerFirstFrames · " +
                    "video=${playerVideoWidth}×${playerVideoHeight}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "SurfaceTexture: frames=$surfaceTextureFrames · GL firstFrame=$glFirstFrame · " +
                    "GL maxTexture=${if (glMaxTextureSize > 0) glMaxTextureSize else "pending"}",
                style = MaterialTheme.typography.bodySmall,
            )
            glDiagnostic?.let { diagnostic ->
                Text(
                    "GL core: program=${diagnostic.programId} · texture=${diagnostic.textureId} · " +
                        "source=${diagnostic.sourceAttached}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "GL draw: calls=${diagnostic.drawCalls} · updateOK=${diagnostic.updateTexImageSuccesses} · " +
                        "last=${diagnostic.lastExitReason}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Shader: VS=${diagnostic.vertexShaderStatus} · FS=${diagnostic.fragmentShaderStatus} · " +
                        "LINK=${diagnostic.programLinkStatus}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Locations: a=${diagnostic.attributeLocation} · u=${diagnostic.uniformLocations}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "GPU: ${diagnostic.glVendor} · ${diagnostic.glRenderer} · ${diagnostic.glVersion}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (diagnostic.shaderLog != "none") {
                    Text("Shader log: ${diagnostic.shaderLog}", color = Color(0xFFEF5350))
                }
            }
            Text(
                "Codec probe: $codecMime ${codecWidth}×${codecHeight} · process=${if (process64Bit) "64-bit" else "32-bit"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Supported: ${supportedDecoders.take(3).joinToString().ifBlank { "NONE" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (supportedDecoders.isEmpty() && rejectedDecoders.isNotEmpty()) {
                Text(
                    "Rejected: ${rejectedDecoders.take(3).joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            codecProbeError?.let { Text("Codec probe error: $it", color = Color(0xFFEF5350)) }
            glRenderError?.let { Text("GL: $it", color = Color(0xFFEF5350)) }
        }
    }
}

private fun playerStateLabel(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> state.toString()
}

@Composable
private fun PlainVideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).also(player::setVideoSurfaceView)
        },
        modifier = modifier,
    )
}

@Composable
private fun SimplePlaybackMessage(title: String, message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        MaterialSurface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(t("Close", "关闭"))
                }
            }
        }
    }
}

private fun resolvedDurations(
    entries: List<PlaybackEntry>,
    player: ExoPlayer,
    playerIndex: Int,
): List<Long> = entries.mapIndexed { index, entry ->
    when {
        index == playerIndex && player.duration > 0L -> player.duration
        entry.durationMs > 0L -> entry.durationMs
        else -> 0L
    }
}

private fun readVideoSize(entry: PlaybackEntry): Pair<Int, Int> {
    val retriever = MediaMetadataRetriever()
    val detected = try {
        retriever.setDataSource(entry.file.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        width to height
    } catch (_: Throwable) {
        0 to 0
    } finally {
        runCatching { retriever.release() }
    }
    return detected.takeIf { it.first > 0 && it.second > 0 }
        ?: ((entry.originalWidth ?: 0) to (entry.originalHeight ?: 0))
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
