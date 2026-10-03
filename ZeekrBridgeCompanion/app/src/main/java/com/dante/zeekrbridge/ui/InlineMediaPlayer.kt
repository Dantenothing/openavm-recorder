package com.dante.zeekrbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.dante.zeekrbridge.core.IndexedLayoutKind
import com.dante.zeekrbridge.core.IndexedRecordingMode
import com.dante.zeekrbridge.core.isFourLane
import com.dante.zeekrbridge.core.ContinuousRasterSupport
import com.dante.zeekrbridge.core.DriveDataFiles
import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import com.dante.zeekrbridge.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** One player stays mounted while the detail page's actions and segment selection change. */
@Composable
internal fun InlineMediaPlayer(
    entries: List<PlaybackEntry>,
    initialIndex: Int = 0,
    seekRequest: PlaybackSeekRequest? = null,
    obstructed: Boolean = false,
    onActiveMediaChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    additionalContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playable = remember(entries) { entries.filter { it.readable } }
    val first = playable.firstOrNull()
    val metadataError = playable.firstNotNullOfOrNull {
        ContinuousRasterSupport.error(it.raster, it.sourceRole, it.layoutKind, it.lanes, it.originalWidth, it.originalHeight)
    }
    if (first == null || metadataError != null || playable.any {
        it.sourceRole != first.sourceRole || it.layoutKind != first.layoutKind || it.raster != first.raster ||
            (first.raster is RecordingRasterMetadata.Repacked && it.lanes != first.lanes)
    }) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Text(
                if (first == null) t("The recording files are missing.", "录像文件已缺失。")
                else if (metadataError != null) t("Recording layout is unavailable: {0}", "录像布局无法识别：{0}", metadataError)
                else t("This session contains mixed camera layouts and cannot be played continuously.", "这个录像片段包含不同摄像头布局，无法安全连续播放。"),
                Modifier.fillMaxWidth().padding(20.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        return
    }
    // Validate every repacked segment before creating the decoder, off the UI thread.
    // The first segment's geometry cannot vouch for rotation or size in later files.
    val checkedTracks by produceState<Pair<List<PlaybackEntry>, String?>?>(null, playable) {
        value = withContext(Dispatchers.IO) {
            playable to playable.filter { it.raster is RecordingRasterMetadata.Repacked }.firstNotNullOfOrNull { item ->
                val geometry = readDisplayGeometry(context, item)
                ContinuousRasterSupport.trackError(item.raster, item.layoutKind,
                    geometry?.width ?: 0, geometry?.height ?: 0, geometry?.metadataRotationDegrees ?: 0)
            }
        }
    }
    val tracks = checkedTracks?.takeIf { it.first == playable }
    if (tracks == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    if (tracks.second != null) {
        Text(t("Recording layout is unavailable: {0}", "录像布局无法识别：{0}", tracks.second),
            color = MaterialTheme.colorScheme.error)
        return
    }
    val ids = remember(playable) { playable.map { it.id } }
    var resumeId by rememberSaveable(ids) { mutableStateOf(entries.getOrNull(initialIndex)?.id ?: first.id) }
    var resumePositionMs by rememberSaveable(ids) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(ids) { mutableStateOf(true) }
    val player = remember(playable) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    var intent by remember(player) {
        mutableStateOf(InlinePlaybackIntent(wantsToPlay = resumePlaying,
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var currentIndex by remember(player) {
        mutableIntStateOf(playableIndexForId(ids, resumeId) ?: 0)
    }
    var currentGlobalMs by remember(player) { mutableLongStateOf(0L) }
    var totalDurationMs by remember(player) { mutableLongStateOf(0L) }
    var driveDurationsMs by remember(player) { mutableStateOf(playable.map { 0L }) }
    var sliderMs by remember(player) { mutableFloatStateOf(0f) }
    var dragging by remember(player) { mutableStateOf(false) }
    var speed by rememberSaveable(ids) { mutableFloatStateOf(1f) }
    var muted by rememberSaveable(ids) { mutableStateOf(false) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    var renderProblem by remember(player) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(ids) { mutableIntStateOf(FourLaneGlView.MODE_GRID) }
    var lensMode by rememberSaveable(ids) { mutableStateOf(FourLaneLensMode.FISHEYE) }
    var firstFrame by remember(player) { mutableStateOf(false) }
    var surfaceAttached by remember(player) { mutableStateOf(false) }
    var decodedSize by remember(player) { mutableStateOf<PlaybackDisplaySize?>(null) }
    val activeEntry = playable[currentIndex.coerceIn(playable.indices)]
    val loadedDriveData by produceState<Pair<PlaybackEntry, LoadedDriveAttachment>?>(null, activeEntry) {
        value = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val loaded = loadDriveAttachment(activeEntry)
            currentCoroutineContext().ensureActive()
            activeEntry to loaded
        }
    }
    val loadedRoute by produceState<LoadedDriveRoute?>(null, entries, playable) {
        value = withContext(Dispatchers.IO) {
            val sourcePositions = entries.mapIndexed { index, entry -> entry.id to index }.toMap()
            val budget = (MAX_DRIVE_ROUTE_POINTS / minOf(playable.size, MAX_DRIVE_ROUTE_SEGMENTS).coerceAtLeast(1)).coerceAtLeast(1)
            val durations = linkedMapOf<String, Long>()
            val summaries = playable.mapIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                durations[entry.id] = readPlaybackDuration(context, entry)
                currentCoroutineContext().ensureActive()
                val sourceIndex = sourcePositions[entry.id] ?: index
                if (index >= MAX_DRIVE_ROUTE_SEGMENTS) RecordedDriveSegment(entry.id, sourceIndex, DriveAttachmentStatus.ROUTE_LIMIT)
                else {
                    val loaded = loadDriveAttachment(entry)
                    currentCoroutineContext().ensureActive()
                    loaded.bundle?.let { summarizeRecordedDrive(entry.id, sourceIndex, it, budget) }
                        ?: RecordedDriveSegment(entry.id, sourceIndex, loaded.status)
                }
            }
            currentCoroutineContext().ensureActive()
            LoadedDriveRoute(entries, playable, summaries, durations)
        }
    }
    // Old results never survive a segment/playlist change while the new attachment is being verified.
    val driveData = loadedDriveData?.takeIf { it.first == activeEntry }?.second
    val checkedRoute = loadedRoute?.takeIf { it.entries == entries && it.playable == playable }
    val driveSegments = checkedRoute?.segments
    val measuredDurations = checkedRoute?.durations.orEmpty()
    val currentMeasuredDurations by rememberUpdatedState(measuredDurations)
    val driveRoute = remember(driveSegments, driveDurationsMs) { driveSegments?.let { recordedPlaylistRoute(it, driveDurationsMs) } }
    var metadataSize by remember(activeEntry.id) { mutableStateOf<PlaybackDisplaySize?>(null) }
    val currentOnActiveMediaChanged by rememberUpdatedState(onActiveMediaChanged)
    SideEffect { resumePlaying = intent.wantsToPlay }

    fun updatePosition(index: Int, positionMs: Long) {
        currentIndex = index.coerceIn(playable.indices)
        resumeId = playable[currentIndex].id
        resumePositionMs = positionMs.coerceAtLeast(0L)
        val durations = resolvedDurations(playable, player, currentIndex, currentMeasuredDurations)
        driveDurationsMs = durations
        val timeline = PlaybackTimeline(durations)
        currentGlobalMs = timeline.globalPosition(currentIndex, resumePositionMs)
        totalDurationMs = timeline.totalDurationMs
    }
    LaunchedEffect(player, measuredDurations) { updatePosition(player.currentMediaItemIndex, player.currentPosition) }

    BackHandler(enabled = mode != FourLaneGlView.MODE_GRID && !obstructed) { mode = FourLaneGlView.MODE_GRID }
    LaunchedEffect(activeEntry.id) { currentOnActiveMediaChanged(activeEntry.id) }
    LaunchedEffect(activeEntry.id, player) {
        if (!activeEntry.layoutKind.isFourLane) {
            metadataSize = withContext(Dispatchers.IO) {
                readDisplayGeometry(context, activeEntry)?.let { PlaybackDisplaySize(activeEntry.id, it) }
            }
        }
    }

    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            intent = intent.copy(foreground = foreground)
            if (!foreground) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, intent, obstructed, renderProblem) {
        player.playWhenReady = renderProblem == null && intent.copy(obstructed = obstructed).shouldPlay
    }
    val view = LocalView.current
    DisposableEffect(view, isPlaying, obstructed, intent.foreground) {
        val previous = view.keepScreenOn
        view.keepScreenOn = isPlaying && intent.foreground && !obstructed
        onDispose { view.keepScreenOn = previous }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) intent = intent.copy(wantsToPlay = false)
                if (state == Player.STATE_READY && renderProblem == null) error = null
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    intent = intent.copy(wantsToPlay = false)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePosition(player.currentMediaItemIndex, player.currentPosition)
                decodedSize = null
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                updatePosition(newPosition.mediaItemIndex, newPosition.positionMs)
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updatePosition(player.currentMediaItemIndex, player.currentPosition)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val id = player.currentMediaItem?.mediaId ?: return
                // Media3 already applies rotation to decoder dimensions on all supported APIs.
                decodedSize = PlaybackDisplaySize(id, VideoDisplayGeometry(
                    videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio,
                ))
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                onVideoSizeChanged(player.videoSize)
            }
            override fun onPlayerError(playbackError: PlaybackException) {
                error = t("Playback failed: {0}", "播放失败：{0}", playbackError.errorCodeName)
                intent = intent.copy(wantsToPlay = false)
            }
        }
        player.addListener(listener)
        player.setMediaItems(playable.map { MediaItem.Builder().setMediaId(it.id).setUri(it.uri).build() }, currentIndex, resumePositionMs)
        player.setPlaybackSpeed(speed)
        player.volume = if (muted) 0f else 1f
        if (canPreparePlayback(first.layoutKind, customSurfaceAttached = false)) player.prepare()
        onDispose {
            player.removeListener(listener)
            player.clearVideoSurface()
            player.release()
        }
    }
    LaunchedEffect(player, seekRequest) {
        seekRequest?.let { request ->
            playableIndexForId(ids, request.mediaId)?.let { index ->
                player.seekTo(index, 0L)
                intent = intent.copy(wantsToPlay = true)
            }
        }
    }
    LaunchedEffect(player, dragging) {
        while (true) {
            val index = player.currentMediaItemIndex.coerceIn(playable.indices)
            updatePosition(index, player.currentPosition)
            if (!dragging) sliderMs = currentGlobalMs.toFloat()
            delay(250L)
        }
    }
    fun seekGlobal(targetMs: Long) {
        val index = player.currentMediaItemIndex.coerceIn(playable.indices)
        val position = PlaybackTimeline(resolvedDurations(playable, player, index, currentMeasuredDurations)).resolve(targetMs)
        player.seekTo(position.mediaItemIndex, position.positionMs)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth >= 600.dp && maxWidth > maxHeight
        val viewportHeight = maxHeight.value
        val videoContent: @Composable () -> Unit = {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                val ratio = if (activeEntry.layoutKind.isFourLane) 1f else
                    decodedSize?.aspectRatioFor(activeEntry.id) ?: metadataSize?.aspectRatioFor(activeEntry.id)
                val maxHeight = if (landscape) (viewportHeight - 170f).coerceAtLeast(80f)
                    else (viewportHeight * 0.32f).coerceIn(100f, 260f)
                // Only physical video/time controls are LTR; surrounding text follows the selected locale.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
                        val frame = fitVideoFrame(maxWidth.value, maxHeight, ratio)
                        Box(Modifier.fillMaxWidth().height(frame.height.dp), contentAlignment = Alignment.Center) {
                            if (activeEntry.layoutKind.isFourLane) {
                                Box(Modifier.size(frame.width.dp, frame.height.dp)) {
                                    key(player) { FourLaneVideoSurface(
                                        player = player, entry = first, mode = mode, lensMode = lensMode,
                                        onModeChanged = { mode = it }, onFirstFrame = { firstFrame = true },
                                        onSurfaceReady = { surfaceAttached = true },
                                        onRenderError = {
                                            renderProblem = it
                                            error = t("360° rendering failed: {0}", "360° 渲染失败：{0}", it)
                                            intent = intent.copy(wantsToPlay = false)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    ) }
                                    LaneLabels(entry = first, mode = mode)
                                }
                            } else {
                                PlainVideoSurface(player, Modifier.size(frame.width.dp, frame.height.dp))
                            }
                            if (!firstFrame && error == null) {
                                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
                            }
                            RecordedVideoHud(recordedPlaylistFrame(driveData?.bundle,
                                resumePositionMs.takeIf { resumeId == activeEntry.id }, driveDurationsMs.getOrNull(currentIndex) ?: 0L),
                                Modifier.align(Alignment.BottomStart).padding(6.dp), compact = frame.height < 300f)
                        }
                    }
                    PlaybackControls(
                        positionMs = if (dragging) sliderMs.toLong() else currentGlobalMs,
                        durationMs = totalDurationMs,
                        playing = renderProblem == null && intent.copy(obstructed = obstructed).shouldPlay,
                        speed = speed, muted = muted,
                        onScrub = { dragging = true; sliderMs = it },
                        onScrubFinished = { seekGlobal(sliderMs.toLong()); dragging = false },
                        onBackTen = { seekGlobal((currentGlobalMs - 10_000L).coerceAtLeast(0L)) },
                        onForwardTen = { seekGlobal((currentGlobalMs + 10_000L).coerceAtMost(totalDurationMs)) },
                        onTogglePlay = {
                            if (!intent.wantsToPlay && player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                            intent = intent.copy(wantsToPlay = !intent.wantsToPlay)
                        },
                        onSpeed = { speed = it; player.setPlaybackSpeed(it) },
                        onMute = { muted = !muted; player.volume = if (muted) 0f else 1f },
                    )
                }
            }
        }
        }
        val detailsContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RecordedDrivePanel(ids, driveRoute, driveData?.bundle,
            driveData?.status ?: DriveAttachmentStatus.LOADING, resumePositionMs.takeIf { resumeId == activeEntry.id },
            currentIndex, driveDurationsMs.getOrNull(currentIndex) ?: 0L, onSeek = ::seekGlobal)
        if (first.layoutKind.isFourLane) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(lensMode == FourLaneLensMode.FISHEYE, { lensMode = FourLaneLensMode.FISHEYE }, label = { Text(t("Fisheye", "鱼眼")) })
                FilterChip(lensMode == FourLaneLensMode.STANDARD, { lensMode = FourLaneLensMode.STANDARD }, label = { Text(t("Standard view", "标准视角")) })
                if (mode != FourLaneGlView.MODE_GRID) {
                    TextButton(onClick = { mode = FourLaneGlView.MODE_GRID }) { Text(t("All views", "全部视角")) }
                }
            }
            Text(
                if (mode == FourLaneGlView.MODE_GRID) t("Tap a view to enlarge", "点击任一画面放大")
                else t("Tap to return · Pinch to zoom · Drag to move · Double-tap to reset", "单击返回四宫格 · 双指缩放 · 拖动查看 · 双击复位"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.size > playable.size) {
            Text(t("{0} missing segments were skipped.", "已跳过 {0} 个缺失分段。", entries.size - playable.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = {
                        if (canPreparePlayback(first.layoutKind, surfaceAttached)) {
                            error = null
                            player.prepare()
                            intent = intent.copy(wantsToPlay = true)
                        }
                    }, enabled = renderProblem == null) { Text(t("Retry", "重试")) }
                }
            }
        }
        additionalContent()
        }
        }
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.56f).fillMaxHeight()) { videoContent() }
            Column(Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { detailsContent() }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            videoContent()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { detailsContent() }
        }
    }
}

private data class LoadedDriveAttachment(val status: DriveAttachmentStatus, val bundle: DriveDataBundle? = null)
private data class LoadedDriveRoute(val entries: List<PlaybackEntry>, val playable: List<PlaybackEntry>,
    val segments: List<RecordedDriveSegment>, val durations: Map<String, Long>)

/** Called only from IO producers, one bounded attachment at a time. */
private fun loadDriveAttachment(entry: PlaybackEntry): LoadedDriveAttachment {
    if (entry.recordingMode == IndexedRecordingMode.TIME_LAPSE) return LoadedDriveAttachment(DriveAttachmentStatus.TIME_LAPSE)
    if (!entry.uri.scheme.equals("file", ignoreCase = true)) return LoadedDriveAttachment(DriveAttachmentStatus.NOT_LOCAL)
    val bundle = entry.uri.path?.let { DriveDataFiles.load(File(it)) }
    return LoadedDriveAttachment(if (bundle == null) DriveAttachmentStatus.MISSING else DriveAttachmentStatus.AVAILABLE, bundle)
}

@Composable
private fun BoxScope.LaneLabels(entry: PlaybackEntry, mode: Int) {
    val labels = entry.laneLabels.takeIf { it.size == 4 } ?: when (entry.layoutKind) {
        IndexedLayoutKind.FOUR_LANE_GRID_2X2 -> listOf(t("Top left", "左上"), t("Top right", "右上"), t("Bottom left", "左下"), t("Bottom right", "右下"))
        else -> listOf(t("Front", "前"), t("Rear", "后"), t("Left", "左"), t("Right", "右"))
    }
    val alignments = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)
    val visible = if (mode == FourLaneGlView.MODE_GRID) labels.mapIndexed { index, label -> index to label }
        else listOf(0 to (labels.getOrNull(entry.laneOrder.indexOf(mode)) ?: t("View", "视角")))
    visible.forEach { (index, label) ->
        Surface(modifier = Modifier.align(alignments[index]).padding(8.dp), color = Color.Black.copy(alpha = 0.70f), shape = MaterialTheme.shapes.small) {
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
