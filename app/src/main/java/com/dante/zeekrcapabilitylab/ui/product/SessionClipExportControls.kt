package com.dante.zeekrcapabilitylab.ui.product

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.player.SessionClipExporter
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.util.Utils
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import java.io.File
import kotlinx.coroutines.*

@Composable
internal fun SessionClipExportControls(
    timeline: SessionPlaybackTimeline, filesById: Map<String, File>, channelId: String, positionMs: Long,
    modifier: Modifier = Modifier, onRangeChanged: (Long, Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fromMs by rememberSaveable(timeline.sessionId) { mutableLongStateOf(0) }
    var toMs by rememberSaveable(timeline.sessionId) { mutableLongStateOf(minOf(timeline.durationMs, 30_000)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSession by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingChannel by rememberSaveable { mutableStateOf(channelId) }
    val latestRangeChanged by rememberUpdatedState(onRangeChanged)
    LaunchedEffect(fromMs, toMs, timeline.sessionId) { latestRangeChanged(fromMs, toMs) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val selection = pendingFrom?.let { start -> pendingTo?.let { end -> start to end } }
        val requestSession = pendingSession
        pendingFrom = null; pendingTo = null; pendingSession = null
        if (uri == null) { busy = false; return@rememberLauncherForActivityResult }
        if (selection == null || requestSession != timeline.sessionId) {
            busy = false
            scope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
            message = Utils.t("Export selection was closed. Choose a range and try again.", "导出选择已关闭，请重新选择范围。")
            return@rememberLauncherForActivityResult
        }
        val exportChannel = pendingChannel
        job = scope.launch {
            var success = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val operation = currentCoroutineContext()
                    check(!CameraRecordingService.isRunning()) { "CLIP_RECORDING_ACTIVE" }
                    val clip = SessionClipExporter.export(context, timeline, filesById, exportChannel,
                        selection.first, selection.second, operation::ensureActive) { value ->
                        scope.launch { progress = maxOf(progress, value * 0.9f) }
                    }
                    try {
                        context.contentResolver.openOutputStream(uri, "w").use { out ->
                            checkNotNull(out) { "CLIP_SAVE_FAILED" }
                            clip.file.inputStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    operation.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                }
                            }
                            out.flush()
                        }
                        clip
                    } finally { clip.file.delete() }
                }
                success = true; progress = 1f
                message = if (result.extraLeadMs > 0) Utils.t("Saved. The clip begins {0} ms earlier at a video keyframe.",
                    "已保存。视频从前一个关键帧开始，比所选时间早 {0} 毫秒。", result.extraLeadMs)
                else Utils.t("Clip saved. Route and HUD data are not included.", "片段已保存，不包含路线和 HUD 数据。")
            } catch (_: CancellationException) {
                message = Utils.t("Export cancelled", "已取消导出")
            } catch (error: Exception) {
                val code = error.message?.takeIf { it.matches(Regex("CLIP_[A-Z_]+")) } ?: "CLIP_EXPORT_FAILED"
                message = Utils.t("Could not export this range ({0}). Choose a shorter, continuous range from one camera, with recording stopped.",
                    "无法导出这个范围（{0}）。请停止录像，选择同一摄像头中较短且连续的片段。", code)
            } finally {
                if (!success) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                busy = false; job = null
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Utils.t("Export a clip", "导出片段"), style = MaterialTheme.typography.titleSmall)
        Text("${clipTime(fromMs)} – ${clipTime(toMs)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy, onClick = { fromMs = positionMs.coerceIn(0, timeline.durationMs); if (toMs <= fromMs) toMs = minOf(timeline.durationMs, fromMs + 30_000) }) {
                Text(Utils.t("Start here", "从此处开始"))
            }
            TextButton(enabled = !busy, onClick = { toMs = positionMs.coerceIn(0, timeline.durationMs); if (fromMs >= toMs) fromMs = maxOf(0, toMs - 30_000) }) {
                Text(Utils.t("End here", "到此处结束"))
            }
        }
        Text(Utils.t("Selected camera · up to 20 min · begins at an earlier keyframe if needed. Original video layout; no route or HUD burned in.",
            "所选摄像头 · 最长 20 分钟 · 必要时从前一个关键帧开始。保留原始视频布局，不烧录路线或 HUD。"), style = MaterialTheme.typography.bodySmall)
        val eligible = toMs - fromMs in 1..SessionClipExporter.MAX_CLIP_MS && timeline.range(channelId, fromMs, toMs) != null
        OutlinedButton(enabled = !busy && pendingSession == null && eligible && !CameraRecordingService.isRunning(), onClick = {
            pendingFrom = fromMs; pendingTo = toMs; pendingSession = timeline.sessionId
            pendingChannel = channelId; busy = true; message = null; progress = 0f
            save.launch("OpenAVM-clip.mp4")
        }) { Text(Utils.t("Save selected range…", "保存所选范围…")) }
        if (!eligible) Text(Utils.t("This range contains a gap, overlap, or exceeds 20 min.", "所选范围存在缺口、重叠，或超过 20 分钟。"), style = MaterialTheme.typography.bodySmall)
        if (busy) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = job != null, onClick = { job?.cancel() }) { Text(Utils.t("Cancel export", "取消导出")) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun clipTime(ms: Long): String = "%02d:%02d".format(ms / 60_000, ms / 1_000 % 60)
