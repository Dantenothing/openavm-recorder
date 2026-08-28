package com.dante.zeekrbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedRecordingMode
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.core.MediaExportJob
import com.dante.zeekrbridge.core.MediaExportPlanner
import com.dante.zeekrbridge.core.MediaExportQueue
import com.dante.zeekrbridge.core.MediaExportState
import com.dante.zeekrbridge.core.MediaExportTarget
import java.io.File

@Composable
internal fun MediaExportDialog(
    segments: List<IndexedMediaSegment>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val source = segments.firstOrNull()?.sourceRole ?: IndexedSourceRole.UNKNOWN
    val totalMs = MediaExportPlanner.totalDurationMs(segments)
    val totalSeconds = (totalMs / 1_000f).coerceAtLeast(1f)
    val targets = MediaExportPlanner.supportedTargets(source)
    var selected by remember(segments) { mutableStateOf(setOf(MediaExportTarget.ORIGINAL)) }
    var trimRange by remember(segments) { mutableStateOf(0f..totalSeconds) }
    var error by remember { mutableStateOf<String?>(null) }
    val gapSummary = remember(segments) { MediaExportPlanner.gapSummary(segments) }
    val first = segments.firstOrNull()
    val isTimeLapse = first?.recordingMode == IndexedRecordingMode.TIME_LAPSE
    val realDurationMs = segments.sumOf { segment ->
        segment.realDurationMs ?: segment.durationMs * segment.timeLapseMultiplier.coerceAtLeast(1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Export recording clip", "导出录像片段")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    t(
                        "Available video ${formatExportDuration(totalMs)} · ${segments.size} physical segments",
                        "可用视频 ${formatExportDuration(totalMs)} · ${segments.size} 个分段文件",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isTimeLapse) {
                    Text(
                        t(
                            "Time-lapse ${first?.timeLapseMultiplier ?: 1}× · captured ${formatExportDuration(realDurationMs)}. The range below uses finished-video time.",
                            "延时摄影 ${first?.timeLapseMultiplier ?: 1}× · 现实拍摄 ${formatExportDuration(realDurationMs)}。下方裁切范围使用成片时间。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (gapSummary.first > 0) {
                    Text(
                        t(
                            "${gapSummary.first} interruption gaps (${formatExportDuration(gapSummary.second)}) will be omitted, not filled with black video.",
                            "检测到 ${gapSummary.first} 处中断（${formatExportDuration(gapSummary.second)}）；导出会跳过缺失部分，不会填充黑色视频。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(t("Output", "输出内容"), fontWeight = FontWeight.SemiBold)
                targets.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (target in selected) selected - target else selected + target
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = target in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + target else selected - target
                            },
                        )
                        Column {
                            Text(exportTargetLabel(target, source))
                            if (target != MediaExportTarget.ORIGINAL) {
                                Text(
                                    t("1280×1280 source fisheye crop", "1280×1280 原始鱼眼裁切"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    if (isTimeLapse) t("Finished-video range", "成片时间范围") else t("Time range", "时间范围"),
                    fontWeight = FontWeight.SemiBold,
                )
                RangeSlider(
                    value = trimRange,
                    onValueChange = { trimRange = it },
                    valueRange = 0f..totalSeconds,
                )
                Text(
                    "${formatExportDuration((trimRange.start * 1_000).toLong())} – " +
                        formatExportDuration((trimRange.endInclusive * 1_000).toLong()),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (source == IndexedSourceRole.SURROUND && selected.any { it != MediaExportTarget.ORIGINAL }) {
                    Text(
                        t(
                            "Direction outputs require re-encoding. They run one at a time to limit heat and memory use.",
                            "方向视频需要重新编码，将逐个处理以控制发热和内存占用。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = selected.isNotEmpty() && trimRange.endInclusive > trimRange.start,
                onClick = {
                    runCatching {
                        val rawStart = (trimRange.start * 1_000).toLong().coerceAtMost(totalMs)
                        val rawEnd = (trimRange.endInclusive * 1_000).toLong().coerceAtMost(totalMs)
                        val snappedStart = if (rawStart < 1_000L) 0L else rawStart
                        val snappedEnd = if (totalMs - rawEnd < 1_000L) totalMs else rawEnd
                        MediaExportQueue.enqueue(
                            context = context,
                            segments = segments,
                            targets = selected,
                            trimStartMs = snappedStart,
                            trimEndMs = snappedEnd,
                        )
                    }.onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: t("Cannot start export", "无法开始导出") }
                },
            ) { Text(t("Start export", "开始导出")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "取消")) } },
    )
}

@Composable
internal fun MediaExportQueueCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val jobs by MediaExportQueue.jobs.collectAsState()
    if (jobs.isEmpty()) return
    Card(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Export tasks", "导出任务"), fontWeight = FontWeight.SemiBold)
            jobs.takeLast(6).forEach { job ->
                Column(Modifier.fillMaxWidth()) {
                    Text(job.outputName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        exportStateLabel(job),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (job.state == MediaExportState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.state in setOf(MediaExportState.RUNNING, MediaExportState.CANCELLING)) {
                        LinearProgressIndicator(
                            progress = { job.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (job.state) {
                            MediaExportState.QUEUED, MediaExportState.RUNNING ->
                                TextButton(onClick = { MediaExportQueue.requestCancel(context, job.id) }) {
                                    Text(t("Cancel", "取消"))
                                }
                            MediaExportState.COMPLETED -> {
                                TextButton(onClick = { openExport(context, job) }) { Text(t("Play", "播放")) }
                                TextButton(onClick = { shareExport(context, job) }) { Text(t("Share", "分享")) }
                                TextButton(onClick = { MediaExportQueue.dismiss(job.id) }) { Text(t("Dismiss", "隐藏")) }
                            }
                            MediaExportState.FAILED, MediaExportState.CANCELLED ->
                                Row {
                                    TextButton(onClick = { MediaExportQueue.retry(context, job.id) }) {
                                        Text(t("Retry", "重试"))
                                    }
                                    TextButton(onClick = { MediaExportQueue.dismiss(job.id) }) { Text(t("Dismiss", "隐藏")) }
                                }
                            MediaExportState.CANCELLING -> Unit
                        }
                    }
                }
            }
        }
    }
}

private fun exportTargetLabel(target: MediaExportTarget, source: IndexedSourceRole): String = when (target) {
    MediaExportTarget.ORIGINAL -> when (source) {
        IndexedSourceRole.SURROUND -> t("Original 360° composite", "原始360°合成视频")
        IndexedSourceRole.CABIN -> t("Cabin video", "Cabin车内视频")
        IndexedSourceRole.IR -> t("IR video", "IR红外视频")
        else -> t("Original video", "原始视频")
    }
    MediaExportTarget.FRONT -> t("Front", "前")
    MediaExportTarget.REAR -> t("Rear", "后")
    MediaExportTarget.LEFT -> t("Left", "左")
    MediaExportTarget.RIGHT -> t("Right", "右")
}

private fun exportStateLabel(job: MediaExportJob): String = when (job.state) {
    MediaExportState.QUEUED -> t("Queued", "等待导出")
    MediaExportState.RUNNING -> t("Exporting ${job.progressPercent}%", "正在导出 ${job.progressPercent}%")
    MediaExportState.CANCELLING -> t("Cancelling…", "正在取消…")
    MediaExportState.COMPLETED -> if (job.outputUri != null) {
        t("Saved to Movies/OpenAVM", "已保存到 Movies/OpenAVM")
    } else {
        t("Saved inside OpenAVM", "已保存到 OpenAVM 应用内")
    }
    MediaExportState.FAILED -> t("Failed: ${job.message.orEmpty()}", "导出失败：${job.message.orEmpty()}")
    MediaExportState.CANCELLED -> t("Cancelled", "已取消")
}

private fun outputUri(context: Context, job: MediaExportJob): Uri? = when {
    !job.outputUri.isNullOrBlank() -> Uri.parse(job.outputUri)
    !job.outputPath.isNullOrBlank() -> FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(job.outputPath),
    )
    else -> null
}

private fun openExport(context: Context, job: MediaExportJob) {
    val uri = outputUri(context, job) ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

private fun shareExport(context: Context, job: MediaExportJob) {
    val uri = outputUri(context, job) ?: return
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                t("Share recording", "分享录像"),
            ),
        )
    }
}

private fun formatExportDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
