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

/** Normal recordings only. Parallel camera files share one clock, with at most three decoders. */
@Composable
internal fun SessionReviewDialog(
    files: List<File>,
    initialFile: File? = null,
    onSendToPhone: (suspend (List<File>, Boolean) -> Boolean)? = null,
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

@Composable
private fun SessionReviewContent(data: SessionReviewData, initialFile: File?,
    onSendToPhone: (suspend (List<File>, Boolean) -> Boolean)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val timeline = data.timeline
    val initial = data.entries[initialFile?.absolutePath] ?: timeline.segments.firstOrNull()?.let { data.entries[it.id] }
    var channel by remember { mutableStateOf(initial?.segment?.channelId ?: timeline.channelIds.first()) }
    var position by remember { mutableLongStateOf(initial?.segment?.let { timeline.mediaToSession(it.id, 0) } ?: 0L) }
    var seekSerial by remember { mutableIntStateOf(0) }
    var wantsPlay by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }
    // This is an opening preference; the player's temporary toggle does not change it.
    var showDetails by remember { mutableStateOf(settings.playbackMapDefaultOpen) }
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val hudPrefs = remember { context.getSharedPreferences("playback_ui", android.content.Context.MODE_PRIVATE) }
    var hudVisible by remember { mutableStateOf(hudPrefs.getBoolean("drive_hud", true)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(view, wantsPlay, foreground) {
        val old = view.keepScreenOn; view.keepScreenOn = wantsPlay && foreground
        onDispose { view.keepScreenOn = old }
    }
    val seek: (Long) -> Unit = { next -> position = next.coerceIn(0, timeline.durationMs); seekSerial++ }
    val active = timeline.seek(channel, position)
    val entry = active?.let { data.entries[it.segmentId] }
    val clock = timeline.originElapsedMs + position
    val gps = TelemetryPlayback.sampleAt(data.gps, clock).takeIf { position < timeline.durationMs }
    val vehicle = VehicleTelemetryPlayback.sampleAt(data.vehicle, clock).takeIf { position < timeline.durationMs }
    val frame = recordedDriveHudFrame(clock, vehicle, gps)
    LaunchedEffect(active?.segmentId, position >= timeline.durationMs) { if (active == null) wantsPlay = false }
    val chinese = AppLanguage.usesChinese()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Utils.t("Session playback", "行程回放"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { overview = !overview }) { Text(if (overview) Utils.t("One camera", "单摄像头") else Utils.t("Overview", "多画面")) }
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) Utils.t("Hide details", "收起详情") else Utils.t("Map & events", "地图与事件")) }
            TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SessionPlaybackTimeline.CHANNELS.forEach { id ->
                FilterChip(selected = channel == id, onClick = { channel = id; seekSerial++ },
                    enabled = id in timeline.channelIds, label = { Text(sessionChannelLabel(id)) })
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 800.dp
            val detailsHeight = maxHeight * 0.38f
            val compactHud = overview || maxHeight < 300.dp || maxWidth < 600.dp
            val video: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                        if (entry != null && active != null && foreground) {
                            SessionCameraPane(entry, active.mediaPositionMs, seekSerial,
                                wantsPlay && !dragging, true,
                                onProgress = { local -> if (!dragging) timeline.mediaToSession(entry.segment.id, local)?.let { position = it } },
                                onCompleted = {
                                    position = timeline.mediaToSession(entry.segment.id, entry.segment.mediaDurationMs) ?: position
                                    if (timeline.seek(channel, position) == null) wantsPlay = false
                                }, onError = { wantsPlay = false }, modifier = Modifier.fillMaxSize(), overlay = {
                                    DriveHud(frame, hudVisible, { hudVisible = it; hudPrefs.edit().putBoolean("drive_hud", it).apply() },
                                        Modifier.align(Alignment.BottomStart).padding(6.dp).widthIn(max = 330.dp), chinese, compact = compactHud)
                                })
                        } else Text(if (!foreground) Utils.t("Playback paused", "回放已暂停")
                            else if (position >= timeline.durationMs) Utils.t("End of session", "行程结束")
                            else Utils.t("No video from this camera at this time", "此时没有该摄像头的录像"),
                            Modifier.align(Alignment.Center).padding(12.dp), color = Color.White)
                        if (entry == null) DriveHud(frame, hudVisible, { hudVisible = it; hudPrefs.edit().putBoolean("drive_hud", it).apply() },
                            Modifier.align(Alignment.BottomStart).padding(6.dp).widthIn(max = 330.dp), chinese, compact = compactHud)
                    }
                    if (overview) Row(Modifier.fillMaxWidth().height(if (wide) 116.dp else 90.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        timeline.channelIds.filter { it != channel }.take(2).forEach { other ->
                            val selection = timeline.seek(other, position)
                            val otherEntry = selection?.let { data.entries[it.segmentId] }
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                                if (otherEntry != null && selection != null && foreground) SessionCameraPane(otherEntry,
                                    selection.mediaPositionMs, seekSerial, wantsPlay && !dragging, false, {}, {}, {}, Modifier.fillMaxSize())
                                else Text(Utils.t("No video", "无录像"), Modifier.align(Alignment.Center), color = Color.White)
                                Text(sessionChannelLabel(other), Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                video(Modifier.weight(1f).fillMaxHeight())
                if (showDetails) SessionReviewDetails(data, channel, position, gps, seek,
                    onLeave = { wantsPlay = false }, onSendToPhone, Modifier.width(350.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                video(Modifier.fillMaxWidth().weight(1f))
                if (showDetails) SessionReviewDetails(data, channel, position, gps, seek,
                    onLeave = { wantsPlay = false }, onSendToPhone, Modifier.fillMaxWidth().height(detailsHeight))
            }
        }
        Slider(value = if (dragging) dragged else position.toFloat(), onValueChange = { dragging = true; dragged = it },
            onValueChangeFinished = { seek(dragged.toLong()); dragging = false }, valueRange = 0f..timeline.durationMs.toFloat())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (position >= timeline.durationMs) seek(0); wantsPlay = !wantsPlay },
                enabled = active != null || position >= timeline.durationMs) { Text(if (wantsPlay) Utils.t("Pause", "暂停") else Utils.t("Play", "播放")) }
            TextButton(onClick = { seek(position - 10_000) }) { Text("−10s") }
            TextButton(onClick = { seek(position + 10_000) }) { Text("+10s") }
            Text(sessionTime(position) + " / " + sessionTime(timeline.durationMs), style = MaterialTheme.typography.bodyMedium)
            if (active == null && position < timeline.durationMs) timeline.segments.firstOrNull {
                it.channelId == channel && it.mediaStartElapsedMs!! > clock
            }?.let { next -> TextButton(onClick = { seek(next.mediaStartElapsedMs!! - timeline.originElapsedMs) }) {
                Text(Utils.t("Next available video", "下一段可用录像"))
            } }
        }
    }
}

/** The established raster-aware renderer is retained; one ordinary decoder per visible channel. */
@Composable
private fun SessionCameraPane(entry: SessionReviewEntry, targetMs: Long, seekSerial: Int, playing: Boolean,
    primary: Boolean, onProgress: (Long) -> Unit, onCompleted: () -> Unit, onError: () -> Unit, modifier: Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}) {
    key(entry.segment.id) {
        val context = LocalContext.current
        val settings = remember { SettingsStore.get(context) }
        val container = remember { FourLaneTextureContainer(context) }
        val texture = remember { TextureView(context) }
        var controls by remember { mutableStateOf<PlaybackControls?>(null) }
        var firstFrame by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var mode by remember { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
        var lensMode by remember { mutableStateOf(settings.lensMode) }
        var zoom by remember { mutableFloatStateOf(1f) }
        val fourLane = entry.sidecar.layoutKind != RecordingLayoutKind.SINGLE_V1
        val fallbackAspect = if (fourLane) 1f else sessionVideoAspect(
            entry.sidecar.actualTrack?.width?.takeIf { it > 0 } ?: entry.sidecar.profile.size.width,
            entry.sidecar.actualTrack?.height?.takeIf { it > 0 } ?: entry.sidecar.profile.size.height, 0)
        val aspect by produceState(fallbackAspect, entry.file.absolutePath, fourLane) {
            if (!fourLane) value = withContext(Dispatchers.IO) {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(entry.file.absolutePath)
                    val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (width > 0 && height > 0) sessionVideoAspect(width, height, rotation) else fallbackAspect
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { fallbackAspect }
                finally { runCatching { reader.release() } }
            }
        }
        val currentTarget by rememberUpdatedState(targetMs)
        val currentPlaying by rememberUpdatedState(playing)
        val currentProgress by rememberUpdatedState(onProgress)
        val currentCompleted by rememberUpdatedState(onCompleted)
        val currentError by rememberUpdatedState(onError)
        val callbacks = PlaybackSurfaceCallbacks(onControlsReady = { value ->
            controls = value; value?.setPlaying(false); value?.seekToSegmentPosition?.invoke(0, currentTarget)
            value?.setPlaying(currentPlaying)
        }, onPrepared = {}, onFirstFrame = { firstFrame = true }, onCompleted = { if (primary) currentCompleted() },
            onError = { message -> error = message; currentError() })
        LaunchedEffect(controls, seekSerial) { controls?.seekToSegmentPosition?.invoke(0, currentTarget) }
        LaunchedEffect(controls, playing) { controls?.setPlaying(playing && error == null) }
        LaunchedEffect(controls) {
            val active = controls ?: return@LaunchedEffect
            while (true) {
                val actual = active.currentPosition()
                if (primary) currentProgress(actual)
                else if (abs(actual - currentTarget) > if (currentPlaying) 350L else 60L) active.seekToSegmentPosition(0, currentTarget)
                delay(100)
            }
        }
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val size = fitSessionPreview(maxWidth.value, maxHeight.value, aspect)
            Box(Modifier.size(size.width.dp, size.height.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                val hints = remember(entry) { mapOf(entry.file.absolutePath to entry.segment.mediaDurationMs) }
                if (fourLane) {
                    FourLanePlaybackSurface(listOf(entry.file), hints, container,
                        entry.sidecar.profile.size.width, entry.sidecar.profile.size.height, mode, lensMode,
                        settings.fisheyeCorrection, callbacks, Modifier.fillMaxSize())
                    if (primary) FourLaneDirectionOverlay(productDirectionLabels(), mode, firstFrame,
                        zoom = zoom, onLaneTapped = { mode = mode.toggleLane(it); zoom = container.resetViewport() },
                        onTransformGesture = { z, x, y -> zoom = container.applyViewportGesture(z, x, y) })
                    if (primary) FourLaneLensToggle(lensMode, { selected -> lensMode = selected; settings.setLensMode(selected) },
                        Modifier.align(Alignment.TopEnd).padding(6.dp))
                } else SinglePlaybackSurface(listOf(entry.file), hints, texture, callbacks, Modifier.fillMaxSize())
                if (!firstFrame || error != null) Text(error ?: Utils.t("Loading video…", "载入画面…"),
                    Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f)).padding(8.dp), color = Color.White,
                    style = MaterialTheme.typography.bodySmall)
                overlay()
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
        Text(Utils.t("Map & recorded data", "地图与录像数据"), style = MaterialTheme.typography.titleMedium)
        RecordedRoadMap(data.route, gps?.let { RoadMapPoint(it.latitude, it.longitude, it.elapsedMs - data.timeline.originElapsedMs) },
            Modifier.fillMaxWidth().height(270.dp), AppLanguage.usesChinese(), seek)
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
            Text(sessionChannelLabel(id) + " · " + Utils.t("${segments.size} saved files", "${segments.size} 个已保存文件") + " · " +
                Utils.t("${segments.sumOf { it.sidecar.frameStats?.count ?: 0 }} capture callbacks", "${segments.sumOf { it.sidecar.frameStats?.count ?: 0 }} 次采集回调"),
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

private fun sessionTime(ms: Long): String = "%02d:%02d".format(Locale.ROOT, ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
private fun sessionEventLabel(event: DriveReviewEvent): String = when (event.kind) {
    DriveEventKind.TURN -> Utils.t("Indicators: ", "转向灯：") + when (event.value) {
        "left" -> Utils.t("left", "左"); "right" -> Utils.t("right", "右"); "hazard" -> Utils.t("hazard", "双闪"); else -> Utils.t("off", "关闭") }
    DriveEventKind.BRAKE -> if (event.value == "pressed") Utils.t("Brake pressed", "踩下制动") else Utils.t("Brake released", "松开制动")
    DriveEventKind.MANUAL -> Utils.t("Saved clip", "手动保存")
    DriveEventKind.EMERGENCY -> Utils.t("Emergency marker", "紧急标记")
    DriveEventKind.GPS_GAP -> Utils.t("GPS interruption", "GPS 中断")
    DriveEventKind.VEHICLE_GAP -> Utils.t("Vehicle data interruption", "车辆数据中断")
    DriveEventKind.RECORDING_INTERRUPTED -> Utils.t("Recording gap", "录像缺口")
}
