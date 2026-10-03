package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.telemetry.*
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun vehicleTelemetryStatusText(status: String): String = when (status) {
    "DISABLED" -> Utils.t("Vehicle data recording is off", "车辆数据记录已关闭")
    "IDLE" -> Utils.t("Ready for the next recording", "将在下次录像时记录")
    "WAITING" -> Utils.t("Waiting for vehicle readings", "正在等待车辆读数")
    "RECORDING" -> Utils.t("Vehicle readings are being recorded", "正在记录车辆读数")
    "SDK_TIMEOUT" -> Utils.t("Vehicle read timed out · video continues", "车辆读取超时 · 录像继续")
    "SDK_BUSY" -> Utils.t("Vehicle reader busy · video continues", "车辆读取忙碌 · 录像继续")
    "STORAGE_ERROR" -> Utils.t("Vehicle data could not be saved · video continues", "车辆数据保存失败 · 录像继续")
    "LIMIT_REACHED" -> Utils.t("Vehicle data limit reached · video continues", "车辆数据已达上限 · 录像继续")
    else -> Utils.t("Vehicle data unavailable · video continues", "车辆数据不可用 · 录像继续")
}

internal fun vehicleSpeedText(speed: Double?): String = speed?.let {
    String.format(Locale.getDefault(), "%.0f km/h", it)
} ?: Utils.t("Speed unavailable", "暂无速度")

internal fun vehiclePlaybackText(sample: VehicleTelemetrySample?): String = buildString {
    append(Utils.t("Vehicle", "车辆")); append(" · "); append(vehicleSpeedText(sample?.speedKmh))
    append(" · "); append(sample?.gear ?: "—")
    when (sample?.indicator) {
        "left" -> append(" · ←")
        "right" -> append(" · →")
        "hazard" -> append(" · ↔")
    }
    if (sample?.brakePressed == true) append(" · " + Utils.t("Brake", "制动"))
}

@Composable
internal fun VehicleTelemetrySettingsCard() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()
    val state by RecordingVehicleTelemetry.state.collectAsState()
    val recording by CameraRecordingService.state.collectAsState()
    val busy = CameraRecordingService.isRunning() || RecorderCommandPolicy.isActive(recording.status)
    var enabled by remember { mutableStateOf(settings.recordingVehicleTelemetryEnabled) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Utils.t("Vehicle data with recordings", "随录像记录车辆数据"), style = MaterialTheme.typography.titleMedium)
                    Text(Utils.t("Optional · speed, gear, indicators and braking", "可选 · 车速、挡位、转向和制动"), style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, onCheckedChange = {
                    enabled = it; settings.setRecordingVehicleTelemetryEnabled(it)
                    CameraRecordingService.refreshTelemetrySetting()
                }, enabled = !clearing && (enabled || !busy), modifier = Modifier.testTag("vehicle-telemetry-switch"))
            }
            Text(Utils.t("Read-only vehicle data is saved alongside video for playback. It works independently of GPS. Missing readings remain unknown; recording continues.",
                "只读车辆数据与视频一同保存，供回看时显示。它独立于 GPS；读不到的数据保留为未知，录像继续。"))
            if (enabled) Text(vehicleTelemetryStatusText(if (busy) state.status.name else "IDLE"),
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("vehicle-telemetry-status"))
            else if (busy) Text(Utils.t("Stop recording before enabling this option.", "请停止录像后再开启此选项。"))
            Text(Utils.t("Stored only on this head unit, up to 64 MB and 30 days. This preview does not transfer vehicle history to the phone or burn it into videos. Readings are approximately aligned with playback.",
                "仅保存在此车机，最多 64 MB、30 天。此测试版尚不将车辆记录传给手机或烧录进画面，回放数据与视频近似对齐。"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && !clearing,
                modifier = Modifier.testTag("vehicle-telemetry-clear")) { Text(Utils.t("Clear vehicle data history", "清除车辆数据记录")) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(Utils.t("Clear vehicle data history?", "清除车辆数据记录？")) },
        text = { Text(Utils.t("Videos and GPS tracks are kept. Stop recording before clearing.", "录像和 GPS 轨迹会保留。请在停止录像后清除。")) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false; clearing = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    !CameraRecordingService.isRunning() && !RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status) &&
                        runCatching { RecordingVehicleTelemetry.store(context).clear() }.getOrDefault(false)
                }
                message = if (ok) Utils.t("Vehicle data history cleared", "车辆数据记录已清除")
                    else Utils.t("Unable to clear now. Stop recording and try again.", "暂时无法清除，请停止录像后重试。")
                clearing = false
            }
        }) { Text(Utils.t("Clear", "清除")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(Utils.t("Cancel", "取消")) } })
}

@Composable
internal fun RecordingVehicleTelemetryLabel() {
    val context = LocalContext.current
    val state by RecordingVehicleTelemetry.state.collectAsState()
    if (SettingsStore.get(context).recordingVehicleTelemetryEnabled) {
        Text(vehicleTelemetryStatusText(state.status.name), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp).testTag("vehicle-recording-status"))
    }
}
