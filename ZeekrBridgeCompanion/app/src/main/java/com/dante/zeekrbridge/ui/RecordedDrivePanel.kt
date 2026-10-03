package com.dante.zeekrbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.player.*
import com.dante.zeekrbridge.OpenAvmHost
import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.drivemap.RecordedRoadMap
import io.github.dantenothing.drivemap.RoadMapPoint
import io.github.dantenothing.drivemap.RoadMapDiagnostics
import java.util.Locale

/** Received recording data only. The shared map owns its explicit street-map choice and offline fallback. */
@Composable
internal fun RecordedDrivePanel(
    playlistKey: List<String>,
    route: RecordedDriveRoute?,
    bundle: DriveDataBundle?,
    attachmentStatus: DriveAttachmentStatus,
    positionMs: Long?,
    mediaIndex: Int,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val context = LocalContext.current
    // Keep the user's choice while playback advances to another segment in the same playlist.
    var expanded by rememberSaveable(playlistKey) { mutableStateOf(false) }
    val frame = remember(bundle, positionMs, durationMs) { recordedPlaylistFrame(bundle, positionMs, durationMs) }
    val roadPoints = remember(route) { route?.points?.map { RoadMapPoint(it.latitude, it.longitude, it.playbackMs, it.breakBefore) }.orEmpty() }
    val marker = remember(bundle, frame, route, mediaIndex, durationMs) {
        route?.let { recordedPlaylistMarker(bundle, frame, it, mediaIndex, durationMs) }
            ?.let { RoadMapPoint(it.latitude, it.longitude, it.playbackMs, it.breakBefore) }
    }
    Card(Modifier.fillMaxWidth().testTag("recorded-drive-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Drive data", "行驶数据"), style = MaterialTheme.typography.titleMedium)
                    Text(recordedClockLabel(bundle?.videoClockAnchor),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("recorded-drive-toggle")) {
                    Text(if (expanded) t("Hide", "收起") else t("Show", "展开"))
                }
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriveValue(t("Vehicle speed", "车辆速度"), driveSpeed(recordedDriveValue(frame, "speed")?.number), Modifier.weight(1f), "recorded-vehicle-speed")
                    DriveValue(t("GPS speed", "GPS 速度"), driveSpeed(frame.gps?.speedMps?.times(3.6)), Modifier.weight(1f), "recorded-gps-speed")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriveValue(t("Gear", "挡位"), recordedDriveValue(frame, "gear")?.text ?: "—", Modifier.weight(1f), "recorded-gear")
                    DriveValue(t("Turn signal", "转向灯"), driveTurnLabel(recordedDriveTurn(frame)), Modifier.weight(1f), "recorded-turn")
                    DriveValue(t("Brake pedal", "制动踏板"), driveSwitch(recordedDriveValue(frame, "brake_pedal")?.number), Modifier.weight(1f), "recorded-brake")
                }
                Text(when (attachmentStatus) {
                    DriveAttachmentStatus.LOADING -> t("Loading this segment's drive data…", "正在读取此分段的行驶数据…")
                    DriveAttachmentStatus.TIME_LAPSE -> t("Synchronized drive data is unavailable for time-lapse video.", "缩时视频暂不支持同步行驶数据。")
                    DriveAttachmentStatus.NOT_LOCAL, DriveAttachmentStatus.MISSING -> t("No verified drive data attached to this segment.", "此分段未附带已验证的行驶数据。")
                    DriveAttachmentStatus.ROUTE_LIMIT -> t("Route limit reached. Open fewer segments to see the whole route.", "已达到轨迹上限，请减少播放分段以查看完整轨迹。")
                    DriveAttachmentStatus.AVAILABLE -> when {
                        frame.elapsedMs == null -> t("No drive data at this position · — unavailable", "此播放位置无行驶数据 · — 表示不可用")
                        bundle?.gps.isNullOrEmpty() -> t("No GPS recorded for this segment · — unavailable", "此分段未记录 GPS · — 表示不可用")
                        frame.gps == null -> t("GPS missing or in a gap · — unavailable", "GPS 缺失或中断 · — 表示不可用")
                        else -> t("Recorded readings at the playhead · — unavailable", "播放位置对应的已记录读数 · — 表示不可用")
                    }
                }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recorded-drive-status"))
                RecordedPedalDetails(frame)
                if (route == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(t("Loading the playlist route…", "正在读取播放列表轨迹…"), style = MaterialTheme.typography.bodySmall)
                } else {
                    if (roadPoints.isNotEmpty()) {
                        RecordedRoadMap(roadPoints, marker, Modifier.fillMaxWidth().testTag("recorded-drive-map"),
                            chinese = PhoneLanguage.locale.language == "zh", onSeek = onSeek)
                    } else Text(t("No recorded GPS route is available for this playlist.", "此播放列表没有可用的已记录 GPS 轨迹。"), style = MaterialTheme.typography.bodySmall)
                    if (route.missingSegments > 0 || route.unsupportedSegments > 0) Text(t(
                        "Route gaps: {0} segments without data · {1} time-lapse segments", "轨迹缺口：{0} 个分段无数据 · {1} 个缩时分段",
                        route.missingSegments, route.unsupportedSegments), style = MaterialTheme.typography.bodySmall)
                    if (route.limitedSegments > 0) Text(t("This playlist exceeds the route limit. Open fewer segments for the full route.",
                        "此播放列表超过轨迹上限，请减少播放分段以查看完整轨迹。"), style = MaterialTheme.typography.bodySmall)
                    if (route.timingIncomplete) Text(t("Some video durations are still unavailable; their route points cannot be placed yet.",
                        "部分视频时长暂不可用，尚无法定位相应轨迹点。"), style = MaterialTheme.typography.bodySmall)
                    if (route.simplified && roadPoints.isNotEmpty()) Text(t("Route overview simplified; current readings keep their original timing.",
                        "轨迹总览已简化，当前读数仍使用原始时间。"), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    val report = recordedPlaybackReport(runCatching { OpenAvmHost.version(context) }.getOrNull(), route,
                        playlistKey.size, mediaIndex, positionMs, attachmentStatus, frame, RoadMapDiagnostics.snapshot(),
                        bundle?.recorderReport, bundle?.alignment, anchor = bundle?.videoClockAnchor)
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM playback report", report))
                    Toast.makeText(context, t("Playback report copied", "回放报告已复制"), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.align(Alignment.End).testTag("recorded-drive-copy-report")) {
                    Text(t("Copy combined test report", "复制综合测试报告"))
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
