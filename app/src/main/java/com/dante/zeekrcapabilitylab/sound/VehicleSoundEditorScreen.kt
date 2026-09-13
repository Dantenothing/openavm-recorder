package com.dante.zeekrcapabilitylab.sound

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrbridge.sound.SoundFileNames
import com.dante.zeekrbridge.sound.SoundPurpose
import com.dante.zeekrcapabilitylab.diagnostic.UsbStorageInventory
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min

private fun t(en: String, zh: String, vararg args: Any?) = Utils.t(en, zh, *args)
private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VehicleSoundEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { VehicleSoundController(context, scope) }
    var name by remember { mutableStateOf("sound") }
    var purpose by remember { mutableStateOf(SoundPurpose.UNLOCK) }
    var downloads by remember { mutableStateOf(false) }
    var usbOptions by remember { mutableStateOf<List<UsbExportTarget>?>(null) }
    var usbLoading by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    fun back() {
        if (controller.phase == VehicleSoundPhase.SAVING) controller.message(t("Finishing the write; please wait.", "正在完成写入，请稍等再返回。"))
        else onBack()
    }
    BackHandler { back() }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.visibilityChanged(true)
            if (event == Lifecycle.Event.ON_STOP) controller.visibilityChanged(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.release() }
    }
    LaunchedEffect(controller.source) { controller.source?.let { name = SoundFileNames.cleanBase(it.audio.name) ?: "sound" } }
    LaunchedEffect(controller.playing) { while (controller.playing) { controller.refreshPreviewPosition(); delay(150) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::import) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> uri?.let(controller::saveDocument) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(t("Lock / Unlock Sound Maker", "上锁 / 解锁音效制作")) }, navigationIcon = {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Choose a downloaded music or video file. Edit locally, then install the WAV to your sound USB.",
                "选择浏览器下载的音乐或视频，在车机裁切试听，再保存到铃声 U 盘。"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !controller.busy, onClick = {
                    controller.stopPreview()
                    runCatching { picker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")) }
                        .onFailure { downloads = true; controller.message(t("Use Downloads to select the file.", "系统文件选择器不可用，请从下载目录选择。")) }
                }) { Text(t("Choose music / video", "选择音乐 / 视频")) }
                OutlinedButton(enabled = !controller.busy, onClick = { controller.stopPreview(); downloads = true }) { Text(t("Downloads", "下载目录")) }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM sound diagnostic", controller.diagnosticJson()))
                    Toast.makeText(context, t("Diagnostic JSON copied", "诊断 JSON 已复制"), Toast.LENGTH_SHORT).show()
                }) { Text(t("Copy diagnostic", "复制诊断")) }
                if (controller.busy && controller.phase != VehicleSoundPhase.SAVING)
                    TextButton(onClick = controller::cancel) { Text(t("Cancel", "取消")) }
            }
            if (controller.busy) {
                if (controller.phase == VehicleSoundPhase.IMPORTING && controller.progress > 0)
                    LinearProgressIndicator(progress = { controller.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (controller.status.isNotBlank()) Text(controller.status, style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints(Modifier.weight(1f)) {
                val source = controller.source
                if (source == null) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t("Start by downloading a normal local file in the browser. Video imports use its audio track.",
                            "先在浏览器下载普通本地文件，再回这里选择；视频会提取其中的声音。"))
                        Text(t("MP3, M4A, WAV and MP4 are common choices. Available formats depend on the vehicle decoder; silent or protected files cannot be used.",
                            "可尝试 MP3、M4A、WAV、MP4 等常见格式；具体取决于车机解码能力，无声或受保护的素材无法制作。"))
                        Text(t("Output: 48 kHz / 16-bit / mono WAV, under 1 MB. About 5 seconds is recommended.",
                            "输出为 48 kHz / 16-bit / 单声道 WAV，必须小于 1 MB，建议约 5 秒。"))
                        PendingInstall(controller, onDiscard = { discard = true })
                    }
                } else {
                    val chooseUsb: () -> Unit = {
                        controller.stopPreview(); usbLoading = true
                        scope.launch {
                            usbOptions = runCatching { withContext(Dispatchers.IO) { UsbExportVolumeResolver.mountedTargets(context).filter { it.directoryPath != null } } }
                                .onFailure { controller.message(t("Cannot read USB drives.", "无法读取 U 盘，请确认已插入并获得文件访问权限。")) }.getOrDefault(emptyList())
                            usbLoading = false
                        }
                    }
                    val exportDocument: () -> Unit = {
                        runCatching { saver.launch(SoundFileNames.wavFileName(name)) }
                            .onFailure { controller.message(t("The document saver is unavailable; use Save to USB.", "系统文件保存器不可用，请使用“保存到 U 盘”。")) }
                    }
                    if (maxWidth >= 900.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState())) { TrimCard(controller) }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutputCard(controller, name, { name = it }, purpose, { purpose = it }, usbLoading, chooseUsb, exportDocument)
                                PendingInstall(controller) { discard = true }
                            }
                        }
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrimCard(controller)
                            OutputCard(controller, name, { name = it }, purpose, { purpose = it }, usbLoading, chooseUsb, exportDocument)
                            PendingInstall(controller) { discard = true }
                        }
                    }
                }
            }
        }
    }
    if (downloads) DownloadsDialog(onDismiss = { downloads = false }, onSelected = { downloads = false; controller.import(Uri.fromFile(it)) })
    usbOptions?.let { options ->
        AlertDialog(onDismissRequest = { usbOptions = null }, title = { Text(t("Choose the sound USB", "选择铃声 U 盘")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (options.isEmpty()) Text(t("No writable USB path is available. Insert the sound USB and grant file access if requested.",
                    "未找到可用的 U 盘路径，请插入铃声 U 盘，并确认应用的文件访问权限。"))
                options.forEach { usb ->
                    OutlinedButton(onClick = { usbOptions = null; controller.saveToUsb(name, purpose, usb.storageUuid) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${usb.description} · ${usb.storageUuid}")
                    }
                }
                Text(t("Existing names are kept: a suffix is added when needed. Then choose the new sound in vehicle settings.",
                    "遇到同名音效会自动加编号保留原文件。保存后请到原车设置选择使用。"), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { usbOptions = null }) { Text(t("Close", "关闭")) } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(t("Discard retry data?", "放弃这次重试？")) },
        text = { Text(t("Any sound files already written to USB will remain. You can manage them in the sound toolbox.", "已经写入 U 盘的音效文件会保留，可回铃声工具箱查看和管理。")) },
        confirmButton = { TextButton(onClick = { discard = false; controller.discardInstall() }) { Text(t("Discard retry", "放弃重试")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(t("Keep", "保留任务")) } })
}

@Composable
private fun TrimCard(controller: VehicleSoundController) {
    val source = controller.source ?: return
    val meta = source.audio.meta
    val edit = controller.edit
    var zoomWindow by remember(source.audio.pcmFile) { mutableStateOf<Pair<Long, Long>?>(null) }
    var startText by remember(edit.startFrame, source.audio.pcmFile) { mutableStateOf(seconds(edit.startFrame.toDouble() / meta.sampleRate)) }
    var endText by remember(edit.endFrame, source.audio.pcmFile) { mutableStateOf(seconds(edit.endFrame.toDouble() / meta.sampleRate)) }
    val window = zoomWindow?.takeIf { edit.startFrame >= it.first && edit.endFrame <= it.second }
    val viewStart = window?.first ?: 0L
    val viewEnd = window?.second ?: meta.frameCount
    val keyboard = LocalSoftwareKeyboardController.current
    val total = (viewEnd - viewStart).coerceAtLeast(1).toFloat()
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(source.audio.name, style = MaterialTheme.typography.titleMedium)
            Text("${source.audio.formatLabel} · ${seconds(meta.frameCount.toDouble() / meta.sampleRate)} s", style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(145.dp).semantics { contentDescription = t("Audio waveform and selected range", "音频波形与裁切选区") }) {
                val left = (edit.startFrame - viewStart) / total * size.width
                val right = (edit.endFrame - viewStart) / total * size.width
                drawRect(primary.copy(alpha = .12f), Offset(left, 0f), Size(right - left, size.height))
                val peaks = source.waveform.max
                for (i in peaks.indices) {
                    val frame = i.toDouble() / peaks.size * meta.frameCount
                    if (frame < viewStart || frame > viewEnd) continue
                    val x = ((frame - viewStart) / total * size.width).toFloat()
                    val amplitude = peaks[i].coerceIn(0f, 1f) * size.height * .45f
                    drawLine(if (frame >= edit.startFrame && frame < edit.endFrame) primary else muted,
                        Offset(x, size.height / 2 - amplitude), Offset(x, size.height / 2 + amplitude), 2f)
                }
                if (controller.playing) {
                    val frame = edit.startFrame + controller.previewPositionMs * meta.sampleRate / 1000L
                    val x = (frame - viewStart) / total * size.width
                    drawLine(primary, Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            RangeSlider(value = edit.startFrame.toFloat()..edit.endFrame.toFloat(),
                onValueChange = { range ->
                    val from = range.start.toLong().coerceIn(0, meta.frameCount - 1)
                    val to = range.endInclusive.toLong().coerceIn(from + 1, meta.frameCount)
                    controller.update(edit.copy(startFrame = from, endFrame = to))
                }, valueRange = viewStart.toFloat()..viewEnd.toFloat(), enabled = !controller.busy,
                modifier = Modifier.semantics { contentDescription = t("Trim range", "裁切范围") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(startText, { startText = it }, label = { Text(t("Start (seconds)", "开始（秒）")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !controller.busy, modifier = Modifier.weight(1f))
                OutlinedTextField(endText, { endText = it }, label = { Text(t("End (seconds)", "结束（秒）")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !controller.busy, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { controller.setSeconds(startText, endText); keyboard?.hide() }, enabled = !controller.busy) { Text(t("Apply", "应用")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    zoomWindow = if (window != null) null else (edit.startFrame - meta.sampleRate * 5L).coerceAtLeast(0) to
                        (edit.endFrame + meta.sampleRate * 5L).coerceAtMost(meta.frameCount)
                }, enabled = !controller.busy) { Text(if (window != null) t("Whole source", "看全段") else t("Zoom selection", "放大选区")) }
                Button(onClick = controller::preview, enabled = !controller.busy && edit.validFor(meta)) { Text(if (controller.playing) t("Stop preview", "停止试听") else t("Preview result", "试听效果")) }
            }
            Toggle(t("Loop preview", "循环试听"), edit.loop, !controller.busy) { controller.update(edit.copy(loop = it)) }
            Text(t("Selection: ", "选区：") + seconds((edit.endFrame - edit.startFrame).toDouble() / meta.sampleRate) + " s · ${edit.outputBytes(meta.sampleRate) / 1000} KB")
            if (!edit.validFor(meta)) Text(t("Shorten the selection to keep the WAV under 1 MB.", "选区过长，请缩短到 WAV 小于 1 MB。"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OutputCard(controller: VehicleSoundController, name: String, onName: (String) -> Unit,
    purpose: SoundPurpose, onPurpose: (SoundPurpose) -> Unit, usbLoading: Boolean, onUsb: () -> Unit, onDocument: () -> Unit) {
    val meta = controller.source?.audio?.meta ?: return
    val edit = controller.edit
    val enabled = !controller.busy && !usbLoading
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Adjust and save", "调整与保存"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = purpose == SoundPurpose.UNLOCK, onClick = { onPurpose(SoundPurpose.UNLOCK) }, label = { Text(t("Unlock", "解锁")) }, enabled = enabled)
                FilterChip(selected = purpose == SoundPurpose.LOCK, onClick = { onPurpose(SoundPurpose.LOCK) }, label = { Text(t("Lock", "上锁")) }, enabled = enabled)
            }
            OutlinedTextField(name, onName, label = { Text(t("Sound name", "音效名称")) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(t("Volume", "音量") + " ${edit.volumePercent}%")
            Slider(edit.volumePercent.toFloat(), { controller.update(edit.copy(volumePercent = it.toInt())) }, valueRange = 0f..200f, enabled = enabled)
            Toggle(t("Normalize loudness", "统一响度"), edit.normalize, enabled) { controller.update(edit.copy(normalize = it)) }
            val fadeMax = min(2000L, (edit.endFrame - edit.startFrame) * 1000 / meta.sampleRate).coerceAtLeast(1).toFloat()
            Text(t("Fade in", "淡入") + " ${edit.fadeInMs} ms")
            Slider(edit.fadeInMs.toFloat().coerceAtMost(fadeMax), { controller.update(edit.copy(fadeInMs = it.toLong())) }, valueRange = 0f..fadeMax, enabled = enabled)
            Text(t("Fade out", "淡出") + " ${edit.fadeOutMs} ms")
            Slider(edit.fadeOutMs.toFloat().coerceAtMost(fadeMax), { controller.update(edit.copy(fadeOutMs = it.toLong())) }, valueRange = 0f..fadeMax, enabled = enabled)
            Text(t("48 kHz / 16-bit / mono WAV · under 1 MB · about 5 seconds recommended.", "48 kHz / 16-bit / 单声道 WAV · 小于 1 MB · 建议约 5 秒"), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onUsb, enabled = enabled && edit.validFor(meta) && controller.pending == null, modifier = Modifier.fillMaxWidth()) { Text(t("Save to sound USB", "保存到铃声 U 盘")) }
            OutlinedButton(onClick = onDocument, enabled = enabled && edit.validFor(meta), modifier = Modifier.fillMaxWidth()) { Text(t("Save WAV elsewhere", "另存 WAV 文件")) }
            Text(t("This makes the file. Select it as the lock/unlock sound in the vehicle's own settings; that list may refresh after you leave and return.",
                "这里负责制作文件；上锁或解锁的实际选用，请到原车设置完成。原车列表可能在上下车后才刷新。"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label); Switch(value, change, enabled = enabled)
    }
}

@Composable
private fun PendingInstall(controller: VehicleSoundController, onDiscard: () -> Unit) {
    controller.pending?.let { task ->
        Text(t("Unfinished USB install: ", "上次 U 盘写入未完成：") + task.offer.fileName)
        Text(t("Connect the same USB to continue. Existing files are kept if you discard the retry.", "插入原 U 盘可继续；放弃重试会保留 U 盘现有文件。"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::retryInstall, enabled = !controller.busy) { Text(t("Continue install", "继续写入")) }
            TextButton(onClick = onDiscard, enabled = !controller.busy) { Text(t("Discard retry", "放弃重试")) }
        }
    }
}

@Composable
private fun DownloadsDialog(onDismiss: () -> Unit, onSelected: (File) -> Unit) {
    val context = LocalContext.current
    var listing by remember { mutableStateOf<DownloadedSoundListing?>(null) }
    var directory by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(directory, refresh) {
        runCatching { withContext(Dispatchers.IO) { DownloadedSoundFiles.list(directory) } }
            .onSuccess { listing = it; error = "" }.onFailure { error = t("Could not read Downloads.", "无法读取下载目录。") }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Downloaded music / videos", "下载的音乐 / 视频")) },
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listing?.let { list ->
                Text(list.directory.relativeTo(list.root).path.ifBlank { t("Downloads", "下载目录") })
                if (list.directory != list.root) TextButton(onClick = { directory = list.directory.parentFile }) { Text(t("Up one folder", "返回上一级")) }
                if (!list.readable || list.entries.isEmpty()) Text(t("No accessible media was found. Check file permissions or use the system file picker.", "未找到可读取的素材。可检查文件权限，或使用系统文件选择器。"))
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(list.entries, key = { it.file.absolutePath }) { entry ->
                        TextButton(onClick = { if (entry.folder) directory = entry.file else onSelected(entry.file) }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (entry.folder) "▸ " else "") + entry.file.name + if (entry.folder) "" else " · ${entry.bytes / 1024} KB", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (error.isNotEmpty()) Text(error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refresh++ }) { Text(t("Refresh", "刷新")) }
                TextButton(onClick = {
                    val request = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    permissions.launch(request)
                }) { Text(t("Media permission", "素材读取权限")) }
            }
            val settings = UsbStorageInventory.allFilesSettingsIntent(context)
            if (settings != null) TextButton(onClick = { runCatching { context.startActivity(settings) }.onFailure { error = t("File settings unavailable.", "无法打开文件访问设置。") } }) {
                Text(t("File access settings", "文件访问设置"))
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) } })
}
