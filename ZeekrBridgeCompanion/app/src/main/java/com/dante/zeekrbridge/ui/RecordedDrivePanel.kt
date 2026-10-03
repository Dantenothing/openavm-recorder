package com.dante.zeekrbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.player.RecordedTrace
import com.dante.zeekrbridge.player.RecordedTracePoint
import com.dante.zeekrbridge.player.recordedDriveFrame
import com.dante.zeekrbridge.player.recordedDriveTrace
import com.dante.zeekrbridge.player.recordedDriveTurn
import com.dante.zeekrbridge.player.recordedDriveValue
import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Uses only a verified received attachment. It never requests the phone's location. */
@Composable
internal fun RecordedDrivePanel(bundle: DriveDataBundle, positionMs: Long?, mediaId: String) {
    var expanded by rememberSaveable(mediaId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("recorded-drive-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Drive data", "行驶数据"), style = MaterialTheme.typography.titleMedium)
                    Text(t("Recorded with this video · approximate timing", "随视频记录 · 时间近似对齐"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("recorded-drive-toggle")) {
                    Text(if (expanded) t("Hide", "收起") else t("Show", "展开"))
                }
            }
            if (expanded) {
                val frame = remember(bundle, positionMs) { recordedDriveFrame(bundle, positionMs) }
                val trace = remember(bundle, frame) { recordedDriveTrace(bundle.gps, frame) }
                val vehicleSpeed = recordedDriveValue(frame, "speed")?.number
                val gpsSpeed = frame.gps?.speedMps?.times(3.6)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriveValue(t("Vehicle speed", "车辆速度"), driveSpeed(vehicleSpeed), Modifier.weight(1f), "recorded-vehicle-speed")
                    DriveValue(t("GPS speed", "GPS 速度"), driveSpeed(gpsSpeed), Modifier.weight(1f), "recorded-gps-speed")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriveValue(t("Gear", "挡位"), recordedDriveValue(frame, "gear")?.text ?: "—", Modifier.weight(1f), "recorded-gear")
                    DriveValue(t("Turn signal", "转向灯"), driveTurnLabel(recordedDriveTurn(frame)), Modifier.weight(1f), "recorded-turn")
                    DriveValue(t("Brake pedal", "制动踏板"), driveSwitch(recordedDriveValue(frame, "brake_pedal")?.number), Modifier.weight(1f), "recorded-brake")
                }
                Text(when {
                    frame.elapsedMs == null -> t("No drive data at this playback position.", "当前播放位置没有可用行驶数据。")
                    frame.gps == null && frame.vehicle == null -> t("No fresh data here · — means unavailable", "此处无新鲜数据 · — 表示不可用")
                    bundle.gps.isEmpty() -> t("No recorded GPS · — means unavailable", "未记录 GPS · — 表示不可用")
                    trace.current == null -> t("Current GPS point unavailable · — means unavailable", "当前 GPS 点不可用 · — 表示不可用")
                    else -> t("Dot: latest recorded GPS point · — means unavailable", "圆点：最近的已记录 GPS 点 · — 表示不可用")
                }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recorded-drive-status"))
                if (trace.pointCount > 0) {
                    Text(t("GPS trace · north up · no street map", "GPS 轨迹 · 上方为北 · 无街道底图"), style = MaterialTheme.typography.titleSmall)
                    RecordedTraceCanvas(trace)
                }
            }
        }
    }
}

@Composable
private fun DriveValue(label: String, value: String, modifier: Modifier, tag: String) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun RecordedTraceCanvas(trace: RecordedTrace) {
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val description = t("Recorded GPS trace through the playback position, {0} points. {1}", "播放位置之前的 GPS 轨迹，共 {0} 个点。{1}",
        trace.pointCount, if (trace.current == null) t("Current marker unavailable.", "当前标记不可用。") else t("Current marker shown.", "已显示当前标记。"))
    Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("recorded-drive-trace").semantics { contentDescription = description }) {
        drawRect(background)
        val points = trace.strokes.flatten()
        if (points.isEmpty()) return@Canvas
        val minX = points.minOf { it.eastM }; val maxX = points.maxOf { it.eastM }
        val minY = points.minOf { it.northM }; val maxY = points.maxOf { it.northM }
        val padding = 18.dp.toPx()
        val scale = min((size.width - padding * 2).coerceAtLeast(1f) / max(10.0, maxX - minX),
            (size.height - padding * 2).coerceAtLeast(1f) / max(10.0, maxY - minY))
        val centerX = (minX + maxX) / 2.0; val centerY = (minY + maxY) / 2.0
        fun location(point: RecordedTracePoint) = Offset(
            (size.width / 2.0 + (point.eastM - centerX) * scale).toFloat(),
            (size.height / 2.0 - (point.northM - centerY) * scale).toFloat())
        drawLine(grid, Offset(size.width / 2, padding), Offset(size.width / 2, size.height - padding), 1.dp.toPx())
        drawLine(grid, Offset(padding, size.height / 2), Offset(size.width - padding, size.height / 2), 1.dp.toPx())
        trace.strokes.forEach { stroke ->
            if (stroke.size == 1) drawCircle(line, 2.dp.toPx(), location(stroke.first()))
            else if (stroke.isNotEmpty()) {
                val path = Path()
                stroke.forEachIndexed { index, point ->
                    val pointAt = location(point)
                    if (index == 0) path.moveTo(pointAt.x, pointAt.y) else path.lineTo(pointAt.x, pointAt.y)
                }
                drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        trace.current?.let { drawCircle(background, 8.dp.toPx(), location(it)); drawCircle(marker, 5.dp.toPx(), location(it)) }
    }
}

private fun driveSpeed(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.ROOT, "%.1f km/h", it) } ?: "—"

private fun driveSwitch(value: Double?): String = when (value) { 1.0 -> t("Pressed", "已踩下"); 0.0 -> t("Released", "已松开"); else -> "—" }

private fun driveTurnLabel(value: String?): String = when (value) {
    "left" -> t("Left", "左转")
    "right" -> t("Right", "右转")
    "hazard" -> t("Hazard", "双闪")
    "off" -> t("Off", "关闭")
    else -> "—"
}
