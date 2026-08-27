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
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.player.PlaybackDiagnostics
import com.dante.zeekrcapabilitylab.player.PlaybackInspector
import com.dante.zeekrcapabilitylab.player.RecordingPlaybackTimeline
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.util.Utils
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

private data class RecordingPlaybackInfo(
    val diagnostics: List<PlaybackDiagnostics>,
    val durationsMs: List<Long>,
    val frontOnly: List<Boolean>,
    val recordedAtEpochMs: Long,
)

/**
 * Product playback surface. Composite sidecars use the four-lane redraw path;
 * FRONT_ONLY sidecars render the encoded square frame directly and never
 * reinterpret it as another four-lane source.
 */
@Composable
fun FourLanePlayerDialog(
    files: List<File>,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onSendToPhone: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    require(files.isNotEmpty()) { "A recording must contain at least one segment" }
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val languageMode by AppLanguage.mode.collectAsState()
    val recordingFiles = remember(files) { files.distinctBy { it.absolutePath } }
    val recordingKey = remember(recordingFiles) { recordingFiles.joinToString("|") { it.absolutePath } }
    val playbackInfo by produceState<RecordingPlaybackInfo?>(initialValue = null, recordingKey) {
        value = withContext(Dispatchers.IO) {
            val sidecars = recordingFiles.map { file ->
                SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
            }
            val diagnostics = recordingFiles.map(PlaybackInspector::inspect)
            RecordingPlaybackInfo(
                diagnostics = diagnostics,
                durationsMs = recordingFiles.indices.map { index ->
                    diagnostics[index].durationMs
                        ?: sidecars[index]?.actualTrack?.durationMs
                        ?: sidecars[index]?.let { sidecar ->
                            val start = sidecar.startedAtEpochMs
                            val stop = sidecar.stoppedAtEpochMs
                            if (start != null && stop != null) stop - start else null
                        }
                        ?: sidecars[index]?.segmentSeconds?.times(1000L)
                        ?: 0L
                },
                frontOnly = sidecars.map { it?.recordingMode == RecordingMode.FRONT_ONLY },
                recordedAtEpochMs = recordingFiles.indices.minOf { index ->
                    sidecars[index]?.startedAtEpochMs ?: recordingFiles[index].lastModified()
                },
            )
        }
    }
    val directionLabels = productDirectionLabels()

    val timeline = remember(playbackInfo, recordingKey) {
        RecordingPlaybackTimeline(playbackInfo?.durationsMs ?: List(recordingFiles.size) { 0L })
    }
    var activeIndex by remember(recordingKey) { mutableStateOf(0) }
    val file = recordingFiles[activeIndex.coerceIn(recordingFiles.indices)]
    val diagnostics = playbackInfo?.diagnostics?.getOrNull(activeIndex)
    val frontOnly = playbackInfo?.frontOnly?.getOrNull(activeIndex) == true
    val durationMs = timeline.totalDurationMs
    val totalBytes = remember(recordingKey) { recordingFiles.sumOf { it.length().coerceAtLeast(0L) } }

    var controls by remember(recordingKey) { mutableStateOf<PlaybackControls?>(null) }
    var playing by remember(recordingKey) { mutableStateOf(false) }
    var firstFrame by remember(recordingKey, activeIndex) { mutableStateOf(false) }
    var positionMs by remember(recordingKey) { mutableStateOf(0L) }
    var segmentStartPositionMs by remember(recordingKey) { mutableStateOf(0L) }
    var autoPlaySegment by remember(recordingKey) { mutableStateOf(true) }
    var dragging by remember(recordingKey) { mutableStateOf(false) }
    var draggedPositionMs by remember(recordingKey) { mutableStateOf(0L) }
    var status by remember(recordingKey, languageMode) {
        mutableStateOf(Utils.t("Opening recording…", "正在打开录像…"))
    }
    var error by remember(recordingKey, activeIndex) { mutableStateOf<String?>(null) }
    var displayMode by remember(recordingKey) { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
    var lensMode by remember(recordingKey) { mutableStateOf(settings.lensMode) }
    var zoom by remember(recordingKey) { mutableStateOf(1f) }
    // AndroidView keeps the attached View across recompositions. Reuse that
    // same attached container when previous/next changes the media source.
    val playbackContainer = remember(context) { FourLaneTextureContainer(context) }

    DisposableEffect(recordingKey) {
        recordingFiles.forEach(PlaybackPinRegistry::acquire)
        onDispose { recordingFiles.forEach(PlaybackPinRegistry::release) }
    }

    BackHandler(enabled = displayMode.singleLane != null) {
        displayMode = FourLaneDisplayMode.FOUR_GRID
        zoom = playbackContainer.resetViewport()
    }

    LaunchedEffect(controls, activeIndex, timeline) {
        val active = controls ?: return@LaunchedEffect
        while (true) {
            val localPosition = runCatching { active.currentPosition() }.getOrDefault(0L)
            positionMs = timeline.globalPosition(activeIndex, localPosition)
            playing = runCatching { active.isPlaying() }.getOrDefault(false)
            delay(250L)
        }
    }

    val seekRecordingTo: (Long) -> Unit = { requestedPosition ->
        val target = requestedPosition.coerceIn(0L, durationMs)
        val located = timeline.locate(target)
        if (located.segmentIndex == activeIndex) {
            controls?.seekTo(located.positionInSegmentMs)
        } else {
            val resume = playing
            controls = null
            segmentStartPositionMs = located.positionInSegmentMs
            autoPlaySegment = resume
            activeIndex = located.segmentIndex.coerceIn(recordingFiles.indices)
        }
        positionMs = target
    }

    Dialog(
        onDismissRequest = {
            if (displayMode.singleLane != null) {
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
                            Utils.t("Playback", "录像回放"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            Utils.formatEpoch(playbackInfo?.recordedAtEpochMs ?: recordingFiles.first().lastModified()),
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
                                Modifier
                                    .size(previewSide)
                                    .background(Color.Black, RoundedCornerShape(14.dp)),
                            ) {
                                FourLanePlaybackSurface(
                                    file = file,
                                    container = playbackContainer,
                                    displayMode = displayMode,
                                    lensMode = lensMode,
                                    correctionConfig = settings.fisheyeCorrection,
                                    directSingleView = frontOnly,
                                    startPositionMs = segmentStartPositionMs,
                                    autoPlay = autoPlaySegment,
                                    onControlsReady = { controls = it },
                                    onPrepared = {
                                        playing = autoPlaySegment
                                        status = if (playing) {
                                            Utils.t("Playing", "正在播放")
                                        } else {
                                            Utils.t("Paused", "已暂停")
                                        }
                                    },
                                    onFirstFrame = {
                                        firstFrame = true
                                        error = null
                                        status = if (playing) Utils.t("Playing", "正在播放") else Utils.t("Paused", "已暂停")
                                    },
                                    onCompleted = {
                                        if (activeIndex < recordingFiles.lastIndex) {
                                            controls = null
                                            positionMs = timeline.globalPosition(activeIndex + 1, 0L)
                                            segmentStartPositionMs = 0L
                                            autoPlaySegment = true
                                            activeIndex += 1
                                            status = Utils.t("Playing", "正在播放")
                                        } else {
                                            positionMs = durationMs
                                            playing = false
                                            status = Utils.t("Playback complete", "播放完成")
                                        }
                                    },
                                    onError = { message ->
                                        EventLogger.logEvent(
                                            category = Categories.MEDIA_SESSION,
                                            eventName = "RECORDING_PLAYBACK_FAILED",
                                            severity = Severity.ERROR,
                                            payload = mapOf(
                                                "file" to file.name,
                                                "segmentIndex" to activeIndex.toString(),
                                                "segmentCount" to recordingFiles.size.toString(),
                                                "inspectionError" to (diagnostics?.error ?: "-"),
                                            ),
                                            errorMessage = message,
                                        )
                                        error = message
                                        playing = false
                                        status = Utils.t("Unable to play", "无法播放")
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (!frontOnly) FourLaneDirectionOverlay(
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
                                if (!frontOnly) FourLaneLensToggle(
                                    mode = lensMode,
                                    onModeChanged = { selected ->
                                        lensMode = selected
                                        settings.setLensMode(selected)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp),
                                )
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
                            recordedAtEpochMs = playbackInfo?.recordedAtEpochMs
                                ?: recordingFiles.first().lastModified(),
                            durationMs = durationMs,
                            totalBytes = totalBytes,
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
                        seekRecordingTo(draggedPositionMs)
                        dragging = false
                    },
                    onSeekBy = { delta ->
                        val target = (positionMs + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                        seekRecordingTo(target)
                    },
                    onToggle = {
                        if (!playing && positionMs >= durationMs && durationMs > 0L) {
                            segmentStartPositionMs = 0L
                            autoPlaySegment = true
                            controls = null
                            activeIndex = 0
                            positionMs = 0L
                            playing = true
                        } else {
                            playing = controls?.toggle?.invoke() ?: false
                            autoPlaySegment = playing
                        }
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
    recordedAtEpochMs: Long,
    durationMs: Long,
    totalBytes: Long,
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
            PlaybackInfoRow(Utils.t("Recorded", "录像时间"), Utils.formatEpoch(recordedAtEpochMs))
            PlaybackInfoRow(Utils.t("Duration", "时长"), formatPlaybackTime(durationMs))
            PlaybackInfoRow(Utils.t("File size", "文件大小"), formatPlaybackBytes(totalBytes))
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
    directSingleView: Boolean,
    startPositionMs: Long,
    autoPlay: Boolean,
    onControlsReady: (PlaybackControls?) -> Unit,
    onPrepared: () -> Unit,
    onFirstFrame: () -> Unit,
    onCompleted: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { container },
        update = {
            it.directSingleView = directSingleView
            it.displayMode = displayMode
            it.lensMode = lensMode
            it.correctionConfig = correctionConfig
        },
        modifier = modifier,
    )

    DisposableEffect(file, container) {
        val textureView = container.textureView
        var player: MediaPlayer? = null
        var outputSurface: Surface? = null

        fun releasePlayer() {
            onControlsReady(null)
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
                    onControlsReady(
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
                    if (startPositionMs > 0L) {
                        runCatching { it.seekTo(startPositionMs.coerceIn(0L, duration).toInt()) }
                    }
                    if (autoPlay) it.start()
                    onPrepared()
                }
                mediaPlayer.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        onFirstFrame()
                    }
                    false
                }
                mediaPlayer.setOnCompletionListener { onCompleted() }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    onError(Utils.t("This recording cannot currently be played on the head unit. Send it to your phone to view it.", "这段录像暂时无法在车机上播放，可以发送到手机查看。"))
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (_: Throwable) {
                onError(Utils.t("This recording cannot currently be played on the head unit. Send it to your phone to view it.", "这段录像暂时无法在车机上播放，可以发送到手机查看。"))
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
