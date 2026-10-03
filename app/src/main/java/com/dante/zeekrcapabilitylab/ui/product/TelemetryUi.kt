package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.telemetry.*
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun telemetryStatusText(status: TelemetryStatus): String = when (status) {
    TelemetryStatus.DISABLED -> Utils.t("Location recording is off", "位置记录已关闭")
    TelemetryStatus.IDLE -> Utils.t("Ready for the next recording", "将在下次录像时记录")
    TelemetryStatus.WAITING -> Utils.t("Waiting for an accurate location", "正在等待准确定位")
    TelemetryStatus.RECORDING -> Utils.t("Location is being recorded", "正在记录位置")
    TelemetryStatus.STALE -> Utils.t("Location interrupted · video continues", "定位中断 · 录像继续")
    TelemetryStatus.NO_PERMISSION -> Utils.t("Location permission needed", "需要位置权限")
    TelemetryStatus.LOCATION_OFF -> Utils.t("Turn on system Location", "请开启系统定位")
    TelemetryStatus.NO_PROVIDER -> Utils.t("No usable location provider on this device", "本设备暂无可用定位来源")
    TelemetryStatus.FOREGROUND_REQUIRED -> Utils.t("Open OpenAVM, then restart recording to enable location", "请打开 OpenAVM 后重新开始录像以启用定位")
    TelemetryStatus.STORAGE_ERROR -> Utils.t("Location could not be saved · video continues", "位置保存失败 · 录像继续")
    TelemetryStatus.LIMIT_REACHED -> Utils.t("Location limit reached for this recording · video continues", "本次位置记录已达上限 · 录像继续")
}

internal fun telemetrySpeedText(speedMps: Float?): String = speedMps?.let {
    String.format(Locale.getDefault(), "%.0f km/h", it * 3.6f)
} ?: Utils.t("Speed unavailable", "暂无速度")

@Composable
internal fun TelemetrySettingsCard() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()
    val capture by RecordingTelemetry.state.collectAsState()
    val recording by CameraRecordingService.state.collectAsState()
    val busy = RecorderCommandPolicy.isActive(recording.status) || CameraRecordingService.isRunning()
    var enabled by remember { mutableStateOf(settings.recordingTelemetryEnabled) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(RecordingTelemetry.hasPermission(context)) }
    var precisePermission by remember { mutableStateOf(RecordingTelemetry.hasPrecisePermission(context)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = RecordingTelemetry.hasPermission(context)
                precisePermission = RecordingTelemetry.hasPrecisePermission(context)
                if (permissionGranted) message = null
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun setEnabled(value: Boolean) {
        enabled = value; settings.setRecordingTelemetryEnabled(value)
        CameraRecordingService.refreshTelemetrySetting()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = RecordingTelemetry.hasPermission(context)
        precisePermission = RecordingTelemetry.hasPrecisePermission(context)
        if (RecordingTelemetry.hasPermission(context) && !CameraRecordingService.isRunning() &&
            !RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status)) {
            setEnabled(true); message = null
        } else {
            message = Utils.t("Allow location access before enabling this option. Video recording does not need it.", "启用此选项需要位置权限；普通录像不需要。")
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Utils.t("Location & speed", "位置与速度"), style = MaterialTheme.typography.titleMedium)
                    Text(Utils.t("Optional · recorded with your video", "可选 · 随录像记录"), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, enabled = !clearing && (enabled || !busy), onCheckedChange = { value ->
                    message = null
                    if (!value) setEnabled(false)
                    else if (RecordingTelemetry.hasPermission(context)) setEnabled(true)
                    else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }, modifier = Modifier.testTag("telemetry-switch"))
            }
            Text(Utils.t("Saves location and available GPS/location speed while recording. Speed may differ from the dashboard. If positioning is unavailable, video keeps recording.",
                "录像时保存位置及定位服务提供的速度。速度可能与仪表不同；无法定位时仍可正常录像。"), style = MaterialTheme.typography.bodyMedium)
            if (enabled) {
                val status = if (!permissionGranted) TelemetryStatus.NO_PERMISSION
                    else if (!busy) TelemetryStatus.IDLE else capture.status
                Text(telemetryStatusText(status), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("telemetry-settings-status"))
                if (!precisePermission) Text(Utils.t("For a usable route, allow precise location in app permissions.", "要记录有效轨迹，请在应用权限中允许精确位置。"),
                    style = MaterialTheme.typography.bodySmall)
                if (!permissionGranted) TextButton(onClick = {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }, enabled = !busy) { Text(Utils.t("Grant location permission", "授予位置权限")) }
            } else if (busy) Text(Utils.t("Stop recording before enabling location.", "请停止录像后再开启位置记录。"))
            Text(Utils.t("Stored only on this head unit. Up to 30 days and 64 MB; older tracks are removed when a new recording starts. Phone transfers and video exports do not include tracks in this preview.",
                "仅保存在此车机，最多保留 30 天及 64 MB，开始新录像时清理旧轨迹。此测试版的手机传输及视频导出不包含轨迹。"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && !clearing,
                modifier = Modifier.testTag("telemetry-clear")) { Text(Utils.t("Clear location history", "清除位置记录")) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if ((message != null && !permissionGranted) || (enabled && !precisePermission)) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    ("package:" + context.packageName).toUri())) }
            }) { Text(Utils.t("Open app permissions", "打开应用权限设置")) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(Utils.t("Clear location history?", "清除位置记录？")) },
        text = { Text(Utils.t("All saved routes and speed samples on this head unit will be deleted. Your videos stay in the library.", "将删除此车机保存的所有轨迹和速度记录，媒体库中的录像会保留。")) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false; clearing = true
            scope.launch {
                val cleared = withContext(Dispatchers.IO) {
                    !CameraRecordingService.isRunning() && !RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status) &&
                        runCatching { RecordingTelemetry.store(context).clear() }.getOrDefault(false)
                }
                message = if (cleared) Utils.t("Location history cleared. Videos kept.", "位置记录已清除，录像已保留。")
                    else Utils.t("Unable to clear now. Stop recording and try again.", "暂时无法清除，请停止录像后重试。")
                clearing = false
            }
        }) { Text(Utils.t("Clear", "清除")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(Utils.t("Cancel", "取消")) } })
}

@Composable
internal fun RecordingTelemetryLabel() {
    val context = LocalContext.current
    val capture by RecordingTelemetry.state.collectAsState()
    if (SettingsStore.get(context).recordingTelemetryEnabled) {
        Text(telemetryStatusText(if (capture.status == TelemetryStatus.DISABLED) TelemetryStatus.IDLE else capture.status) +
            if (capture.status == TelemetryStatus.RECORDING) " · " + telemetrySpeedText(capture.speedMps) else "",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
internal fun TelemetryRouteCanvas(points: List<TelemetryFix>, sample: TelemetryFix?, modifier: Modifier = Modifier) {
    val projected = remember(points) { TelemetryProjection.project(points) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val edge = with(LocalDensity.current) { 20.dp.toPx() }
    val side = (minOf(bounds.width, bounds.height) - edge * 2).coerceAtLeast(1f)
    val origin = Offset((bounds.width - side) / 2, (bounds.height - side) / 2)
    // Geometry is built once per track/size, not on every playback position update.
    val path = remember(projected, bounds, edge) { Path().apply { projected.forEach {
        val x = origin.x + it.x * side; val y = origin.y + it.y * side
        if (it.connected) lineTo(x, y) else moveTo(x, y)
    } } }
    val starts = remember(projected, bounds, edge) { projected.filter { !it.connected }.map {
        origin + Offset(it.x * side, it.y * side)
    } }
    val index = remember(points, sample) { sample?.let { points.binarySearchBy(it.elapsedMs) { fix -> fix.elapsedMs } } ?: -1 }
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.background(background).onSizeChanged { bounds = it }.testTag("telemetry-route")) {
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        starts.forEach { drawCircle(muted, 3.dp.toPx(), it) }
        projected.getOrNull(index)?.let { drawCircle(color, 7.dp.toPx(), origin + Offset(it.x * side, it.y * side)) }
    }
}

@Composable
internal fun TelemetryRouteDialog(
    track: TelemetryTrack?, sample: TelemetryFix?, loading: Boolean, aligned: Boolean,
    positionMs: Long, durationMs: Long, playing: Boolean, onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit, onToggle: () -> Unit, onDismiss: () -> Unit,
    vehicleSample: VehicleTelemetrySample? = null, vehicleAvailable: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSession by rememberSaveable { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val session = pendingSession
        pendingSession = null
        if (uri != null && session != null) {
            exportBusy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching {
                    val saved = requireNotNull(RecordingTelemetry.store(context).load(session))
                    require(saved.points.isNotEmpty())
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(TelemetryRouteExport.gpx(saved).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess }
                exportBusy = false
                exportMessage = if (ok) Utils.t("GPX saved with recorded coordinates", "GPX 已保存，包含已记录的位置")
                    else Utils.t("Could not save GPX", "未能保存 GPX")
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.widthIn(max = 1080.dp).fillMaxWidth(0.94f).heightIn(max = 760.dp).fillMaxHeight(0.94f)) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Utils.t("Drive playback", "行车回放"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val routeHeight = (maxHeight - 120.dp).coerceIn(160.dp, 540.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(telemetrySpeedText(sample?.speedMps), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.testTag("telemetry-speed"))
                        Text(Utils.t("GPS/location speed · approximate video alignment", "定位速度 · 与视频近似对齐"), style = MaterialTheme.typography.bodySmall)
                        if (vehicleAvailable) {
                            Text(vehiclePlaybackText(vehicleSample), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("vehicle-playback-data"))
                            Text(Utils.t("Vehicle readings · independently timestamped · missing data stays unknown", "车辆读数 · 独立时间戳 · 缺失数据保留为未知"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            loading -> Text(Utils.t("Loading route…", "正在读取轨迹…"))
                            track == null || track.points.isEmpty() -> Text(Utils.t("No location was recorded for this video, or its local history has been cleared.", "此视频没有位置记录，或本地轨迹已被清除。"))
                            else -> {
                                if (!aligned) Text(Utils.t("Route only: this video has no supported time alignment. Time-lapse speed playback is not enabled in this preview.", "仅显示轨迹：此视频暂不支持时间对齐。测试版尚未启用延时录像的速度回放。"))
                                else if (sample == null) Text(Utils.t("No fresh location at this point in the video.", "此视频时刻没有有效定位。"))
                                TelemetryRouteCanvas(track.points, sample, Modifier.fillMaxWidth().height(routeHeight))
                                Text(Utils.t("Local trace, without a map background. Breaks indicate missing or rejected location data.", "本地轨迹，不含地图底图。断线表示定位缺失或数据未通过检查。"), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        pendingSession = track.header.sessionId
                                        exportGpx.launch("OpenAVM-${track.header.sessionId.take(8)}.gpx")
                                    }, enabled = !exportBusy && pendingSession == null,
                                        modifier = Modifier.testTag("telemetry-export-gpx")) { Text(Utils.t("Save session GPX", "保存本次录像 GPX")) }
                                    val pointUrl = sample?.let(TelemetryRouteExport::googleMapsPointUrl)
                                    OutlinedButton(onClick = {
                                        if (playing) onToggle()
                                        if (pointUrl != null && runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pointUrl.toUri())) }.isFailure) {
                                            exportMessage = Utils.t("No map app or browser available", "没有可用的地图应用或浏览器")
                                        }
                                    }, enabled = pointUrl != null, modifier = Modifier.testTag("telemetry-google-point")) {
                                        Text(Utils.t("Google Maps · this point", "Google 地图 · 此时位置"))
                                    }
                                }
                                Text(Utils.t("GPX includes all saved locations from this recording session, including other segments. Google Maps opens only the selected point and receives that coordinate; it is not synchronized route playback.",
                                    "GPX 包含本次录像所有已保存的位置，也包含其他分段。Google 地图只打开所选位置点，并会收到该坐标；它不是同步轨迹回放。"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (exportBusy) Text(Utils.t("Saving GPX…", "正在保存 GPX…"))
                        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Slider(value = positionMs.coerceIn(0, durationMs.coerceAtLeast(1)).toFloat(),
                    onValueChange = { onScrub(it.toLong()) }, onValueChangeFinished = onScrubEnd,
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                    enabled = durationMs > 0, modifier = Modifier.testTag("telemetry-seek"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggle, enabled = durationMs > 0) { Text(if (playing) Utils.t("Pause", "暂停") else Utils.t("Play", "播放")) }
                    Spacer(Modifier.weight(1f))
                    Text(telemetryTime(positionMs) + " / " + telemetryTime(durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun telemetryTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
