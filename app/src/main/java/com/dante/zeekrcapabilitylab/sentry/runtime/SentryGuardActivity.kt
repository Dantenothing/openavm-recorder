package com.dante.zeekrcapabilitylab.sentry.runtime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.CaptureMode
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.ui.product.FourLanePlayerDialog
import com.dante.zeekrcapabilitylab.ui.product.SentryDurationSelector
import com.dante.zeekrcapabilitylab.ui.product.SentryInfoButton
import com.dante.zeekrcapabilitylab.ui.product.sentryDurationLabel
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SentryGuardActivity : ComponentActivity() {
    private var selectedDuration by mutableStateOf(GuardRunDuration.HOURS_12)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SentryGuardService.start(this, duration = selectedDuration) else toast("需要相机权限")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SENTRY_INTEGRATED_ENABLED) { finish(); return }
        selectedDuration = GuardSettings(this).runDuration
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { GuardScreen() } } }
    }
    override fun onResume() { super.onResume(); GuardPreview.visible = BuildConfig.SENTRY_CAPTURE_ENABLED }
    override fun onPause() { GuardPreview.visible = false; super.onPause() }
    private fun startRun() {
        if (!BuildConfig.SENTRY_CAPTURE_ENABLED) { toast("本版本暂时停用哨兵，已有视频与诊断仍可查看"); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.launch(Manifest.permission.CAMERA)
        else SentryGuardService.start(this, duration = selectedDuration)
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    @Composable private fun GuardScreen() {
        val state by SentryGuardService.state.collectAsState()
        val previews by GuardPreview.lanes.collectAsState()
        val layoutKey by GuardPreview.layoutKey.collectAsState()
        val usbTasks by UsbExportRepository.tasks.collectAsState()
        val usbStatus by GuardUsbAutoSave.status.collectAsState()
        LaunchedEffect(Unit) { GuardUsbAutoSave.schedule(this@SentryGuardActivity) }
        val settings = remember { GuardSettings(this) }
        val store = remember { GuardEventStore(this) }
        val scope = rememberCoroutineScope()
        var ai by remember { mutableStateOf(settings.aiEnabled) }
        var near by remember { mutableStateOf(settings.near) }
        var critical by remember { mutableStateOf(settings.critical) }
        var events by remember { mutableStateOf(emptyList<GuardEvent>()) }
        var refresh by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<GuardEvent?>(null) }
        var deleting by remember { mutableStateOf<GuardEvent?>(null) }
        var usbSelection by remember { mutableStateOf<Pair<GuardEvent, List<UsbExportTarget>>?>(null) }
        var report by remember { mutableStateOf<GuardCopyReport?>(null) }
        var copied by remember { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SentryGuardService.restore(this@SentryGuardActivity) } }
        LaunchedEffect(state.eventsRevision, refresh) { events = withContext(Dispatchers.IO) { store.list() } }
        fun send(event: GuardEvent, files: List<File> = store.files(event)) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { TransferRepository.enqueueSelection(event.id, files) }
                toast(if (result.isSuccess) "已加入手机发送队列；连接已配对的手机后发送" else "发送失败：${result.exceptionOrNull()?.message}")
            }
        }
        fun export(event: GuardEvent, target: UsbExportTarget) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { UsbExportRepository.enqueue(event.id, "SENTRY",
                    event.createdAtEpochMs - event.preAchievedUs / 1000, event.createdAtEpochMs + event.postAchievedUs / 1000,
                    store.files(event), target) }
                busy = false
                toast(if (result.isSuccess) "已加入 USB 校验导出队列" else "USB 导出失败：${result.exceptionOrNull()?.message}")
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("OpenAVM 哨兵", style = MaterialTheme.typography.headlineMedium); Text("一体化内测 · ${BuildConfig.VERSION_NAME}") }
                SentryInfoButton()
                OutlinedButton(onClick = { finish() }) { Text("返回主界面") }
            }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (BuildConfig.SENTRY_CAPTURE_ENABLED) {
                    Text(phaseText(state), style = MaterialTheme.typography.titleLarge)
                    Text(state.message)
                } else {
                    Text("哨兵暂时停用", style = MaterialTheme.typography.titleLarge)
                    Text("离车后的持续运行仍不可靠，本版本暂停哨兵及自动离车 / 回车切换。普通录像、延时摄影可继续使用。")
                    if (state.runId.isNotEmpty()) Text("上次运行记录：" + phaseText(state), style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.firstFailure?.let { failure ->
                    val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(failure.atEpochMs))
                    Text("首次异常 $at · ${failure.phase} · 当时应用${if (failure.appForeground) "在前台" else "在后台"}",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.runSource?.let { source ->
                    Text("本次环视：相机 ${source.cameraId} · ${source.profile.size.width}×${source.profile.size.height} · 配置沿用至本次结束",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (BuildConfig.SENTRY_CAPTURE_ENABLED) {
                    Text("哨兵模式：离车保留 RAM 预录")
                    if (!state.running) {
                        SentryDurationSelector(selectedDuration, !SentryGuardService.isRunning()) {
                            selectedDuration = it; settings.runDuration = it
                        }
                    } else {
                        Text("本次时长：${sentryDurationLabel(state.runDuration)}" +
                            if (state.runtime.runExpiresAtEpochMs > 0) " · 结束 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.runtime.runExpiresAtEpochMs))}" else "")
                    }
                    Text("运行方式：${if (state.manualMode != null) "车内哨兵测试；离开应用后自动结束测试" else "自动离车 / 回车切换已开启"}")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = ::startRun, enabled = !state.running && !SentryGuardService.isRunning()) { Text("开始本次运行") }
                        Button(onClick = { SentryGuardService.stop() }, enabled = state.running) { Text("停止本次运行") }
                        OutlinedButton(onClick = { SentryGuardService.parkNow() }, enabled = state.running) { Text("车内测试：进入哨兵") }
                        OutlinedButton(onClick = { SentryGuardService.setMode(null) }, enabled = state.running && state.manualMode != null) { Text("完成测试，恢复自动") }
                    }
                    Text("自动切换：应用在后台且交互/主屏均关闭，连续确认约 1 秒后开始切入哨兵；交互和主屏恢复持续 5 秒后回车录像，无需打开本应用。")
                    Text("停止后不会自己重启。不限时仍受温度、存储和车辆电源等条件限制；详见顶部说明。")
                    if (state.running) Text("运行心跳 ${state.runtime.heartbeat} · CPU 锁 ${if (state.runtime.wakeLockHeld) "持有" else "未持有"} · 相机资源 ${if (state.runtime.resourcesReleased) "已释放" else "使用中 / 释放中"}")
                    state.capture?.let { capture ->
                        LinearProgressIndicator(progress = { (capture.completedHistorySeconds / 180).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("预录 %.1f / 180 秒 · RAM %.1f / 768 MiB · %.1f fps".format(capture.completedHistorySeconds, capture.encodedLiveBytes / 1048576.0, capture.actualFps))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { SentryGuardService.trigger() }, enabled = state.mode == "SENTRY" && capture.clipReady && state.running) {
                                Text(if (state.eventId != null) "再次触发 / 延长事件" else "手动保存事件")
                            }
                            Text(if (capture.ramReady) "180 秒预录已就绪" else "预录仍在积累；提前保存会标注为部分事件")
                        }
                    }
                }
            } }
            if (BuildConfig.SENTRY_CAPTURE_ENABLED) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("本地 AI 触发试用", style = MaterialTheme.typography.titleLarge)
                    Switch(checked = ai, onCheckedChange = { ai = it; settings.aiEnabled = it }, enabled = !state.running)
                    Text("开始前设置；模型在车机本地运行")
                }
                Text(state.ai.message)
                if (state.running && state.mode == "SENTRY") Text(
                    if (state.ai.status == "TRIAL_ACTIVE" && state.ai.clockCalibrated && state.ai.vehicleCalibrated)
                        "AI 自动触发：已就绪（试用）" else "AI 自动触发：未就绪 · ${state.ai.message}",
                    color = if (state.ai.status == "TRIAL_ACTIVE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("已分析 ${state.ai.frames} 帧 · 每轮 ${state.ai.inferenceMs} ms · 最高置信度 %.2f · 自动触发 ${state.ai.triggers} 次".format(state.ai.maxConfidence))
                if (ai) {
                    Text("首次使用：进入哨兵，核对四个视角的方向；黄色线以下为近处区域，红色线以下为重点区域。确认前只观察，不自动保存。")
                    if (previews.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previews.forEach { lane -> Column(Modifier.weight(1f)) {
                            Text(lane.label)
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                Image(lane.bitmap.asImageBitmap(), lane.label, Modifier.fillMaxSize())
                                Canvas(Modifier.fillMaxSize()) {
                                    val b = lane.letterbox
                                    fun x(value: Double) = ((b.left + value * b.width) / 416 * size.width).toFloat()
                                    fun y(value: Double) = ((b.top + value * b.height) / 416 * size.height).toFloat()
                                    drawLine(Color.Yellow, Offset(x(0.0), y(near.toDouble())), Offset(x(1.0), y(near.toDouble())), 3f)
                                    drawLine(Color.Red, Offset(x(0.0), y(critical.toDouble())), Offset(x(1.0), y(critical.toDouble())), 3f)
                                    lane.detections.forEach { detection ->
                                        val box = detection.box
                                        drawRect(Color.Green, Offset(x(box.left), y(box.top)), Size(x(box.right) - x(box.left), y(box.bottom) - y(box.top)), style = Stroke(2f))
                                    }
                                }
                            }
                        } }
                    }
                    Text("近处区域起点：${(near * 100).toInt()}%")
                    Slider(value = near, onValueChange = { near = it; critical = maxOf(critical, it + 0.05f) },
                        onValueChangeFinished = { settings.near = near; settings.critical = critical }, valueRange = 0.1f..0.8f)
                    Text("重点区域起点：${(critical * 100).toInt()}%")
                    Slider(value = critical, onValueChange = { critical = it }, onValueChangeFinished = { settings.critical = critical }, valueRange = (near + 0.05f)..0.95f)
                    OutlinedButton(onClick = { settings.confirmedLayout = layoutKey; toast("已确认当前方向和区域，AI 试用触发将在时钟匹配后生效") },
                        enabled = state.running && state.mode == "SENTRY" && layoutKey.endsWith("/$near/$critical") && previews.isNotEmpty()) { Text("确认当前四路方向和警戒区域") }
                    Text("识别精度仍需实车验收。画面方向不对时，先停止运行，到设置修改视角旋转后重新开始。")
                }
            } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) { runCatching { GuardDiagnostics.prepare(this@SentryGuardActivity) } }
                    busy = false; report = result.getOrNull(); copied = emptySet()
                    result.exceptionOrNull()?.let { toast("生成 JSON 失败：${it.message}") }
                } }, enabled = !busy) { Text("复制最新诊断 JSON") }
                OutlinedButton(onClick = { scope.launch {
                    report = withContext(Dispatchers.IO) { runCatching { GuardDiagnostics.reopen(this@SentryGuardActivity) }.getOrNull() }
                    if (report == null) toast("尚未生成过诊断 JSON")
                } }, enabled = !busy) { Text("继续复制上次 JSON") }
                OutlinedButton(onClick = { refresh++ }) { Text("刷新事件列表") }
            }
            Text("诊断 JSON 包含最近保留的普通录像、延时收尾、预览和 USB 操作记录，可一键复制完整内容；较大报告使用分段复制。")
            Text("OpenAVM 哨兵视频（${events.size}）", style = MaterialTheme.typography.titleLarge)
            Text("事件完成后自动保存到可写 USB，并校验完整性；USB 不可用时保留本机，接回后重试。本机原件最多占用 10 GiB，可在确认 USB 保存后删除。")
            if (events.isEmpty()) Text("暂无事件。普通录像仍在主界面的录像列表中。")
            events.forEach { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("OpenAVM 哨兵视频 · ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.createdAtEpochMs))}")
                Text("${eventLabel(event.state)} · ${event.assets.size} 段 · ${event.triggers.size} 个触发点")
                Text("预录 %.1f 秒 / 后录 %.1f 秒 · %s".format(event.preAchievedUs / 1e6, event.postAchievedUs / 1e6,
                    if (event.triggers.any { it.type == "VISUAL_RISK" }) "AI 触发" else "手动触发"))
                event.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(GuardUsbSavePolicy.label(event, usbTasks, usbStatus[event.id]))
                usbTasks.filter { it.logicalId == event.id }.takeLast(8).forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("USB 第 ${task.sources.firstOrNull()?.segmentNumber ?: "?"} 段：${task.state} ${task.errorCode.orEmpty()}")
                        if (task.state == UsbExportState.FAILED_RECOVERABLE) TextButton(onClick = { UsbExportRepository.retry(task.id) }) { Text("重试") }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { playing = event }, enabled = event.assets.isNotEmpty() && event.state != "WRITING") { Text("回看") }
                    OutlinedButton(onClick = { send(event) }, enabled = event.assets.isNotEmpty() && event.state != "WRITING" && !busy) { Text("发送手机") }
                    OutlinedButton(onClick = { scope.launch {
                        val targets = withContext(Dispatchers.IO) { UsbExportVolumeResolver.mountedTargets(this@SentryGuardActivity) }
                        if (targets.isEmpty()) toast("未检测到可写 USB；本机事件已保留") else usbSelection = event to targets
                    } }, enabled = event.assets.isNotEmpty() && event.state != "WRITING" && !busy) { Text("导出 USB") }
                    OutlinedButton(onClick = { deleting = event }, enabled = event.state != "WRITING" && !busy) { Text("删除本机事件") }
                }
            } } }
            Text("${state.power}\n状态：${state.phase} · 离车判断：${state.presence}", style = MaterialTheme.typography.bodySmall)
        }
        playing?.let { event ->
            val files = remember(event) { store.files(event) }
            if (files.isEmpty()) { LaunchedEffect(event) { playing = null; toast("该事件文件已不可用") } }
            else FourLanePlayerDialog(file = files.first(), files = files,
                layoutKind = event.source.layoutKind, sourceRole = event.source.sourceRole,
                compositeWidth = event.source.profile.size.width, compositeHeight = event.source.profile.size.height,
                recordedAtEpochMs = event.createdAtEpochMs - event.preAchievedUs / 1000, displayTitle = "OpenAVM 哨兵视频",
                triggerMarkersByFile = event.assets.associate { asset ->
                    File(store.directory(event.id), asset.name).absolutePath to GuardVideoPresentation.markers(event, asset)
                },
                onSendToPhone = { send(event, it); true }, onDismiss = { playing = null })
        }
        usbSelection?.let { (event, targets) -> AlertDialog(onDismissRequest = { usbSelection = null }, title = { Text("选择 USB") },
            text = { Column { targets.forEach { target -> TextButton(onClick = { usbSelection = null; export(event, target) }) { Text("${target.description} · ${target.storageUuid}") } } } },
            confirmButton = { TextButton(onClick = { usbSelection = null }) { Text("返回") } }) }
        deleting?.let { event -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这个本机事件？") },
            text = { Text("将删除这 ${event.assets.size} 段本机录像及该事件的未完成片段。发送或播放中的文件会保留。已经导出的 USB / 手机副本不受影响。") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { runCatching { deleteEvent(store, event) } }
                busy = false; refresh++; toast(result.getOrElse { "删除失败：${it.message}" })
            } }) { Text("删除本机事件") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
        report?.let { copyReport ->
            val parts = copyReport.parts
            var showParts by remember(copyReport) { mutableStateOf(copyReport.fullText == null) }
            var copiedWhole by remember(copyReport) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { report = null }, title = { Text("诊断 JSON") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    copyReport.fullText?.let { fullText ->
                        Text("一次复制整份 JSON，粘贴进邮件正文即可。若邮件粘贴不完整，可展开下面的分段复制。")
                        Button(onClick = {
                            if (copyJson("OpenAVM 完整诊断 JSON", fullText)) {
                                copiedWhole = true
                                toast("已复制完整 JSON，粘贴一次即可")
                            } else showParts = true
                        }, modifier = Modifier.fillMaxWidth()) { Text("复制完整 JSON${if (copiedWhole) " ✓" else ""}") }
                        TextButton(onClick = { showParts = !showParts }) {
                            Text(if (showParts) "收起分段复制" else "分段复制（备用，共 ${parts.size} 段）")
                        }
                    }
                    if (showParts) {
                        Text("共 ${parts.size} 段，逐段复制后粘贴进邮件。切去邮件再回来，仍可继续复制同一份报告。")
                        parts.forEachIndexed { i, text -> Button(onClick = {
                            if (copyJson("OpenAVM JSON ${i + 1}", text)) {
                                copied = copied + i; toast("已复制第 ${i + 1} / ${parts.size} 段")
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("复制第 ${i + 1} 段${if (i in copied) " ✓" else ""}") } }
                    }
                } }, confirmButton = { TextButton(onClick = { report = null }) { Text("关闭") } })
        }
    }
    private fun copyJson(label: String, text: String): Boolean = runCatching {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }.onFailure { toast("剪贴板复制失败，请尝试分段复制或稍后重试") }.isSuccess

    private fun deleteEvent(store: GuardEventStore, event: GuardEvent): String {
        check(event.state != "WRITING" && SentryGuardService.state.value.eventId != event.id)
        val dir = store.directory(event.id)
        val failures = store.files(event).map { RecorderLibrary.deleteManagedByUser(dir, it) }.count { !it.deleted }
        if (failures > 0) return "$failures 段仍在发送、导出或播放中，已保留"
        // Exact journal-owned partial only. Unknown files prevent directory deletion and remain untouched.
        event.pending?.let { pending ->
            val final = File(dir, pending.name)
            if (final.exists()) {
                if (RecorderLibrary.isManaged(final)) {
                    if (!RecorderLibrary.deleteManagedByUser(dir, final).deleted) return "未完成事件仍有文件正在使用，已保留"
                } else check(final.delete())
            }
            File(dir, pending.name + ".partial").let { file -> if (file.exists()) check(file.delete()) }
        }
        val remaining = event.assets.filter { File(dir, it.name).exists() }
        if (remaining.isNotEmpty()) return "部分文件尚未确认归属，已保留"
        File(dir, "event.json").delete(); File(dir, "event.json.bak").delete(); dir.delete()
        return "本机事件已删除"
    }
    private fun eventLabel(state: String) = when (state) { "COMPLETE" -> "已完整保存"; "PARTIAL" -> "已保存部分录像"; "WRITING" -> "保存中 / 等待恢复"; else -> "保存失败" }
    private fun phaseText(state: GuardState) = when (state.phase) {
        "NORMAL_ACTIVE" -> "普通录像运行中"; "SENTRY_ACTIVE" -> "哨兵监听中"; "TRANSITION_TO_SENTRY" -> "正在切入哨兵"
        "AWAKE_IDLE" -> "已停录，保持运行"; "TRANSITION_TO_AWAKE_IDLE" -> "正在保存末段并关闭相机"
        "TRANSITION_TO_NORMAL" -> "正在恢复普通录像"; "STARTING_NORMAL", "STARTING" -> "正在开始"
        "STOPPING" -> "正在保存并释放资源"; "FAULT" -> "运行停止，需要查看原因"; "INTERRUPTED" -> "上次运行已中断"; else -> "尚未运行"
    }
}
