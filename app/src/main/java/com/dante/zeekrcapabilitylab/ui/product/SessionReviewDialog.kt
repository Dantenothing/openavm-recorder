package com.dante.zeekrcapabilitylab.ui.product

import android.view.TextureView
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.telemetry.*
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.player.fitSessionPreview
import com.dante.zeekrcapabilitylab.player.sessionVideoAspect
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import io.github.dantenothing.drivemap.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.dante.zeekrcapabilitylab.player.SessionSelection
import com.dante.zeekrcapabilitylab.player.SessionSelectionWindow
import com.dante.zeekrcapabilitylab.transfer.DriveDataSelection

/** Normal recordings only. Parallel camera files share one clock, with at most three decoders. */
@Composable
internal fun SessionReviewDialog(
    files: List<File>,
    initialFile: File? = null,
    onSendToPhone: (suspend (List<File>, com.dante.zeekrcapabilitylab.transfer.DriveDataSelection) -> Boolean)? = null,
    onTimingUnavailable: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val paths = remember(files) { files.distinctBy { it.absolutePath }.take(SessionPlaybackTimeline.MAX_SEGMENTS) }
    var loaded by remember(paths) { mutableStateOf<SessionReviewData?>(null) }
    var failed by remember(paths) { mutableStateOf(false) }
    var timingUnavailable by remember(paths) { mutableStateOf(false) }
    var retry by remember(paths) { mutableIntStateOf(0) }
    DisposableEffect(paths) {
        paths.forEach(PlaybackPinRegistry::acquire)
        onDispose { paths.forEach(PlaybackPinRegistry::release) }
    }
    LaunchedEffect(paths, retry) {
        loaded = null; failed = false; timingUnavailable = false
        val result = try { withContext(Dispatchers.IO) { loadSessionReview(context, paths) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { SessionReviewLoadResult(null) }
        loaded = result.data; timingUnavailable = result.hasUntimedVideo
        failed = loaded == null
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0866F5), secondaryContainer = Color(0xFFE4EEFF),
          onSecondaryContainer = Color(0xFF0757CB), surface = Color.White, surfaceContainer = Color(0xFFF0F4FA))) {
        Surface(Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.96f), shape = MaterialTheme.shapes.large) {
            val data = loaded
            if (data == null) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (failed && timingUnavailable) Utils.t("These older videos have no usable session clock. They can be opened individually.", "这些旧录像没有可用的行程时钟，可以单独打开视频。")
                    else if (failed) Utils.t("No readable timed video. Check the storage and keep each original video with its metadata.", "没有可读取的时序录像，请检查存储并保留原视频和配套信息。")
                    else Utils.t("Reading session videos and recorded observations…", "正在读取行程录像与已记录的数据…"))
                if (failed) TextButton(onClick = { retry++ }) { Text(Utils.t("Retry", "重试")) }
                if (failed && timingUnavailable && onTimingUnavailable != null) OutlinedButton(onClick = onTimingUnavailable) {
                    Text(Utils.t("Open a video without session timing", "不使用行程时间轴，打开单个视频"))
                }
                TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
            } else key(data.timeline.sessionId, retry) {
                SessionReviewContent(data, initialFile, onSendToPhone, onDismiss)
            }
        }
      }
    }
}


@Composable
private fun SessionReviewContent(data: SessionReviewData, initialFile: File?,
    onSendToPhone: (suspend (List<File>, DriveDataSelection) -> Boolean)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val timeline = data.timeline
    val settings = remember { SettingsStore.get(context) }
    val clips = remember(data) { SessionSelection.clips(timeline) }
    val initial = data.entries[initialFile?.absolutePath]?.segment ?: clips.first()
    var position by remember { mutableLongStateOf(timeline.mediaToSession(initial.id, 0) ?: 0L) }
    var seekSerial by remember { mutableIntStateOf(0) }
    var wantsPlay by remember { mutableStateOf(true) }
    var hasVideoFrame by remember { mutableStateOf(false) }
    val videoReady = remember { mutableStateMapOf<String, Boolean>() }
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    var focus by remember { mutableStateOf<String?>(null) }
    var lensMode by remember { mutableStateOf(settings.lensMode) }
    var showMap by remember { mutableStateOf(settings.playbackMapDefaultOpen) }
    var mapExpanded by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var saveRange by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf(clips.firstOrNull { position >= it.mediaStartElapsedMs!! - timeline.originElapsedMs && position < it.mediaEndElapsedMs!! - timeline.originElapsedMs }?.id ?: clips.first().id)) }
    var sendingWindows by remember { mutableStateOf<List<SessionSelectionWindow>?>(null) }
    var sendingAll by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("playback_ui", 0) }
    var showPeaks by remember { mutableStateOf(prefs.getBoolean("show_pedal_peaks", true)) }
    val scale by produceState(RelativePedalScale(), data) {
        value = withContext(Dispatchers.IO) { runCatching { RecordedPedalHistory.observe(context, data.vehicle) }.getOrDefault(RelativePedalScale()) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(view, foreground, wantsPlay) {
        val previous = view.keepScreenOn; view.keepScreenOn = foreground && wantsPlay
        onDispose { view.keepScreenOn = previous }
    }
    val seek: (Long) -> Unit = { value -> position = value.coerceIn(0, timeline.durationMs); seekSerial++ }
    // A session clock survives a missing/failed camera. Each decoder follows the same target.
    LaunchedEffect(wantsPlay, foreground, dragging, seekSerial, details, saveRange, sendingWindows != null, hasVideoFrame) {
        if (wantsPlay && hasVideoFrame && foreground && !dragging && !details && !saveRange && sendingWindows == null) {
            val started = SystemClock.elapsedRealtime(); val origin = position
            while (true) {
                position = (origin + SystemClock.elapsedRealtime() - started).coerceAtMost(timeline.durationMs)
                if (position >= timeline.durationMs) { wantsPlay = false; break }
                // Native video keeps its own frame cadence. The timeline/HUD does not need
                // to recompose this large dashboard for every video frame.
                delay(100)
            }
        }
    }
    val playing = wantsPlay && foreground && !dragging && !details && !saveRange && sendingWindows == null
    val clock = timeline.originElapsedMs + position
    val gps = TelemetryPlayback.sampleAt(data.gps, clock).takeIf { position < timeline.durationMs }
    val vehicle = VehicleTelemetryPlayback.sampleAt(data.vehicle, clock).takeIf { position < timeline.durationMs }
    val frame = recordedDriveHudFrame(clock, vehicle, gps)
    val windows = remember(selected, data) { SessionSelection.windows(timeline, selected) }
    val selectedFiles = remember(selected, clips) { clips.filter { it.id in selected }.map { it.id } }
    val selectionBytes = selectedFiles.sumOf { data.entries[it]?.file?.length() ?: 0L }
    val chinese = AppLanguage.usesChinese()
    val exportChannel = when (focus) { "FRONT_CABIN", "REAR_CABIN" -> focus!!; else -> if ("SURROUND" in timeline.channelIds) "SURROUND" else timeline.channelIds.first() }
    val activeVideos = timeline.channelIds.mapNotNull { timeline.seek(it, position)?.segmentId }
    val thumbnailsMayDecode = foreground && !dragging && activeVideos.isNotEmpty() && activeVideos.all { videoReady[it] == true }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FC)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Utils.t("Session playback", "行程回放"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(18.dp))
            Text(data.entries.values.minOfOrNull { it.sidecar.startedAtEpochMs ?: 0L }?.takeIf { it > 0 }?.let { Utils.formatEpoch(it) }.orEmpty() +
                "  ·  " + sessionTime(timeline.durationMs), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if ("SURROUND" in timeline.channelIds) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 12.dp)) {
                FilterChip(selected = lensMode == FourLaneLensMode.FISHEYE,
                    onClick = { lensMode = FourLaneLensMode.FISHEYE; settings.setLensMode(lensMode) },
                    label = { Text(Utils.t("Original / Wide", "原始 / 广角")) })
                FilterChip(selected = lensMode == FourLaneLensMode.STANDARD,
                    onClick = { lensMode = FourLaneLensMode.STANDARD; settings.setLensMode(lensMode) },
                    label = { Text(Utils.t("Corrected", "标准修正")) })
            }
            FilterChip(selected = focus == null && !mapExpanded, onClick = { focus = null; mapExpanded = false }, label = { Text(Utils.t("Overview", "总览")) })
            TextButton(onClick = { showMap = !showMap; mapExpanded = false }) { Text(if (showMap) Utils.t("Hide map", "收起地图") else Utils.t("Map", "地图")) }
            TextButton(onClick = { details = true }) { Text(Utils.t("Details", "详情")) }
            TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 900.dp
            val railWidth = (maxWidth * 0.14f).coerceIn(160.dp, 280.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wide && focus == null && !mapExpanded) SessionClipRail(data, clips, selected, position,
                    { id -> selected = if (id in selected) selected - id else selected + id },
                    { selected = clips.map { it.id }.toSet() }, { selected = emptySet() }, seek, Modifier.width(railWidth).fillMaxHeight(),
                    allowThumbnailExtraction = thumbnailsMayDecode)
                if (!mapExpanded) Column(Modifier.weight(if (showMap && focus == null) 0.65f else 1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackHudStrip(frame, scale, showPeaks, { showPeaks = !showPeaks; prefs.edit().putBoolean("show_pedal_peaks", showPeaks).apply() },
                        Modifier.fillMaxWidth(), chinese)
                    SessionCameraGrid(data, position, seekSerial, playing, foreground, focus, { focus = it },
                        { id, ready -> videoReady[id] = ready; if (ready) hasVideoFrame = true }, lensMode, Modifier.fillMaxWidth().weight(1f))
                }
                if (showMap && focus == null) Surface(Modifier.weight(if (mapExpanded) 1f else 0.35f).fillMaxHeight(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Utils.t("Recorded route", "录像路线"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { mapExpanded = !mapExpanded }) { Text(if (mapExpanded) Utils.t("Restore", "还原") else Utils.t("Expand", "放大")) }
                        }
                        RecordedRoadMap(data.route, gps?.let { RoadMapPoint(it.latitude, it.longitude, it.elapsedMs - timeline.originElapsedMs) },
                            Modifier.fillMaxWidth().weight(1f), chinese, seek)
                    }
                }
            }
        }
        SessionTimeline(data, if (dragging) dragged.toLong() else position, { dragging = true; dragged = it.toFloat() },
            { seek(dragged.toLong()); dragging = false }, seek)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { seek(position - 10_000) }) { Text(Utils.t("Back 10s", "后退 10 秒")) }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { if (position >= timeline.durationMs) seek(0); wantsPlay = !wantsPlay }, Modifier.height(48.dp)) {
                Text(if (wantsPlay) Utils.t("Pause", "暂停") else Utils.t("Play", "播放"))
            }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { seek(position + 10_000) }) { Text(Utils.t("Forward 10s", "前进 10 秒")) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Utils.t("{0} selected", "已选 {0} 段", selected.size), style = MaterialTheme.typography.titleSmall)
                Text(sessionBytes(selectionBytes) + " · " + Utils.t("Source clips", "原始分段"), style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(enabled = selected.isNotEmpty() && onSendToPhone != null, onClick = { sendingAll = false; sendingWindows = windows }) { Text(Utils.t("Send selected", "发送选中")) }
            OutlinedButton(enabled = onSendToPhone != null, onClick = { sendingAll = true; sendingWindows = listOf(SessionSelectionWindow(0, timeline.durationMs)) }) { Text(Utils.t("Send all", "发送全部")) }
            OutlinedButton(onClick = { saveRange = true }) { Text(Utils.t("Save range", "保存范围")) }
            SessionMoreActions(data, { details = true })
        }
        SessionTransferProgress(data)
    }
    if (details) Dialog(onDismissRequest = { details = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.65f).fillMaxHeight(0.85f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(Utils.t("Session details", "行程详情"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { details = false }) { Text(Utils.t("Close", "关闭")) } }
                SessionReviewDetails(data, exportChannel, position, gps, seek, { wantsPlay = false }, null, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (saveRange) SessionSaveRangeDialog(data, exportChannel, position, { saveRange = false })
    sendingWindows?.let { selection -> SessionSendDialog(data, selection, sendingAll, onSendToPhone!!, { sendingWindows = null }) }
}

@Composable
private fun SessionCameraGrid(data: SessionReviewData, position: Long, seekSerial: Int, playing: Boolean,
    foreground: Boolean, focus: String?, onFocus: (String?) -> Unit, onReady: (String, Boolean) -> Unit, lensMode: FourLaneLensMode, modifier: Modifier) {
    val runs = remember(data) { sessionCameraRuns(data) }
    BoxWithConstraints(modifier.background(Color(0xFF10151F), RoundedCornerShape(12.dp))) {
        val cellWidth = (maxWidth - 8.dp) / 3
        val cellHeight = (maxHeight - 8.dp) / 2
        listOf("SURROUND", "FRONT_CABIN", "REAR_CABIN").forEach { channel -> key(channel) {
            val focused = focus == channel || (channel == "SURROUND" && focus?.startsWith("lane:") == true)
            val selected = data.timeline.seek(channel, position)
            val entry = selected?.let { data.entries[it.segmentId] }
            val bounds = if (focused) Modifier.fillMaxSize() else when (channel) {
                "SURROUND" -> Modifier.width(cellWidth * 2 + 4.dp).fillMaxHeight()
                "FRONT_CABIN" -> Modifier.offset(x = cellWidth * 2 + 8.dp).width(cellWidth).height(cellHeight)
                else -> Modifier.offset(x = cellWidth * 2 + 8.dp, y = cellHeight + 8.dp).width(cellWidth).height(cellHeight)
            }
            Box(bounds.alpha(if (focus == null || focused) 1f else 0f).zIndex(if (focused) 1f else 0f)) {
                if (entry != null && selected != null && foreground) SessionCameraPane(runs.getValue(entry.segment.id), entry, selected.mediaPositionMs, seekSerial,
                    playing, if (channel == "SURROUND") focus?.removePrefix("lane:")?.toIntOrNull() else null,
                    onLane = { lane -> onFocus(if (focus != null) null else "lane:$lane") }, onReady, lensMode, Modifier.fillMaxSize())
                else Text(if (!foreground) Utils.t("Playback paused", "回放已暂停") else Utils.t("Camera unavailable", "此时无该路录像"),
                    Modifier.align(Alignment.Center).padding(12.dp), color = Color(0xFFB9C3D2))
                if (channel != "SURROUND") {
                    Text(sessionChannelLabel(channel), Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xBB111722), RoundedCornerShape(12.dp)).padding(6.dp), color = Color.White)
                    TextButton(onClick = { onFocus(if (focused) null else channel) }, Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp)
                        .background(Color(0xAA111722), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = (if (focused) "Restore " else "Expand ") + channel }) { Text(if (focused) "↙" else "⛶", color = Color.White) }
                }
            }
        } }
    }
}

/** At most one decoder per physical stream; changing its viewport never adds a decoder. */
@Composable
private fun SessionCameraPane(playlist: List<SessionReviewEntry>, entry: SessionReviewEntry, targetMs: Long, seekSerial: Int, playing: Boolean,
    lane: Int?, onLane: (Int) -> Unit, onReady: (String, Boolean) -> Unit, lensMode: FourLaneLensMode, modifier: Modifier) {
    key(playlist.first().segment.id) {
        var attempt by remember { mutableIntStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(error) {
            if (error != null && attempt == 0) { delay(900); attempt++; error = null }
        }
        key(attempt) {
            val context = LocalContext.current
            val settings = remember { SettingsStore.get(context) }
            val container = remember { FourLaneTextureContainer(context) }
            val texture = remember { TextureView(context) }
            var controls by remember { mutableStateOf<PlaybackControls?>(null) }
            var firstFrame by remember { mutableStateOf(false) }
            DisposableEffect(entry.segment.id, firstFrame) {
                onReady(entry.segment.id, firstFrame)
                onDispose { onReady(entry.segment.id, false) }
            }
            var zoom by remember { mutableFloatStateOf(1f) }
            val fourLane = entry.sidecar.layoutKind != RecordingLayoutKind.SINGLE_V1
            val mode = lane?.let(FourLaneDisplayMode::forLane) ?: FourLaneDisplayMode.FOUR_GRID
            val fallback = if (fourLane) 1f else sessionVideoAspect(entry.sidecar.actualTrack?.width ?: entry.sidecar.profile.size.width,
                entry.sidecar.actualTrack?.height ?: entry.sidecar.profile.size.height, 0)
            val aspect by produceState(fallback, playlist.first().file.absolutePath) {
                if (!fourLane) value = withContext(Dispatchers.IO) {
                    val reader = MediaMetadataRetriever()
                    try { reader.setDataSource(playlist.first().file.absolutePath)
                        sessionVideoAspect(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
                    } catch (_: Exception) { fallback } finally { runCatching { reader.release() } }
                }
            }
            val currentTarget by rememberUpdatedState(targetMs)
            val currentIndex by rememberUpdatedState(playlist.indexOfFirst { it.segment.id == entry.segment.id })
            val currentPlaying by rememberUpdatedState(playing)
            val callbacks = PlaybackSurfaceCallbacks(onControlsReady = { value -> controls = value; value?.setPlaying(false)
                value?.seekToSegmentPosition?.invoke(currentIndex, currentTarget); value?.setPlaying(currentPlaying) },
                onPrepared = {}, onFirstFrame = { firstFrame = true; onReady(entry.segment.id, true) }, onCompleted = {},
                onError = { error = it; onReady(entry.segment.id, false) })
            LaunchedEffect(attempt) {
                delay(8_000)
                if (!firstFrame && error == null) error = "FIRST_FRAME_TIMEOUT"
            }
            LaunchedEffect(controls, seekSerial) { controls?.seekToSegmentPosition?.invoke(currentIndex, currentTarget) }
            LaunchedEffect(controls, playing) { controls?.setPlaying(playing) }
            LaunchedEffect(controls) {
                val player = controls ?: return@LaunchedEffect
                while (true) {
                    val target = player.segmentDurations().take(currentIndex).sum() + currentTarget
                    if (firstFrame && abs(player.currentPosition() - target) > if (currentPlaying) 450L else 60L)
                        player.seekToSegmentPosition(currentIndex, currentTarget)
                    delay(1_000)
                }
            }
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                val size = fitSessionPreview(maxWidth.value, maxHeight.value, aspect)
                Box(Modifier.size(size.width.dp, size.height.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                    if (error == null) {
                        val media = remember(playlist) { playlist.map { it.file } }
                        val hints = remember(playlist) { playlist.associate { it.file.absolutePath to it.segment.mediaDurationMs } }
                        val rasters = remember(playlist) { playlist.mapNotNull { it.raster }.takeIf { it.size == playlist.size } }
                        if (fourLane) {
                            FourLanePlaybackSurface(media, hints, container, entry.sidecar.profile.size.width,
                                entry.sidecar.profile.size.height, mode, lensMode, settings.fisheyeCorrection, callbacks, Modifier.fillMaxSize(),
                                knownRasterMetadata = rasters)
                            FourLaneDirectionOverlay(productDirectionLabels(), mode, firstFrame, zoom = zoom,
                                onLaneTapped = { onLane(it); zoom = container.resetViewport() },
                                onTransformGesture = { z, x, y -> zoom = container.applyViewportGesture(z, x, y) })
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                if (lane != null) TextButton(onClick = { onLane(lane) }, Modifier.align(Alignment.BottomEnd).size(48.dp)
                                    .background(Color(0xAA111722), RoundedCornerShape(8.dp))
                                    .semantics { contentDescription = "Restore surround grid" }) { Text("↙", color = Color.White) }
                                else (1..4).forEach { direction ->
                                    val x = if (direction % 2 == 1) maxWidth / 2 - 48.dp else maxWidth - 48.dp
                                    val y = if (direction <= 2) maxHeight / 2 - 48.dp else maxHeight - 48.dp
                                    TextButton(onClick = { onLane(direction) }, Modifier.offset(x, y).size(48.dp)
                                        .background(Color(0x77111722), RoundedCornerShape(8.dp))
                                        .semantics { contentDescription = "Expand surround " + productDirectionLabels()[direction - 1] }) {
                                        Text("⛶", color = Color.White)
                                    }
                                }
                            }
                        } else SinglePlaybackSurface(media, hints, texture, callbacks, Modifier.fillMaxSize(), knownRasterMetadata = rasters)
                        if (!firstFrame) Text(Utils.t("Loading video…", "载入画面…"), Modifier.align(Alignment.Center), color = Color.White)
                    } else Column(Modifier.align(Alignment.Center).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Utils.t("This camera could not play", "此路画面暂时无法播放"), color = Color.White)
                        TextButton(onClick = { attempt++; error = null }) { Text(Utils.t("Retry camera", "重试此路")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionReviewDetails(data: SessionReviewData, channel: String, position: Long, gps: TelemetryFix?,
    seek: (Long) -> Unit, onLeave: () -> Unit, onSendToPhone: (suspend (List<File>, Boolean) -> Boolean)?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableLongStateOf(0) }
    var to by remember { mutableLongStateOf(data.timeline.durationMs) }
    var pendingRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var sendChannels by remember { mutableStateOf(data.timeline.channelIds.toSet()) }
    var includeData by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var reportBusy by remember { mutableStateOf(false) }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val range = pendingRange; pendingRange = null
        if (uri != null && range != null) {
            saving = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching {
                    val selected = data.gps.filter { it.elapsedMs - data.timeline.originElapsedMs in range.first until range.second }
                    require(selected.isNotEmpty())
                    val track = TelemetryTrack(TelemetryHeader(sessionId = data.timeline.sessionId, startedElapsedMs = data.timeline.originElapsedMs), selected)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(TelemetryRouteExport.gpx(track).toByteArray(Charsets.UTF_8)) }
                }.isSuccess }
                saving = false; message = if (ok) Utils.t("Selected route saved", "已保存选定范围路线") else Utils.t("Route could not be saved", "路线保存失败")
            }
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Utils.t("Recorded data", "录像数据"), style = MaterialTheme.typography.titleMedium)
        if (data.route.isEmpty()) Text(Utils.t(
            "No GPS route is available for this session. Enable Location & speed before your next recording; video playback is still available.",
            "这次录像没有可用的 GPS 轨迹。下次录像前可开启“位置与速度”；当前视频仍可正常回放。"), style = MaterialTheme.typography.bodySmall)
        val summary = data.summary
        Text(String.format(Locale.getDefault(), Utils.t("Recorded path %.2f km · GPS %.0f%% · vehicle %.0f%%", "已记录轨迹 %.2f km · GPS %.0f%% · 车辆 %.0f%%"),
            summary.distanceM / 1000, summary.gpsCoveredMs * 100.0 / summary.durationMs.coerceAtLeast(1),
            summary.vehicleCoveredMs * 100.0 / summary.durationMs.coerceAtLeast(1)), style = MaterialTheme.typography.bodySmall)
        Text(Utils.t("Coverage counts retained fresh observations; gaps are excluded from distance.", "覆盖率仅统计已保留的有效观察值；距离不跨越数据断点。"), style = MaterialTheme.typography.bodySmall)
        Text(if (data.entries.values.all { it.segment.anchor?.source == "CAMERA_REALTIME" })
            Utils.t("Camera clock anchors recorded. Vehicle getters may lag ECU measurements.", "已记录相机时钟锚点；车辆读取时间可能晚于 ECU 测量。")
            else Utils.t("Approximate alignment: some camera timestamps are observations or older segment starts.", "近似对齐：部分摄像头采用观察时间或旧分段起始时间。"), style = MaterialTheme.typography.bodySmall)
        if (data.timeline.quantizedBoundaries > 0) Text(Utils.t("Continuous cuts verified: integer-millisecond rounding is reconciled; exported length can differ by up to 1 ms per boundary.",
            "已验证连续切段：已协调整数毫秒舍入，导出时长每个边界最多相差 1 毫秒。"), style = MaterialTheme.typography.bodySmall)
        if (data.skipped > 0) Text(Utils.t("${data.skipped} files unavailable or missing valid timing/layout.", "${data.skipped} 个文件不可用或缺少有效时间/布局。"), style = MaterialTheme.typography.bodySmall)
        data.timeline.channelIds.forEach { id ->
            val segments = data.entries.values.filter { it.segment.channelId == id }
            val recordedSizes = segments.mapNotNull { entry -> entry.sidecar.actualTrack?.let { track ->
                if ((track.width ?: 0) > 0 && (track.height ?: 0) > 0) "${track.width}×${track.height}" else null
            } }.distinct().joinToString(" / ")
            Text(sessionChannelLabel(id) + " · " + Utils.t("${segments.size} saved files", "${segments.size} 个已保存文件") + " · " +
                Utils.t("${segments.sumOf { it.sidecar.frameStats?.count ?: 0 }} capture callbacks", "${segments.sumOf { it.sidecar.frameStats?.count ?: 0 }} 次采集回调") +
                if (recordedSizes.isNotEmpty()) " · $recordedSizes" else "",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = !reportBusy, onClick = {
            reportBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val evidence = com.dante.zeekrcapabilitylab.transfer.SessionRecorderEvidence.collect(context,
                        data.timeline.sessionId, com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO.json.encodeToString(data.entries.values.first().sidecar),
                        RecordingTelemetry.store(context).load(data.timeline.sessionId), RecordingVehicleTelemetry.store(context).load(data.timeline.sessionId),
                        selectedSidecars = data.entries.values.map { it.sidecar })
                    buildJsonObject {
                        put("format", "OPENAVM_CAR_DRIVE_REPORT"); put("schemaVersion", 1)
                        put("recorder", Json.encodeToJsonElement(evidence))
                        put("playback", buildJsonObject {
                            put("loadedFiles", data.entries.size); put("skippedFiles", data.skipped)
                            put("channelCount", data.timeline.channelIds.size); put("durationMs", data.timeline.durationMs)
                            put("integerMsQuantizedBoundaries", data.timeline.quantizedBoundaries)
                            put("hardwareSynchronization", "NOT_VERIFIED_BY_THIS_REPORT")
                            put("gpsPointCount", data.gps.size); put("vehicleSampleCount", data.vehicle.size)
                        })
                        put("maps", buildJsonObject { RoadMapDiagnostics.snapshot().forEach { (key, value) -> put(key, value) } })
                        put("recovery", com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.collectRecoveryEvidence(context))
                    }.toString()
                }.getOrNull() }
                reportBusy = false
                if (result != null) {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("OpenAVM car drive report", result))
                    message = Utils.t("Report copied · no coordinates or video", "报告已复制 · 不含坐标或视频")
                } else message = Utils.t("Could not prepare report", "无法生成报告")
            }
        }) { Text(Utils.t("Copy session test report", "复制行程测试报告")) }
        SessionClipExportControls(data.timeline, data.entries.mapValues { it.value.file }, channel, position,
            onRangeChanged = { start, end -> from = start; to = end })
        OutlinedButton(enabled = !saving && pendingRange == null && data.gps.any { it.elapsedMs - data.timeline.originElapsedMs in from until to }, onClick = {
            onLeave(); pendingRange = from to to; exportGpx.launch("OpenAVM-selected-route.gpx")
        }) { Text(Utils.t("Save selected range as GPX", "将选定范围保存为 GPX")) }
        Text(Utils.t("GPX contains coordinates. Ordinary video export excludes location and HUD.", "GPX 包含坐标。普通视频导出不含位置或 HUD。"), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(Utils.t("Events · tap to seek", "事件 · 点击跳转"), style = MaterialTheme.typography.titleSmall)
        if (data.events.isEmpty()) Text(Utils.t("No retained transitions or markers", "没有保留的状态变化或标记"))
        else LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
            items(data.events) { event -> TextButton(onClick = { seek(event.sessionMs) }) {
                Text(sessionTime(event.sessionMs) + "  " + sessionEventLabel(event))
            } }
        }
        if (onSendToPhone != null) {
            HorizontalDivider()
            Text(Utils.t("Send this session", "发送此行程"), style = MaterialTheme.typography.titleSmall)
            data.timeline.channelIds.forEach { id -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in sendChannels, onCheckedChange = { selected -> sendChannels = if (selected) sendChannels + id else sendChannels - id }, enabled = !sending)
                Text(sessionChannelLabel(id))
            } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeData, onCheckedChange = { includeData = it }, enabled = !sending)
                Text(Utils.t("Include route & vehicle observations", "附带路线与车辆观察值"), style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = sendChannels.isNotEmpty() && !sending, onClick = {
                val selected = data.entries.values.filter { it.segment.channelId in sendChannels }.map { it.file }
                sending = true
                scope.launch {
                    val accepted = try { onSendToPhone(selected, includeData) } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { false }
                    sending = false; message = if (accepted) Utils.t("Queued for paired transfer", "已加入配对传输队列") else Utils.t("Could not queue transfer", "无法加入传输队列")
                }
            }) { Text(if (sending) Utils.t("Queuing…", "正在加入…") else Utils.t("Send selected cameras", "发送所选摄像头")) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun sessionChannelLabel(channel: String) = when (channel) {
    "FRONT_CABIN" -> Utils.t("Cabin camera", "舱内摄像头")
    "REAR_CABIN" -> Utils.t("Infrared camera", "红外摄像头")
    else -> Utils.t("Surround", "环视")
}

internal fun sessionTime(ms: Long): String = "%02d:%02d".format(Locale.ROOT, ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
internal fun sessionEventLabel(event: DriveReviewEvent): String = when (event.kind) {
    DriveEventKind.TURN -> Utils.t("Indicators: ", "转向灯：") + when (event.value) {
        "left" -> Utils.t("left", "左"); "right" -> Utils.t("right", "右"); "hazard" -> Utils.t("hazard", "双闪"); else -> Utils.t("off", "关闭") }
    DriveEventKind.BRAKE -> if (event.value == "pressed") Utils.t("Brake pressed", "踩下制动") else Utils.t("Brake released", "松开制动")
    DriveEventKind.MANUAL -> Utils.t("Saved clip", "手动保存")
    DriveEventKind.EMERGENCY -> Utils.t("Emergency marker", "紧急标记")
    DriveEventKind.GPS_GAP -> Utils.t("GPS interruption", "GPS 中断")
    DriveEventKind.VEHICLE_GAP -> Utils.t("Vehicle data interruption", "车辆数据中断")
    DriveEventKind.RECORDING_INTERRUPTED -> Utils.t("Recording gap", "录像缺口")
}
