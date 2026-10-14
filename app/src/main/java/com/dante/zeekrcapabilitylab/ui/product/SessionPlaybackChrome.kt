package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.player.*
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.telemetry.*
import com.dante.zeekrcapabilitylab.transfer.*
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.usbexport.*
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

internal fun sessionBytes(bytes: Long): String = if (bytes >= 1_073_741_824) "%.2f GB".format(Locale.ROOT, bytes / 1_073_741_824.0)
    else "%.1f MB".format(Locale.ROOT, bytes / 1_048_576.0)

// Waiting rows suspend without occupying IO threads. Cancellation removes stale scroll/seek
// requests before they enter the cache's process-wide native extraction lock.
private val sessionThumbnailQueue = Mutex()

@Composable
internal fun SessionClipRail(data: SessionReviewData, clips: List<SessionVideoSegment>, selected: Set<String>, position: Long,
    onToggle: (String) -> Unit, onAll: () -> Unit, onClear: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier,
    allowThumbnailExtraction: Boolean) {
    val context = LocalContext.current
    val cache = remember { RecordingThumbnailCache(File(context.cacheDir, "recording-covers")) }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.White) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Utils.t("Recording clips ({0})", "录像分段（{0}）", clips.size), style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = onAll, enabled = selected.size < clips.size) { Text(Utils.t("Select all", "全选")) }
                TextButton(onClick = onClear, enabled = selected.isNotEmpty()) { Text(Utils.t("Clear", "清除")) }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clips, key = { it.id }) { clip ->
                    val entry = data.entries.getValue(clip.id)
                    val start = clip.mediaStartElapsedMs!! - data.timeline.originElapsedMs
                    val active = position >= start && position < clip.mediaEndElapsedMs!! - data.timeline.originElapsedMs
                    val thumbnail by produceState<Bitmap?>(null, clip.id, allowThumbnailExtraction) {
                        if (value == null) value = withContext(Dispatchers.IO) { cache.loadCached(entry.file, entry.sidecar.layoutKind) }
                        if (value == null && allowThumbnailExtraction) sessionThumbnailQueue.withLock {
                            currentCoroutineContext().ensureActive()
                            value = withContext(Dispatchers.IO) { cache.loadOrCreate(entry.file, entry.sidecar.layoutKind) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF172235))
                        .border(if (active) 3.dp else 1.dp, if (active) Color(0xFF0866F5) else Color(0xFFE0E5ED), RoundedCornerShape(10.dp))) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { onSeek(start) }
                            .semantics { contentDescription = "Play clip " + clip.id.substringAfterLast('/') }) {
                            if (thumbnail != null) Image(thumbnail!!.asImageBitmap(), Utils.t("Recording thumbnail", "录像缩略图"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Text(Utils.t("Recording clip", "录像片段"), Modifier.align(Alignment.Center), color = Color.LightGray)
                            Checkbox(clip.id in selected, onCheckedChange = { onToggle(clip.id) }, modifier = Modifier.align(Alignment.TopStart)
                                .semantics { contentDescription = "Select clip " + clip.id.substringAfterLast('/') })
                        }
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.sidecar.startedAtEpochMs ?: 0L)),
                                Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Text(sessionTime(clip.mediaDurationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SessionTimeline(data: SessionReviewData, position: Long, onDrag: (Long) -> Unit, onDragEnd: () -> Unit, onSeek: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sessionTime(position), style = MaterialTheme.typography.labelMedium)
        BoxWithConstraints(Modifier.weight(1f)) {
            Slider(value = position.toFloat(), onValueChange = { onDrag(it.toLong()) }, onValueChangeFinished = onDragEnd,
                valueRange = 0f..data.timeline.durationMs.toFloat(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Session timeline" })
            data.events.filter { it.kind in setOf(io.github.dantenothing.drivemap.DriveEventKind.MANUAL, io.github.dantenothing.drivemap.DriveEventKind.EMERGENCY) }
                .take(100).forEach { event ->
                    val x = (maxWidth - 40.dp) * (event.sessionMs.toFloat() / data.timeline.durationMs).coerceIn(0f, 1f)
                    Box(Modifier.offset(x = x).size(40.dp, 18.dp).clickable { onSeek(event.sessionMs) }
                        .semantics { contentDescription = "Trigger " + sessionTime(event.sessionMs) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(4.dp).height(12.dp).background(Color(0xFFFFA000), RoundedCornerShape(2.dp)))
                    }
                }
        }
        Text(sessionTime(data.timeline.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SessionSendDialog(data: SessionReviewData, windows: List<SessionSelectionWindow>, all: Boolean,
    onSend: suspend (List<File>, DriveDataSelection) -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels = remember(data, windows) { data.timeline.channelIds.filter {
        SessionSelection.files(data.timeline, windows, setOf(it)).isNotEmpty()
    } }
    var chosen by remember { mutableStateOf(setOf("SURROUND").intersect(channels.toSet())) }
    val permitted = remember(windows) { windows.map { DriveDataWindow(data.timeline.originElapsedMs + it.fromMs, data.timeline.originElapsedMs + it.toMs) } }
    val gpsAvailable = remember(windows) { data.gps.any { fix -> permitted.any { it.contains(fix.elapsedMs) } } }
    val vehicleAvailable = remember(windows) { data.vehicle.any { sample -> sample.values.isNotEmpty() && permitted.any { it.contains(sample.elapsedMs) } } }
    var gps by remember { mutableStateOf(gpsAvailable) }
    var vehicle by remember { mutableStateOf(vehicleAvailable) }
    val requestId = remember { UUID.randomUUID().toString() }
    val selection = DriveDataSelection(gps, vehicle, permitted, requestId)
    val ids = remember(chosen, windows) { SessionSelection.files(data.timeline, windows, chosen) }
    val files = remember(ids) { ids.map { data.entries.getValue(it).file } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val estimate by produceState<Long?>(null, ids, selection) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching {
            val gpsTrack = if (gps) RecordingTelemetry.store(context).load(data.timeline.sessionId) else null
            val vehicleTrack = if (vehicle) RecordingVehicleTelemetry.store(context).load(data.timeline.sessionId) else null
            ids.sumOf { id ->
                val entry = data.entries.getValue(id)
                val sidecar = SegmentSidecarIO.json.encodeToString(entry.sidecar)
                val attachmentBytes = if (selection.enabled)
                    DriveDataCodec.encode(DriveAttachmentSnapshot.build(sidecar, gpsTrack, vehicleTrack, selection)).size.toLong() else 0L
                entry.file.length() + sidecar.toByteArray(Charsets.UTF_8).size + attachmentBytes
            }
        }.getOrNull() }
    }
    val endpoint = PhoneConnectionStore.saved()
    val overlap = ids.any { id -> val segment = data.entries.getValue(id).segment
        windows.none { segment.mediaStartElapsedMs!! >= data.timeline.originElapsedMs + it.fromMs && segment.mediaEndElapsedMs!! <= data.timeline.originElapsedMs + it.toMs } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (all) Utils.t("Send entire session", "发送整个行程") else Utils.t("Send selected clips", "发送选中片段")) },
        text = { Column(Modifier.widthIn(min = 440.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(Utils.t("Sending range", "发送范围") + ": " + windows.joinToString(" · ") { sessionTime(it.fromMs) + "–" + sessionTime(it.toMs) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { chosen = setOf("SURROUND").intersect(channels.toSet()); gps = gpsAvailable; vehicle = vehicleAvailable }) { Text("Standard") }
                OutlinedButton(enabled = !busy, onClick = { chosen = channels.toSet(); gps = gpsAvailable; vehicle = vehicleAvailable }) { Text("Complete") }
            }
            Text(Utils.t("Standard excludes cabin and infrared video.", "Standard 默认不包含舱内和红外录像。"), style = MaterialTheme.typography.bodySmall)
            SessionPlaybackTimeline.CHANNELS.forEach { channel -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(channel in chosen, { yes -> chosen = if (yes) chosen + channel else chosen - channel }, enabled = channel in channels && !busy)
                Text(sessionChannelLabel(channel) + if (channel !in channels) Utils.t(" · unavailable", " · 无录像") else "")
            } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(gps, { gps = it }, enabled = gpsAvailable && !busy); Text(Utils.t("GPS route", "GPS 路线") + if (!gpsAvailable) Utils.t(" · unavailable", " · 无记录") else "") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(vehicle, { vehicle = it }, enabled = vehicleAvailable && !busy); Text(Utils.t("Vehicle HUD data", "车辆 HUD 数据") + if (!vehicleAvailable) Utils.t(" · unavailable", " · 无记录") else "") }
            Text(Utils.t("GPS {0} · vehicle data {1}", "GPS {0} · 车辆数据 {1}", if (gps) "✓" else "—", if (vehicle) "✓" else "—"))
            if (overlap) Text(Utils.t("Original camera files overlap the selected boundary and include those extra seconds. GPS and HUD are limited to the selected range.",
                "原始摄像头分段跨过所选边界，发送时会包含边界外的秒数。GPS 和 HUD 仅包含所选范围。"), style = MaterialTheme.typography.bodySmall)
            Text(Utils.t("{0} files · estimated {1}", "{0} 个文件 · 预计 {1}", files.size, estimate?.let(::sessionBytes) ?: Utils.t("calculating…", "计算中…")))
            Text(Utils.t("Receiver: {0}", "接收设备：{0}", endpoint?.phoneName ?: Utils.t("No phone paired", "尚未配对手机")))
            Text(Utils.t("Queued transfers continue after closing playback. Progress and cancellation remain available in Transfers.", "关闭回放后队列会继续发送，可在传输页面查看进度或取消。"), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(enabled = !busy && files.isNotEmpty() && estimate != null && endpoint != null, onClick = {
            busy = true; error = null
            scope.launch { try {
                if (onSend(files, selection)) onDismiss() else error = Utils.t("Some files could not be queued. Check transfer progress before retrying.", "部分文件未能加入队列，请先检查传输进度再重试。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = Utils.t("Unable to queue this selection", "无法加入发送队列") }
            finally { busy = false } }
        }) { Text(if (busy) Utils.t("Queuing…", "正在加入…") else Utils.t("Send", "发送")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(Utils.t("Cancel", "取消")) } })
}

@Composable
internal fun SessionTransferProgress(data: SessionReviewData) {
    val allTasks by TransferRepository.tasks.collectAsState()
    val matching = allTasks.filter { it.sourceRecordingSessionId == data.timeline.sessionId }
    val latest = matching.maxByOrNull { it.createdAt } ?: return
    val requestId = latest.driveSelection?.requestId
    val tasks = matching.filter { if (requestId != null) it.driveSelection?.requestId == requestId else it.selectionId == latest.selectionId }
    val complete = tasks.count { it.state == TransferTaskState.COMPLETED }
    val active = tasks.filter { it.state !in TransferRepository.TERMINAL }
    val failed = tasks.filter { it.state == TransferTaskState.FAILED || it.state == TransferTaskState.WAITING_RETRY }
    val bytes = tasks.sumOf { it.sizeBytes }.coerceAtLeast(1)
    val sent = tasks.sumOf { if (it.state == TransferTaskState.COMPLETED) it.sizeBytes else minOf(it.sizeBytes, it.uploadedChunks.toLong() * it.chunkSize) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when {
            complete == tasks.size -> Utils.t("Transfer complete", "发送完成")
            tasks.all { it.state == TransferTaskState.CANCELLED } -> Utils.t("Transfer cancelled", "发送已取消")
            failed.isNotEmpty() -> Utils.t("Transfer needs attention", "传输需要处理")
            else -> Utils.t("Sending {0}/{1}", "正在发送 {0}/{1}", complete, tasks.size)
        }, style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { (sent.toDouble() / bytes).toFloat() }, Modifier.weight(1f))
        if (failed.isNotEmpty()) TextButton(onClick = { failed.forEach { TransferRepository.retry(it.id) } }) { Text(Utils.t("Retry", "重试")) }
        if (active.isNotEmpty()) TextButton(onClick = { active.forEach { TransferRepository.cancel(it.id) } }) { Text(Utils.t("Cancel transfer", "取消发送")) }
    }
}

@Composable
internal fun SessionMoreActions(data: SessionReviewData, showDetails: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usbDialog by remember { mutableStateOf(false) }
    var usbTargets by remember { mutableStateOf<List<UsbExportTarget>>(emptyList()) }
    var usbBusy by remember { mutableStateOf(false) }
    var usbMessage by remember { mutableStateOf<String?>(null) }
    val internalFiles = remember(data) { data.entries.values.map { it.file }.filter(RecorderLibrary::isManaged) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("···") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(Utils.t("Export original files to USB", "将原始文件导出到 USB")) }, enabled = internalFiles.isNotEmpty(), onClick = {
                expanded = false; usbDialog = true; usbBusy = true; usbMessage = null
                scope.launch { usbTargets = withContext(Dispatchers.IO) { UsbExportVolumeResolver.mountedTargets(context) }; usbBusy = false }
            })
            DropdownMenuItem(text = { Text(Utils.t("Copy session information", "复制行程信息")) }, onClick = {
                val text = "OpenAVM session ${data.timeline.sessionId}\nDuration ${sessionTime(data.timeline.durationMs)}\n" +
                    data.timeline.channelIds.joinToString("\n") { channel -> "$channel: ${data.entries.values.count { it.segment.channelId == channel }} files" }
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("OpenAVM session", text))
                expanded = false; copied = true
            })
            DropdownMenuItem(text = { Text(Utils.t("Details & diagnostics", "详情与诊断")) }, onClick = { expanded = false; showDetails() })
        }
    }
    if (copied) Text(Utils.t("Copied", "已复制"), style = MaterialTheme.typography.labelSmall)
    if (usbDialog) AlertDialog(onDismissRequest = { if (!usbBusy) usbDialog = false },
        title = { Text(Utils.t("Export originals to USB", "导出原始文件到 USB")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Utils.t("{0} files · {1}. Includes all recorded cameras stored on the head unit in this session. Files already on USB stay there.",
                "{0} 个文件 · {1}。包含本行程保存在车机上的所有摄像头；已在 USB 上的文件保持原位。", internalFiles.size, sessionBytes(internalFiles.sumOf { it.length() })))
            Text(Utils.t("Original video and timing metadata; GPS and HUD are not included. Progress and cancellation are in the USB export queue.",
                "导出原始视频和时间信息，不包含 GPS 或 HUD。可在 USB 导出队列查看进度或取消。"), style = MaterialTheme.typography.bodySmall)
            if (usbBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (usbTargets.isEmpty() && usbMessage == null) Text(Utils.t("Connect a writable USB drive, then reopen this menu.", "请连接可写的 USB 设备，再重新打开此菜单。"))
            usbTargets.forEach { target -> OutlinedButton(enabled = !usbBusy, onClick = {
                usbBusy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { UsbExportRepository.enqueue(data.timeline.sessionId, "NORMAL",
                        data.entries.values.minOf { it.sidecar.startedAtEpochMs ?: 0L }, data.entries.values.maxOf { it.sidecar.stoppedAtEpochMs ?: 0L },
                        internalFiles, target) }
                    usbBusy = false
                    usbMessage = if (result.isSuccess) Utils.t("Queued. Export continues after playback closes.", "已加入队列，关闭回放后继续导出。")
                        else Utils.t("Could not queue export. Check the USB export queue before retrying.", "无法加入导出队列，重试前请检查 USB 导出队列。")
                    if (result.isSuccess) usbTargets = emptyList()
                }
            }) { Text(target.description) } }
            usbMessage?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = !usbBusy, onClick = { usbDialog = false }) { Text(Utils.t("Close", "关闭")) } })
}

@Composable
internal fun SessionSaveRangeDialog(data: SessionReviewData, initialChannel: String, position: Long, onDismiss: () -> Unit) {
    var channel by remember { mutableStateOf(initialChannel) }
    var from by remember { mutableLongStateOf(0L) }
    var to by remember { mutableLongStateOf(minOf(data.timeline.durationMs, 30_000L)) }
    val range = data.timeline.originElapsedMs + from until data.timeline.originElapsedMs + to
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.55f).heightIn(max = 760.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(Utils.t("Save range", "保存范围"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { data.timeline.channelIds.forEach { id ->
                    FilterChip(channel == id, { channel = id }, label = { Text(sessionChannelLabel(id)) })
                } }
                SessionClipExportControls(data.timeline, data.entries.mapValues { it.value.file }, channel, position,
                    onRangeChanged = { a, b -> from = a; to = b },
                    gpsAvailable = data.gps.any { it.elapsedMs in range },
                    vehicleAvailable = data.vehicle.any { it.elapsedMs in range && it.values.values.any { field -> field.readElapsedMs in range } })
            }
        }
    }
}
