package com.dante.zeekrbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.OpenAvmHost
import com.dante.zeekrbridge.player.*
import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.drivemap.*
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionDrivePanel(
    data: LoadedSessionPlayback, bundle: DriveDataBundle?, frame: RecordedDriveFrame, status: DriveAttachmentStatus,
    positionMs: Long, channel: String, seekCount: Int, frames: Int, failures: Int, onSeek: (Long) -> Unit,
) {
    val context = LocalContext.current
    var showMap by rememberSaveable(data.timeline.sessionId) { mutableStateOf(false) }
    var showEvents by rememberSaveable(data.timeline.sessionId) { mutableStateOf(false) }
    var eventLimit by remember(data.timeline.sessionId) { mutableIntStateOf(20) }
    val review = data.review
    val roadPoints = remember(review.route) { review.route.points.map { RoadMapPoint(it.latitude, it.longitude, it.playbackMs, it.breakBefore) } }
    val marker = frame.gps?.let { point -> data.timeline.elapsedToSession(point.elapsedMs)?.let { at ->
        RoadMapPoint(point.latitude, point.longitude, at, point.breakBefore)
    } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Session review", "行程回看"), style = MaterialTheme.typography.titleMedium)
            val summary = review.summary
            Text(t("{0} · measured route {1} km", "{0} · 已记录轨迹 {1} 公里", formatPlayerTime(summary.durationMs),
                String.format(Locale.ROOT, "%.2f", summary.distanceM / 1_000)), style = MaterialTheme.typography.bodyMedium)
            Text(t("GPS coverage {0} · vehicle coverage {1}{2}", "GPS 覆盖 {0} · 车辆数据覆盖 {1}{2}",
                formatPlayerTime(summary.gpsCoveredMs), formatPlayerTime(summary.vehicleCoveredMs),
                if (summary.incomplete) t(" · incomplete", " · 不完整") else ""), style = MaterialTheme.typography.bodySmall)
            Text(when (status) {
                DriveAttachmentStatus.LOADING -> t("Loading current readings…", "正在读取当前读数…")
                DriveAttachmentStatus.AVAILABLE -> if (frame.gps == null && frame.vehicle == null)
                    t("No fresh readings at this position", "此位置没有新鲜读数") else t("Recorded readings · — unavailable", "已记录读数 · — 表示不可用")
                else -> t("No verified drive data for this video position", "此视频位置没有已验证的行驶数据")
            }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Vehicle {0}", "车辆 {0}", formatDriveSpeed(recordedDriveValue(frame, "speed")?.number)), Modifier.weight(1f))
                Text(t("GPS {0}", "GPS {0}", formatDriveSpeed(frame.gps?.speedMps?.times(3.6))), Modifier.weight(1f))
            }
            RecordedPedalDetails(frame)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) t("Hide map", "收起地图") else t("Show map", "显示地图")) }
                TextButton(onClick = { showEvents = !showEvents }) { Text(t("Events ({0})", "事件（{0}）", review.events.size)) }
            }
            if (showMap) {
                if (roadPoints.isEmpty()) Text(t("No recorded GPS route", "没有已记录的 GPS 轨迹")) else
                    RecordedRoadMap(roadPoints, marker, Modifier.fillMaxWidth(), PhoneLanguage.locale.language == "zh", onSeek)
                if (review.observationsLimited) Text(t("Review limit reached; coverage is partial.", "已达到查看上限，覆盖统计仅包含部分数据。"), style = MaterialTheme.typography.bodySmall)
            }
            if (showEvents) {
                if (review.events.isEmpty()) Text(t("No recorded events", "没有已记录的事件"), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    review.events.take(eventLimit).forEach { event ->
                        AssistChip(onClick = { onSeek(event.sessionMs) }, label = { Text("${formatPlayerTime(event.sessionMs)} ${eventLabel(event)}") })
                    }
                }
                if (eventLimit < review.events.size) TextButton(onClick = { eventLimit += 40 }) { Text(t("More events", "更多事件")) }
            }
            val recorder = review.recorderSnapshot ?: bundle?.recorderReport
            recorder?.channels?.forEach { report ->
                Text(t("{0}: {1} saved · {2} failed · {3} incomplete", "{0}：已保存 {1} · 失败 {2} · 未完成 {3}",
                    channelLabel(report.channelId), report.finalizedFiles, report.failedFiles, report.provisionalFiles), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                val report = recordedPlaybackReport(runCatching { OpenAvmHost.version(context) }.getOrNull(), review.route,
                    data.source.size, data.timeline.segments.indexOfFirst { it.id == data.timeline.seek(channel, positionMs)?.segmentId },
                    positionMs, status, frame, RoadMapDiagnostics.snapshot(), recorder,
                    bundle?.alignment, seekCount, frames, failures, data.timeline.channelIds.size, bundle?.videoClockAnchor,
                    recorderFromCurrentPosition = recorder != null && recorder == bundle?.recorderReport,
                    quantizedBoundaries = data.timeline.quantizedBoundaries)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM combined test report", report))
                Toast.makeText(context, t("Combined report copied", "综合报告已复制"), Toast.LENGTH_SHORT).show()
            }) { Text(t("Copy combined test report", "复制综合测试报告")) }
            if (recorder == null) Text(t("No recorder snapshot was received with this session.", "此行程尚未收到车机报告快照。"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatDriveSpeed(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }
    ?.let { String.format(Locale.ROOT, "%.1f km/h", it) } ?: "—"

private fun eventLabel(event: DriveReviewEvent): String = when (event.kind) {
    DriveEventKind.TURN -> when (event.value) {
        "left" -> t("Left turn signal", "左转灯")
        "right" -> t("Right turn signal", "右转灯")
        "hazard" -> t("Hazard lights", "双闪")
        else -> t("Turn signals off", "转向灯关闭")
    }
    DriveEventKind.BRAKE -> if (event.value == "pressed") t("Brake pressed", "制动踩下") else t("Brake released", "制动松开")
    DriveEventKind.GPS_GAP -> t("GPS gap", "GPS 中断")
    DriveEventKind.VEHICLE_GAP -> t("Vehicle data gap", "车辆数据中断")
    DriveEventKind.MANUAL -> t("Manual marker", "手动标记")
    DriveEventKind.EMERGENCY -> t("Emergency marker", "紧急标记")
    DriveEventKind.RECORDING_INTERRUPTED -> t("Recording gap", "录像中断")
}
