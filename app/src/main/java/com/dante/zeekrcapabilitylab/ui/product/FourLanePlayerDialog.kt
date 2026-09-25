package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import android.graphics.Bitmap
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.player.PlaybackDiagnostics
import com.dante.zeekrcapabilitylab.player.PlaybackTimeline
import com.dante.zeekrcapabilitylab.player.RecordingPlaylistPlayer
import com.dante.zeekrcapabilitylab.player.PlaybackInspector
import com.dante.zeekrcapabilitylab.player.PlaybackTrackText
import com.dante.zeekrcapabilitylab.player.RecordingThumbnailCache
import com.dante.zeekrcapabilitylab.player.TriggerTimeline
import com.dante.zeekrcapabilitylab.player.TriggerTimelineSegment
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.player.PlaybackRasterBatch
import com.dante.zeekrcapabilitylab.player.RecordingRasterReader
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.VideoTriggerMarker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PlaybackControls(
    val toggle: () -> Boolean,
    val seekTo: (Long) -> Unit,
    val currentPosition: () -> Long,
    val duration: () -> Long,
    val isPlaying: () -> Boolean,
    val segmentIndex: () -> Int,
    val seekToSegment: (Int) -> Unit,
    val segmentDurations: () -> List<Long>,
    val seekToSegmentPosition: (Int, Long) -> Unit,
)

private data class PlaybackSurfaceCallbacks(
    val onControlsReady: (PlaybackControls?) -> Unit,
    val onPrepared: (Long) -> Unit,
    val onFirstFrame: () -> Unit,
    val onCompleted: () -> Unit,
    val onError: (String) -> Unit,
)

private data class PlaybackSegmentItem(
    val file: File,
    val segmentNumber: Int,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val protected: Boolean,
    val triggerMarkers: List<VideoTriggerMarker> = emptyList(),
    val modifiedAtEpochMs: Long = 0L,
    val sidecar: SegmentSidecar? = null,
)

/**
 * Product playback surface. It deliberately mirrors the live-preview path:
 * one ordinary TextureView receives the 1280x5140 composite and the shared
 * FourLaneTextureContainer redraws its four vertical lanes as a 2x2 grid.
 */
@Composable
fun FourLanePlayerDialog(
    file: File,
    files: List<File> = listOf(file),
    segmentDurationHintsMs: Map<String, Long> = emptyMap(),
    triggerMarkersByFile: Map<String, List<VideoTriggerMarker>> = emptyMap(),
    manualEventEpochs: List<Long> = emptyList(),
    layoutKind: RecordingLayoutKind? = RecordingLayoutKind.FOUR_LANE_V1,
    sourceRole: RecordingSourceRole? = RecordingSourceRole.SURROUND,
    recordingMode: RecordingMode = RecordingMode.NORMAL,
    timeLapseMultiplier: Int = 1,
    realDurationMs: Long? = null,
    compositeWidth: Int? = null,
    compositeHeight: Int? = null,
    laneLabels: List<String>? = null,
    recordedAtEpochMs: Long? = null,
    displayTitle: String? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onSendToPhone: ((List<File>) -> Boolean)? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playlist = remember(file, files) {
        files.ifEmpty { listOf(file) }.distinctBy { it.absolutePath }
    }
    val initialItems = remember(playlist, recordedAtEpochMs, segmentDurationHintsMs, triggerMarkersByFile) {
        playlist.mapIndexed { index, media -> PlaybackSegmentItem(media, index + 1,
            recordedAtEpochMs ?: 0L, segmentDurationHintsMs[media.absolutePath] ?: 0L, 0L, false,
            triggerMarkersByFile[media.absolutePath].orEmpty()) }
    }
    val segmentItems by produceState(initialItems, initialItems, manualEventEpochs) {
        value = withContext(Dispatchers.IO) { playlist.mapIndexed { index, segmentFile ->
            val sidecar = SegmentSidecarIO.readForMedia(segmentFile)
            val modifiedAt = segmentFile.lastModified()
            PlaybackSegmentItem(
                file = segmentFile,
                segmentNumber = sidecar?.segmentNumber?.takeIf { it > 0 } ?: (index + 1),
                startedAtEpochMs = sidecar?.startedAtEpochMs
                    ?: sidecar?.requestedAtEpochMs
                    ?: recordedAtEpochMs?.takeIf { playlist.size == 1 }
                    ?: modifiedAt,
                durationMs = sidecar?.actualTrack?.durationMs
                    ?: sidecar?.realDurationMs?.let { real ->
                        if (sidecar.timeLapseMultiplier > 1) real / sidecar.timeLapseMultiplier else real
                    }
                    ?: segmentDurationHintsMs[segmentFile.absolutePath]?.takeIf { it > 0L }
                    ?: 0L,
                sizeBytes = segmentFile.length(),
                protected = sidecar?.protected == true,
                triggerMarkers = (triggerMarkersByFile[segmentFile.absolutePath] ?: sidecar?.triggerMarkers.orEmpty()) +
                    com.dante.zeekrcapabilitylab.player.ManualBookmarkTimeline.from(sidecar, manualEventEpochs),
                modifiedAtEpochMs = modifiedAt,
                sidecar = sidecar,
            )
        } }
    }
    val playableHints = remember(segmentItems) { segmentItems.associate { it.file.absolutePath to it.durationMs } }
    val thumbnailCache = remember(context) {
        RecordingThumbnailCache(File(context.cacheDir, "recording-covers"))
    }
    val settings = remember { SettingsStore.get(context) }
    val languageMode by AppLanguage.mode.collectAsState()
    val directionLabels = laneLabels?.takeIf { it.size == 4 } ?: productDirectionLabels()
    val isFourLane = layoutKind != RecordingLayoutKind.SINGLE_V1

    var controls by remember(playlist) { mutableStateOf<PlaybackControls?>(null) }
    var playing by remember(playlist) { mutableStateOf(false) }
    var firstFrame by remember(playlist) { mutableStateOf(false) }
    var positionMs by remember(playlist) { mutableStateOf(0L) }
    var durationMs by remember(playlist) { mutableStateOf(0L) }
    var playableDurations by remember(segmentItems) { mutableStateOf(segmentItems.map { it.durationMs }) }
    val triggerMarkers = remember(segmentItems, playableDurations) {
        TriggerTimeline.assemble(segmentItems.mapIndexed { index, item ->
            TriggerTimelineSegment(playableDurations.getOrElse(index) { item.durationMs }, item.triggerMarkers)
        })
    }
    var dragging by remember(playlist) { mutableStateOf(false) }
    var draggedPositionMs by remember(playlist) { mutableStateOf(0L) }
    var activeSegmentIndex by remember(playlist) { mutableStateOf(0) }
    var selectedSegmentPaths by remember(playlist) {
        mutableStateOf(playlist.firstOrNull()?.let { setOf(it.absolutePath) } ?: emptySet())
    }
    var status by remember(file, languageMode) {
        mutableStateOf(Utils.t("Opening recording…", "正在打开录像…"))
    }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val inspectTrack = firstFrame || error != null
    val diagnostics by produceState<PlaybackDiagnostics?>(initialValue = null, file, inspectTrack) {
        value = if (inspectTrack) withContext(Dispatchers.IO) { PlaybackInspector.inspect(file) } else null
    }
    var displayMode by remember(file) { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
    var lensMode by remember(file) { mutableStateOf(settings.lensMode) }
    var zoom by remember(file) { mutableStateOf(1f) }
    // AndroidView keeps the attached View across recompositions. Reuse that
    // same attached container when previous/next changes the media source.
    val playbackContainer = remember(context) { FourLaneTextureContainer(context) }
    val singleTextureView = remember(context) { TextureView(context) }

    DisposableEffect(playlist.map { it.absolutePath }) {
        playlist.forEach(PlaybackPinRegistry::acquire)
        onDispose { playlist.forEach(PlaybackPinRegistry::release) }
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
            playableDurations = runCatching { active.segmentDurations() }.getOrDefault(playableDurations)
            playing = runCatching { active.isPlaying() }.getOrDefault(false)
            activeSegmentIndex = runCatching { active.segmentIndex() }.getOrDefault(activeSegmentIndex)
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
                            displayTitle ?: if (recordingMode == RecordingMode.TIME_LAPSE) {
                                Utils.t("Time-lapse", "延时摄影") +
                                    " · ${playbackSourceLabel(sourceRole)} · ${timeLapseMultiplier}×"
                            } else {
                                Utils.t("Playback", "录像回放") + " · " + playbackSourceLabel(sourceRole)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            (recordedAtEpochMs ?: segmentItems.firstOrNull()?.startedAtEpochMs)?.takeIf { it > 0L }
                                ?.let(Utils::formatEpoch) ?: Utils.t("Reading…", "读取中"),
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
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PlaybackSegmentRail(
                        items = segmentItems,
                        activeIndex = activeSegmentIndex,
                        selectedPaths = selectedSegmentPaths,
                        layoutKind = layoutKind ?: RecordingLayoutKind.FOUR_LANE_V1,
                        thumbnailCache = thumbnailCache,
                        onJump = { index ->
                            controls?.seekToSegment?.invoke(index)
                            activeSegmentIndex = index
                            status = Utils.t(
                                "Playing {0}", "正在播放 {0}", formatSegmentClock(segmentItems[index].startedAtEpochMs))
                        },
                        onToggle = { path ->
                            selectedSegmentPaths = if (path in selectedSegmentPaths) {
                                selectedSegmentPaths - path
                            } else {
                                selectedSegmentPaths + path
                            }
                        },
                        onSelectAll = { selectedSegmentPaths = playlist.mapTo(mutableSetOf()) { it.absolutePath } },
                        onClear = { selectedSegmentPaths = emptySet() },
                        modifier = Modifier
                            .weight(0.92f)
                            .fillMaxHeight(),
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1.65f)
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
                                        files = playlist,
                                        segmentDurationHintsMs = playableHints,
                                        container = playbackContainer,
                                        compositeWidth = compositeWidth ?: 1280,
                                        compositeHeight = compositeHeight ?: 5140,
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
                                        files = playlist,
                                        segmentDurationHintsMs = playableHints,
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

                    Column(Modifier.weight(0.93f).fillMaxHeight()) {
                        PlaybackInfoCard(
                            diagnostics = diagnostics,
                            items = segmentItems,
                            durationMs = durationMs,
                            realDurationMs = realDurationMs,
                            recordingMode = recordingMode,
                            status = status,
                            recordedAtEpochMs = recordedAtEpochMs,
                        )
                        error?.let {
                            Text(
                                it,
                                modifier = Modifier.padding(top = 10.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val selectedFiles = playlist.filter { it.absolutePath in selectedSegmentPaths }
                                val queued = onSendToPhone?.invoke(selectedFiles) == true
                                if (queued) status = Utils.t(
                                    "Selected segments added to phone transfer queue",
                                    "选中分段已加入手机传输队列",
                                )
                            },
                            enabled = onSendToPhone != null && selectedSegmentPaths.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            val selectedBytes = segmentItems
                                .filter { it.file.absolutePath in selectedSegmentPaths }
                                .sumOf { it.sizeBytes }
                            Text(
                                Utils.t(
                                    "Send selected ({0}, {1})", "发送选中（{0} 段，{1}）", selectedSegmentPaths.size, formatPlaybackBytes(selectedBytes)),
                                fontWeight = FontWeight.SemiBold,
                            )
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

                SentryTriggerTimeline(
                    markers = triggerMarkers, durationMs = durationMs,
                    positionMs = if (dragging) draggedPositionMs else positionMs,
                    enabled = controls != null,
                    onSeek = { point ->
                        controls?.seekToSegmentPosition?.invoke(point.segmentIndex, point.localPositionMs)
                        positionMs = point.positionMs
                        dragging = false
                    },
                )
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
private fun PlaybackSegmentRail(
    items: List<PlaybackSegmentItem>,
    activeIndex: Int,
    selectedPaths: Set<String>,
    layoutKind: RecordingLayoutKind,
    thumbnailCache: RecordingThumbnailCache,
    onJump: (Int) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(activeIndex, items.size) {
        if (activeIndex in items.indices) gridState.animateScrollToItem(activeIndex)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Utils.t("One-minute segments", "一分钟分段"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onSelectAll, enabled = selectedPaths.size < items.size) {
                Text(Utils.t("All", "全选"))
            }
            TextButton(onClick = onClear, enabled = selectedPaths.isNotEmpty()) {
                Text(Utils.t("Clear", "清除"))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.file.absolutePath }) { index, item ->
                val thumbnail by produceState<Bitmap?>(
                    initialValue = null,
                    item.file.absolutePath,
                    item.modifiedAtEpochMs,
                    layoutKind,
                ) {
                    value = withContext(Dispatchers.IO) {
                        // Resolve only visible rail items using the proven cover path.
                        // Extraction is serialized across list and player cache instances.
                        thumbnailCache.loadOrCreate(item.file, layoutKind)
                    }
                }
                val active = index == activeIndex
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        )
                        .border(
                            width = if (active) 3.dp else 1.dp,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onJump(index) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbnail != null && thumbnail?.isRecycled == false) {
                            Image(
                                bitmap = thumbnail!!.asImageBitmap(),
                                contentDescription = Utils.t("Segment thumbnail", "分段缩略图"),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(Utils.t("Thumbnail unavailable", "缩略图暂不可用"), color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Checkbox(
                            checked = item.file.absolutePath in selectedPaths,
                            onCheckedChange = { onToggle(item.file.absolutePath) },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                        if (active) {
                            Text(
                                Utils.t("Playing", "播放中"),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(Color(0xCC000000), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatSegmentClock(item.startedAtEpochMs),
                            modifier = Modifier.weight(1f),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        )
                        if (item.protected) {
                            Text(
                                Utils.t("Protected", "已保护"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSegmentClock(epochMs: Long): String =
    if (epochMs > 0L) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs)) else "—"

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
    items: List<PlaybackSegmentItem>,
    durationMs: Long,
    realDurationMs: Long?,
    recordingMode: RecordingMode,
    status: String,
    recordedAtEpochMs: Long?,
) {
    val first = items.first()
    val sidecar = first.sidecar
    val actualBitrate = diagnostics?.bitrateBps?.toLong()
        ?: sidecar?.actualTrack?.bitrateBps
    val effectiveDurationMs = durationMs.takeIf { it > 0L }
        ?: diagnostics?.durationMs
        ?: sidecar?.actualTrack?.durationMs
        ?: 0L
    val recordedAt = recordedAtEpochMs
        ?: sidecar?.startedAtEpochMs
        ?: sidecar?.requestedAtEpochMs
        ?: first.startedAtEpochMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                Utils.t("Recording information", "录像信息"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            PlaybackInfoRow(Utils.t("Status", "状态"), status)
            PlaybackInfoRow(Utils.t("Recorded", "录像时间"),
                if (recordedAt > 0L) Utils.formatEpoch(recordedAt) else Utils.t("Reading…", "读取中"))
            PlaybackInfoRow(Utils.t("Duration", "时长"), formatPlaybackTime(effectiveDurationMs))
            if (recordingMode == RecordingMode.TIME_LAPSE && realDurationMs != null) {
                PlaybackInfoRow(Utils.t("Captured time", "实拍时长"), formatPlaybackTime(realDurationMs))
                if (effectiveDurationMs > 0L) {
                    PlaybackInfoRow(
                        Utils.t("Measured speed", "实测倍率"),
                        String.format(java.util.Locale.US, "%.1f×", realDurationMs.toDouble() / effectiveDurationMs),
                    )
                }
            }
            PlaybackInfoRow(Utils.t("File size", "文件大小"), formatPlaybackBytes(items.sumOf { it.sizeBytes }))
            if (items.size > 1) {
                PlaybackInfoRow(Utils.t("Safety files", "安全分段"), items.size.toString())
            }
            PlaybackInfoRow(
                Utils.t("Resolution", "分辨率"),
                if (diagnostics?.width != null && diagnostics.height != null) {
                    "${diagnostics.width}×${diagnostics.height}"
                } else {
                    Utils.t("Reading…", "读取中")
                },
            )
            PlaybackInfoRow(Utils.t("Actual bitrate", "实际码率"), PlaybackTrackText.bitrate(actualBitrate))
            PlaybackInfoRow(Utils.t("Track frame rate", "轨道帧率"), PlaybackTrackText.frameRate(diagnostics?.frameRateFps))
            sidecar?.profile?.let { requested ->
                PlaybackInfoRow(
                    Utils.t("Requested profile", "请求参数"),
                    "${requested.size.width}×${requested.size.height} · ${PlaybackTrackText.bitrate(requested.bitrateBps.toLong())}",
                )
            }
            sidecar?.requestedCaptureRateFps?.let { captureRate ->
                PlaybackInfoRow(
                    Utils.t("Capture request", "采集请求"),
                    String.format(java.util.Locale.US, "%.3f fps", captureRate),
                )
            }
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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
}

@Composable
private fun FourLanePlaybackSurface(
    files: List<File>,
    segmentDurationHintsMs: Map<String, Long>,
    container: FourLaneTextureContainer,
    compositeWidth: Int,
    compositeHeight: Int,
    displayMode: FourLaneDisplayMode,
    lensMode: FourLaneLensMode,
    correctionConfig: FisheyeCorrectionConfig,
    callbacks: PlaybackSurfaceCallbacks,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(files.map { it.absolutePath }, container) {
        container.blockPlaybackRaster()
        onDispose { container.blockPlaybackRaster() }
    }
    AndroidView(
        factory = { container },
        update = {
            it.setCompositeSize(compositeWidth, compositeHeight)
            it.displayMode = displayMode
            it.lensMode = lensMode
            it.correctionConfig = correctionConfig
        },
        modifier = modifier,
    )

    PlaybackMediaBinding(files, segmentDurationHintsMs, container.textureView, callbacks,
        onVideoFormat = { metadata, width, height -> container.setPlaybackRaster(metadata, width, height) })
}

@Composable
private fun SinglePlaybackSurface(
    files: List<File>,
    segmentDurationHintsMs: Map<String, Long>,
    textureView: TextureView,
    callbacks: PlaybackSurfaceCallbacks,
    modifier: Modifier = Modifier,
) {
    AndroidView(factory = { textureView }, modifier = modifier)
    PlaybackMediaBinding(files, segmentDurationHintsMs, textureView, callbacks)
}

@Composable
private fun PlaybackMediaBinding(
    files: List<File>,
    segmentDurationHintsMs: Map<String, Long>,
    textureView: TextureView,
    callbacks: PlaybackSurfaceCallbacks,
    onVideoFormat: ((RecordingRasterMetadata, Int, Int) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val paths = remember(files) { files.map { it.absolutePath } }
    val timeline = remember(paths) { PlaybackTimeline(paths.size) }
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestVideoFormat by rememberUpdatedState(onVideoFormat)
    val rasterMetadata by produceState<PlaybackRasterBatch?>(null, paths) {
        value = withContext(Dispatchers.IO) { PlaybackRasterBatch(paths, files.map(RecordingRasterReader::read)) }
    }
    SideEffect {
        paths.forEachIndexed { index, path -> timeline.hint(index, segmentDurationHintsMs[path] ?: 0L) }
    }
    // Metadata and duration updates do not recreate a player or restart decoding.
    val metadata = rasterMetadata?.forPaths(paths) ?: return
    DisposableEffect(paths, textureView, metadata) {
        var rasterError: String? = when {
            metadata.any { it is RecordingRasterMetadata.Rejected } -> "INVALID_RASTER_METADATA"
            metadata.distinct().size > 1 -> "MIXED_RECORDING_RASTERS"
            else -> null
        }
        fun playbackError() {
            latestCallbacks.onControlsReady(null)
            latestCallbacks.onError(if (rasterError != null)
                Utils.t("This recording's layout cannot be read safely. Keep the original video and its metadata together.", "无法正确读取这段录像的画面布局，请保留原视频及其配套信息文件。")
                else Utils.t("This recording cannot currently be played on the head unit. Send it to your phone to view it.", "这段录像暂时无法在车机上播放，可以发送到手机查看。"))
        }
        val binding = if (files.isEmpty()) null else runCatching {
            require(metadata.none { it is RecordingRasterMetadata.Rejected }) { "INVALID_RASTER_METADATA" }
            // A playlist may retain one decoder through equal-layout file boundaries. A mixed
            // storage layout requires a separate playback selection, not a speculative size guess.
            require(metadata.distinct().size == 1) { "MIXED_RECORDING_RASTERS" }
            RecordingPlaylistPlayer(
            context, files, textureView, timeline,
            onPrepared = { latestCallbacks.onPrepared(it) },
            onFirstFrame = { latestCallbacks.onFirstFrame() },
            onCompleted = { latestCallbacks.onCompleted() },
            onError = ::playbackError,
            onVideoFormat = { index, width, height ->
                val raster = metadata.getOrNull(index)
                val valid = raster != null && raster.trackError(width, height) == null &&
                    (latestVideoFormat?.invoke(raster, width, height)
                        ?: (raster !is RecordingRasterMetadata.Repacked))
                if (!valid) rasterError = "RASTER_TRACK_OR_PRESENTATION_MISMATCH"
                valid
            },
        ) }.onFailure {
            com.dante.zeekrcapabilitylab.event.EventLogger.markError(
                com.dante.zeekrcapabilitylab.data.Categories.SYSTEM, "RECORDER_PLAYBACK_ERROR", "PLAYER_SETUP_FAILED", it)
            playbackError()
        }.getOrNull()
        latestCallbacks.onControlsReady(binding?.let { active -> PlaybackControls(
            toggle = active::toggle,
            seekTo = active::seekGlobal,
            currentPosition = active::currentPosition,
            duration = timeline::total,
            isPlaying = active::isPlaying,
            segmentIndex = active::segmentIndex,
            seekToSegment = { active.seekSegment(it) },
            segmentDurations = timeline::durations,
            seekToSegmentPosition = active::seekSegment,
        ) })
        onDispose {
            latestCallbacks.onControlsReady(null)
            binding?.close()
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
    RecordingSourceRole.CABIN -> Utils.t("Cabin", "车内")
    RecordingSourceRole.IR -> Utils.t("Infrared", "红外")
    else -> "360°"
}
