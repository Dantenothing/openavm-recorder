package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
            Text(Utils.t("Saved on this head unit for up to 30 days and 64 MB. Older tracks are removed when a new recording starts. You can explicitly include route and vehicle data when sending selected videos to your phone. Ordinary video exports contain no route data.",
                "车机最多保存 30 天及 64 MB，开始新录像时清理旧轨迹。发送选中视频到手机时，可自行勾选附带路线和车辆数据。普通视频导出不含路线数据。"), style = MaterialTheme.typography.bodySmall)
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
        text = { Text(Utils.t("Saved routes and GPS speed on this head unit will be deleted. Videos stay in the library. Copies already queued for transfer, received on a phone, or exported as GPX remain.", "将删除此车机的位置与定位速度记录，媒体库录像会保留。已加入传输队列、手机已接收及已导出的 GPX 副本仍会保留。")) },
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
internal fun TelemetryRoutePanel(
    route: TelemetryPlaylistRoute?, sample: TelemetryPlaylistPoint?, loading: Boolean,
    onSeek: (Long) -> Unit, onDismiss: () -> Unit, onLeaveApp: () -> Unit,
    modifier: Modifier = Modifier,
    vehicleSample: VehicleTelemetrySample? = null, vehicleAvailable: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val mapPoints = remember(route) { route?.points.orEmpty().map {
        io.github.dantenothing.drivemap.RoadMapPoint(it.fix.latitude, it.fix.longitude, it.playbackMs, it.fix.breakBefore)
    } }
    val current = sample?.let {
        io.github.dantenothing.drivemap.RoadMapPoint(it.fix.latitude, it.fix.longitude, it.playbackMs, it.fix.breakBefore)
    }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val selection = pendingSelection
        pendingSelection = null
        if (uri != null && selection != null) {
            exportBusy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching {
                    val windows = requireNotNull(TelemetryPlaylistRoutes.readSelection(selection))
                    val store = RecordingTelemetry.store(context)
                    val selected = TelemetryPlaylistRoutes.build(windows, store::load)
                    val gpx = TelemetryPlaylistRoutes.gpx(selected)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(gpx.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess }
                exportBusy = false
                exportMessage = if (ok) Utils.t("Playback route saved as GPX", "当前回放路线已保存为 GPX")
                    else Utils.t("Could not save GPX; the selected history may have been cleared.", "未能保存 GPX；所选轨迹可能已被清除。")
            }
        }
    }
    val saveRoute: () -> Unit = {
        route?.let { selected ->
            pendingSelection = TelemetryPlaylistRoutes.selection(selected)
            onLeaveApp()
            exportGpx.launch("OpenAVM-playback-route.gpx")
        }
    }
    val pointUrl = sample?.fix?.let(TelemetryRouteExport::googleMapsPointUrl)
    val openPoint: () -> Unit = {
        onLeaveApp()
        if (pointUrl != null && runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pointUrl.toUri())) }.isFailure) {
            exportMessage = Utils.t("No map app or browser available", "没有可用的地图应用或浏览器")
        }
    }
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val compact = maxHeight < 420.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Utils.t("Route & drive data", "路线与行车数据"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (compact && mapPoints.isNotEmpty()) Box {
                        TextButton(onClick = { actionsExpanded = true }) { Text(Utils.t("More", "更多")) }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(text = { Text(Utils.t("Save route GPX", "保存路线 GPX")) },
                                onClick = { actionsExpanded = false; saveRoute() }, enabled = !exportBusy && pendingSelection == null,
                                modifier = Modifier.testTag("telemetry-export-gpx"))
                            DropdownMenuItem(text = { Text(Utils.t("Open this point in Google Maps", "在 Google 地图打开此位置")) },
                                onClick = { actionsExpanded = false; openPoint() }, enabled = pointUrl != null,
                                modifier = Modifier.testTag("telemetry-google-point"))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(Utils.t("Hide map", "收起地图")) }
                }
                Text("GPS · " + telemetrySpeedText(sample?.fix?.speedMps), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("telemetry-speed"))
                Text(Utils.t("Approximate video alignment", "与视频近似对齐"), style = MaterialTheme.typography.bodySmall)
                if (vehicleAvailable) Text(vehiclePlaybackText(vehicleSample), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("vehicle-playback-data"))
                Box(Modifier.fillMaxWidth().weight(1f).testTag("telemetry-route"), contentAlignment = Alignment.Center) {
                    when {
                        loading -> Text(Utils.t("Loading route…", "正在读取轨迹…"))
                        mapPoints.isEmpty() -> Text(if (route?.incomplete == true)
                            Utils.t("Waiting for video timing. The route will appear when segment durations are known.", "正在读取视频时长，分段时间确认后将显示路线。")
                            else Utils.t("No GPS recorded for these videos. Enable Location & speed before your next recording; video works without GPS.", "这些视频没有 GPS 记录。下次录像前可开启“位置与速度”；无定位也能正常录像。"),
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        else -> io.github.dantenothing.drivemap.RecordedRoadMap(
                            points = mapPoints, current = current, modifier = Modifier.fillMaxSize(),
                            chinese = com.dante.zeekrcapabilitylab.product.AppLanguage.usesChinese(), onSeek = onSeek)
                    }
                }
                if (mapPoints.isNotEmpty()) {
                    Text(if (sample == null) Utils.t("No fresh GPS here · tap the route to seek video", "此时没有有效定位 · 点路线可跳转视频")
                        else Utils.t("Tap the route to seek · use the video controls below", "点路线跳转 · 下方可暂停和拖动视频"), style = MaterialTheme.typography.bodySmall)
                    if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = saveRoute, enabled = !exportBusy && pendingSelection == null,
                            modifier = Modifier.weight(1f).testTag("telemetry-export-gpx")) { Text(Utils.t("Save route GPX", "保存路线 GPX")) }
                        OutlinedButton(onClick = openPoint, enabled = pointUrl != null, modifier = Modifier.weight(1f).testTag("telemetry-google-point")) {
                            Text(Utils.t("Open this point", "打开此时位置"))
                        }
                    }
                    if (!compact) Text(Utils.t("Map and GPX include only the videos in this playback. Gaps stay blank. Opening a point shares that coordinate with Google Maps.",
                        "地图与 GPX 仅包含当前回放的视频，缺失位置保留断线。打开位置会将该坐标交给 Google 地图。"), style = MaterialTheme.typography.bodySmall)
                    if (route?.incomplete == true) Text(Utils.t("Some segments are not shown until their timing is available, or the route limit is reached.", "部分分段时间尚未确认或已达到路线显示上限。"), style = MaterialTheme.typography.bodySmall)
                }
                if (exportBusy) Text(Utils.t("Saving GPX…", "正在保存 GPX…"))
                exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
