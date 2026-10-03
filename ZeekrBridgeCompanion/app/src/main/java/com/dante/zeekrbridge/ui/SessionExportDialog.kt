package com.dante.zeekrbridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.player.SessionLoadResult
import com.dante.zeekrbridge.player.loadSessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** This range is session time. The planner verifies every selected physical interval before queuing. */
@Composable
internal fun SessionExportDialog(segments: List<IndexedMediaSegment>, origin: SavedMediaOrigin?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loadAttempt by remember(segments) { mutableIntStateOf(0) }
    val loaded by produceState<SessionLoadResult<List<IndexedMediaSegment>, LoadedSessionPlayback>?>(null, segments, loadAttempt) {
        value = loadSessionResult(segments, loadAttempt) { withContext(Dispatchers.IO) { loadSessionPlayback(context, segments, loadReview = false) } }
    }
    val result = loaded?.takeIf { it.matches(segments, loadAttempt) }
    val data = result?.data
    var channel by remember(segments) { mutableStateOf(segments.firstOrNull()?.playbackChannelId ?: "SURROUND") }
    val timeline = data?.timeline
    val channelSegments = remember(segments, channel) { segments.filter { it.playbackChannelId == channel } }
    val duration = timeline?.durationMs ?: 0L
    val first = timeline?.segments?.firstOrNull { it.channelId == channel }
    var selected by remember(channel) { mutableStateOf(setOf(MediaExportTarget.ORIGINAL)) }
    var range by remember(timeline, channel) {
        mutableStateOf((first?.let { timeline?.mediaToSession(it.id, 0) } ?: 0L).toFloat()..duration.toFloat())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val from = range.start.toLong().coerceIn(0, duration)
    val to = range.endInclusive.toLong().coerceIn(from, duration)
    val slices = timeline?.range(channel, from, to)
    val withinLimits = slices != null && slices.size <= MediaExportPlanner.MAX_SESSION_EXPORT_SEGMENTS &&
        to - from <= MediaExportPlanner.MAX_SESSION_EXPORT_MS
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Export a camera clip", "导出摄像头片段")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data == null) {
                if (result == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(t("Measuring available video…", "正在读取可用视频时长…"))
                } else {
                    Text(t("Could not read this session's timing. Retry or choose another recording.", "无法读取此行程的时间信息，请重试或选择其他录像。"))
                    TextButton(onClick = { loadAttempt++ }) { Text(t("Retry", "重试")) }
                }
            } else if (timeline?.channelIds.isNullOrEmpty()) {
                Text(t("No readable timed video is available for export.", "没有可读取且有时间信息的视频可供导出。"))
                TextButton(onClick = { loadAttempt++ }) { Text(t("Retry", "重试")) }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    timeline?.channelIds.orEmpty().forEach { option ->
                        FilterChip(option == channel, { channel = option; error = null }, label = { Text(channelLabel(option)) })
                    }
                }
                Text(t("Session time · up to 2 hours / 200 segments", "行程时间 · 每次最多 2 小时 / 200 个分段"), style = MaterialTheme.typography.bodySmall)
                RangeSlider(range, { range = it; error = null }, valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0)
                Text("${formatPlayerTime(from)} – ${formatPlayerTime(to)}")
                if (slices == null) Text(t("This camera has missing or overlapping footage in the selected interval. Adjust the range.",
                    "所选时间内该摄像头有缺失或重叠画面，请调整范围。"), color = MaterialTheme.colorScheme.error)
                else if (!withinLimits) Text(t("Choose a shorter interval (up to 2 hours and 200 segments).", "请选择更短范围（最多 2 小时及 200 个分段）。"), color = MaterialTheme.colorScheme.error)
                else Text(t("{0} consecutive segments · {1} output", "{0} 个连续分段 · 输出 {1}", slices.size, formatPlayerTime(to - from)), style = MaterialTheme.typography.bodySmall)
                MediaExportPlanner.supportedTargets(channelSegments).forEach { target ->
                    Row {
                        Checkbox(target in selected, { checked -> selected = if (checked) selected + target else selected - target })
                        Text(exportTargetLabel(target, channelSegments.firstOrNull()?.sourceRole ?: IndexedSourceRole.UNKNOWN), Modifier.padding(top = 12.dp))
                    }
                }
                Text(t("The MP4 contains video only. Drive coordinates are not included.", "MP4 仅包含视频，不包含行驶坐标。"), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = {
        Button(enabled = timeline != null && withinLimits && selected.isNotEmpty(), onClick = {
            runCatching { MediaExportQueue.enqueueSession(context, segments, requireNotNull(timeline), channel, selected, from, to, origin) }
                .onSuccess { onDismiss() }.onFailure { error = it.message ?: t("Cannot export this range", "无法导出此范围") }
        }) { Text(t("Start export", "开始导出")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "取消")) } })
}
