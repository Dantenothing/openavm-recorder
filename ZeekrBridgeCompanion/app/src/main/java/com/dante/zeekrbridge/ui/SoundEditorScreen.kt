package com.dante.zeekrbridge.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.sound.EditSettings
import com.dante.zeekrbridge.sound.AudioResampler
import com.dante.zeekrbridge.sound.SoundEditorController
import com.dante.zeekrbridge.sound.SoundFileNames
import com.dante.zeekrbridge.sound.SoundPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class DragHandle { None, Start, End, Move }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SoundEditorController(context, scope) }
    val imported = controller.imported
    val edit = controller.edit

    var fileName by remember { mutableStateOf("") }
    var pendingUsbUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(imported) {
        fileName = SoundFileNames.wavFileName(imported?.name, "sound.wav")
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) controller.import(uri)
    }

    val pickUsbLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            pendingUsbUri = uri
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        if (uri != null) controller.exportToDocument(uri, fileName)
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    LaunchedEffect(controller.playing) {
        while (controller.playing) {
            controller.refreshPlayPosition()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Sound maker", "声音制作器")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back to Toolbox", "返回工具箱"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                "本地音频编辑：导入 MP3 / M4A / AAC / WAV / FLAC / OGG（以设备实际可用的系统解码器为准），" +
                    "裁切、试听、音量、标准化、淡入淡出、Mono/Stereo，输出 44.1 kHz / 16-bit PCM WAV。" +
                    "所有处理仅在本机完成，不上传云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "实验性说明：App 可以生成符合上述参数的 WAV，但无法保证当前 Zeekr 固件一定识别该文件、文件名或音频长度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))

            when (controller.phase) {
                SoundPhase.Idle -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(t("Choose an audio file", "选择音频文件"), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "支持的常见格式：MP3、M4A/AAC、WAV、FLAC、OGG。若设备缺少某格式解码器，会明确提示原因。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                pickAudioLauncher.launch(
                                    arrayOf(
                                        "audio/mpeg",
                                        "audio/mp3",
                                        "audio/mp4",
                                        "audio/x-m4a",
                                        "audio/aac",
                                        "audio/wav",
                                        "audio/x-wav",
                                        "audio/flac",
                                        "audio/ogg",
                                        "application/ogg",
                                        "audio/*",
                                    ),
                                )
                            }) { Text(t("Import audio…", "导入音频…")) }
                        }
                    }
                }

                SoundPhase.Importing, SoundPhase.Waveform -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (controller.phase == SoundPhase.Importing) "正在分析…" else "正在生成波形…",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            if (controller.progressText.isNotBlank()) {
                                Text(controller.progressText, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { controller.cancelCurrent() }) { Text(t("Cancel import", "取消导入")) }
                        }
                    }
                }

                SoundPhase.Ready -> {
                    if (imported != null) {
                        EditorSection(
                            controller = controller,
                            imported = imported,
                            edit = edit,
                            fileName = fileName,
                            onFileNameChange = { fileName = it },
                            onCreateDocument = { createDocLauncher.launch(SoundFileNames.cleanBase(fileName) ?: "sound") },
                            onPickUsb = { pickUsbLauncher.launch(null) },
                        )
                    }
                }

                SoundPhase.Exporting, SoundPhase.Verifying -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (controller.phase == SoundPhase.Exporting) "正在转换…" else "正在校验…",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            if (controller.progressText.isNotBlank()) {
                                Text(controller.progressText, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { controller.cancelCurrent() }) { Text(t("Cancel conversion", "取消转换")) }
                        }
                    }
                }

                SoundPhase.Done -> {
                    val result = controller.exportResult
                    if (result != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(t("Completed", "已完成"), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(t("Destination: ${result.target}", "目标：${result.target}"), style = MaterialTheme.typography.bodyMedium)
                                Text(t("File: ${result.fileName}", "文件名：${result.fileName}"), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "大小：${SoundEditorController.formatBytes(result.sizeBytes)} | " +
                                        "时长：${SoundEditorController.formatDuration(result.durationMs)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (result.backupName != null) {
                                    Text(
                                        "同名原文件已备份：${result.backupName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (result.savedFile != null) {
                                    Text(
                                        "路径：${result.savedFile.absolutePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (result.verified) {
                                    Text(
                                        "已重新读取并校验 WAV 头、数据长度、采样率、位深、声道数与时长。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { controller.resetEdit() }) { Text(t("Continue editing", "继续编辑")) }
                                    OutlinedButton(onClick = onBack) { Text(t("Back to Toolbox", "返回工具箱")) }
                                }
                            }
                        }
                    }
                }

                SoundPhase.Error -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "处理失败",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(controller.statusText, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { controller.release(); fileName = "sound.wav" }) {
                                    Text(t("Choose another file", "重新选择"))
                                }
                                OutlinedButton(onClick = onBack) { Text(t("Back to Toolbox", "返回工具箱")) }
                            }
                        }
                    }
                }
            }

            if (controller.statusText.isNotBlank() && controller.phase != SoundPhase.Error) {
                Spacer(Modifier.height(8.dp))
                Text(controller.statusText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    val usbUri = pendingUsbUri
    val usbImported = imported
    if (usbUri != null && usbImported != null) {
        val selFrames = edit.endFrame - edit.startFrame
        val outFrames = AudioResampler.outputFrameCount(selFrames, usbImported.meta.sampleRate)
        val estBytes = outFrames * edit.outputChannels * 2L
        AlertDialog(
            onDismissRequest = { pendingUsbUri = null },
            title = { Text(t("Write to USB?", "写入 USB？")) },
            text = {
                Column {
                    Text(t("Destination: ${usbUri.toString().take(120)}", "目标：${usbUri.toString().take(120)}"), style = MaterialTheme.typography.bodySmall)
                    Text(t("File: ${SoundFileNames.wavFileName(fileName)}", "文件名：${SoundFileNames.wavFileName(fileName)}"), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "预计大小：${SoundEditorController.formatBytes(estBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "参数：44.1 kHz / 16-bit PCM / " +
                            (if (edit.outputChannels == 2) "Stereo" else "Mono") +
                            " | 音量 ${edit.volumePercent}%" +
                            (if (edit.normalize) " | 标准化 开" else "") +
                            (if (edit.fadeInMs > 0) " | 淡入 ${edit.fadeInMs / 1000.0}s" else "") +
                            (if (edit.fadeOutMs > 0) " | 淡出 ${edit.fadeOutMs / 1000.0}s" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "同名文件将先备份再写入；写入失败会回滚，不会覆盖原文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingUsbUri = null
                    controller.exportToUsb(usbUri, fileName)
                }) { Text(t("Write to USB", "写入 USB")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUsbUri = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun EditorSection(
    controller: SoundEditorController,
    imported: com.dante.zeekrbridge.sound.ImportedSound,
    edit: EditSettings,
    fileName: String,
    onFileNameChange: (String) -> Unit,
    onCreateDocument: () -> Unit,
    onPickUsb: () -> Unit,
) {
    val totalFrames = imported.meta.frameCount
    val selDurationMs = (edit.endFrame - edit.startFrame) * 1000L / imported.meta.sampleRate
    var zoomLevel by remember { mutableStateOf(1f) }
    val viewFrames = (totalFrames.toDouble() / zoomLevel).toLong().coerceAtLeast(1L)
    val center = (edit.startFrame + edit.endFrame) / 2L
    val viewStart = (center - viewFrames / 2L).coerceIn(0L, max(0L, totalFrames - viewFrames))
    val viewEnd = (viewStart + viewFrames).coerceAtMost(totalFrames)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(imported.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "格式 ${imported.formatLabel} | 源采样率 ${imported.meta.sampleRate} Hz | " +
                    "${if (imported.meta.channels == 2) "立体声" else "单声道"} | " +
                    "总时长 ${SoundEditorController.formatDuration(imported.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            WaveformEditor(
                controller = controller,
                peaks = imported.waveform,
                totalFrames = totalFrames,
                viewStart = viewStart,
                viewEnd = viewEnd,
                startFrame = edit.startFrame,
                endFrame = edit.endFrame,
                playFrame = controller.playPositionFrame,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("Waveform zoom", "波形缩放"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = zoomLevel,
                    onValueChange = { zoomLevel = it },
                    valueRange = 1f..40f,
                    steps = 38,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.0fx".format(zoomLevel),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(44.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "选区 ${SoundEditorController.formatDuration(edit.startFrame * 1000L / imported.meta.sampleRate)}" +
                    " — ${SoundEditorController.formatDuration(edit.endFrame * 1000L / imported.meta.sampleRate)}" +
                    "（输出时长 ${SoundEditorController.formatDuration(selDurationMs)}）",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { controller.togglePlayback() }) {
                    Text(if (controller.playing) "暂停" else "播放选区")
                }
                OutlinedButton(onClick = { controller.stopPlayback() }) { Text(t("Stop", "停止")) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = edit.loopPreview,
                        onCheckedChange = { controller.updateEdit(edit.copy(loopPreview = it)) },
                    )
                    Text(t("Loop preview", "循环试听"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (controller.playing) {
                Slider(
                    value = controller.playPositionFrame.coerceIn(edit.startFrame, max(edit.startFrame, edit.endFrame - 1L)).toFloat(),
                    onValueChange = { controller.seekPlayback(it.toLong()) },
                    valueRange = edit.startFrame.toFloat()..max(edit.startFrame, edit.endFrame - 1L).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(t("Adjust", "调整"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(t("Volume ${edit.volumePercent}%", "音量 ${edit.volumePercent}%"), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = edit.volumePercent.toFloat(),
                onValueChange = { controller.updateEdit(edit.copy(volumePercent = it.toInt())) },
                valueRange = 0f..200f,
                steps = 19,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("Peak normalization", "峰值标准化"), modifier = Modifier.weight(1f))
                Switch(
                    checked = edit.normalize,
                    onCheckedChange = { controller.updateEdit(edit.copy(normalize = it)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(t("Fade in ${edit.fadeInMs / 1000L}.${(edit.fadeInMs % 1000L) / 100L} s", "淡入 ${edit.fadeInMs / 1000L}.${(edit.fadeInMs % 1000L) / 100L} 秒"), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = edit.fadeInMs.toFloat(),
                onValueChange = { controller.updateEdit(edit.copy(fadeInMs = it.toLong())) },
                valueRange = 0f..10000f,
                steps = 99,
            )
            Text(t("Fade out ${edit.fadeOutMs / 1000L}.${(edit.fadeOutMs % 1000L) / 100L} s", "淡出 ${edit.fadeOutMs / 1000L}.${(edit.fadeOutMs % 1000L) / 100L} 秒"), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = edit.fadeOutMs.toFloat(),
                onValueChange = { controller.updateEdit(edit.copy(fadeOutMs = it.toLong())) },
                valueRange = 0f..10000f,
                steps = 99,
            )
            Spacer(Modifier.height(4.dp))
            Text(t("Output channels", "输出声道"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { controller.updateEdit(edit.copy(outputChannels = 2)) },
                ) { Text(if (edit.outputChannels == 2) "Stereo ✓" else "Stereo") }
                OutlinedButton(
                    onClick = { controller.updateEdit(edit.copy(outputChannels = 1)) },
                ) { Text(if (edit.outputChannels == 1) "Mono ✓" else "Mono") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.resetEdit() }) { Text(t("Reset", "恢复默认设置")) }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(t("Export 44.1 kHz / 16-bit PCM WAV", "导出 44.1 kHz / 16-bit PCM WAV"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = fileName,
                onValueChange = onFileNameChange,
                label = { Text(t("File name (.wav is added automatically)", "文件名（自动补充 .wav）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { controller.exportToPhone(fileName) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Save to phone", "保存到手机")) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onCreateDocument,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Save as…", "另存为…（系统文档选择器）")) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onPickUsb,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Write to USB", "写入 USB（SAF 授权）")) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { controller.sendToCar(fileName) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Send to car (experimental, ≤1 MiB)", "发送到车机（实验性次要入口，≤1 MiB）")) }
            Spacer(Modifier.height(6.dp))
            Text(
                "USB 写入规则：先列出目标目录，同名文件先备份，再写临时文件并校验，校验通过后才替换；" +
                    "任何失败都回滚，不会静默覆盖原文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaveformEditor(
    controller: SoundEditorController,
    peaks: com.dante.zeekrbridge.sound.WaveformPeaks,
    totalFrames: Long,
    viewStart: Long,
    viewEnd: Long,
    startFrame: Long,
    endFrame: Long,
    playFrame: Long,
) {
    val colors = MaterialTheme.colorScheme
    var activeHandle by remember { mutableStateOf(DragHandle.None) }
    val minSelection = max(1L, totalFrames / 1000L)
    val viewLen = (viewEnd - viewStart).coerceAtLeast(1L)

    fun frameToX(frame: Long, width: Float): Float =
        ((frame - viewStart).toFloat() / viewLen.toFloat() * width).coerceIn(0f, width)

    fun xToFrame(x: Float, width: Float): Long {
        val frac = (x / width).coerceIn(0f, 1f)
        return (viewStart + (frac * viewLen).toLong()).coerceIn(0L, max(0L, totalFrames - 1L))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(controller, viewStart, viewEnd) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        if (w <= 0f) return@detectDragGestures
                        val cur = controller.edit
                        val sx = frameToX(cur.startFrame, w)
                        val ex = frameToX(cur.endFrame, w)
                        val touch = 24.dp.toPx()
                        val nearStart = abs(offset.x - sx) <= min(touch, (ex - sx) / 2f)
                        val nearEnd = abs(offset.x - ex) <= touch
                        activeHandle = when {
                            nearStart -> DragHandle.Start
                            nearEnd -> DragHandle.End
                            offset.x in sx..ex -> DragHandle.Move
                            abs(offset.x - sx) <= abs(offset.x - ex) -> DragHandle.Start
                            else -> DragHandle.End
                        }
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat()
                        if (w <= 0f) return@detectDragGestures
                        val frame = xToFrame(change.position.x, w)
                        val cur = controller.edit
                        when (activeHandle) {
                            DragHandle.Start -> {
                                val newEnd = maxOf(frame + minSelection, cur.endFrame).coerceAtMost(totalFrames)
                                controller.updateEdit(cur.copy(startFrame = frame, endFrame = newEnd))
                            }
                            DragHandle.End -> {
                                val newStart = minOf(frame - minSelection, cur.startFrame).coerceAtLeast(0L)
                                controller.updateEdit(cur.copy(startFrame = newStart, endFrame = frame))
                            }
                            DragHandle.Move -> {
                                val len = cur.endFrame - cur.startFrame
                                val s = (frame - len / 2).coerceIn(0L, max(0L, totalFrames - len))
                                controller.updateEdit(cur.copy(startFrame = s, endFrame = s + len))
                            }
                            DragHandle.None -> Unit
                        }
                        change.consume()
                    },
                    onDragEnd = { activeHandle = DragHandle.None },
                    onDragCancel = { activeHandle = DragHandle.None },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val buckets = peaks.buckets
        val barW = w / buckets
        val selStart = frameToX(startFrame, w)
        val selEnd = frameToX(endFrame, w)

        for (b in 0 until buckets) {
            val x = b * barW
            val centerX = x + barW / 2f
            val inSelection = centerX >= selStart && centerX <= selEnd
            val color = if (inSelection) colors.primary else colors.surfaceVariant
            val f0 = viewStart + (b.toLong() * viewLen) / buckets
            val f1 = viewStart + (((b + 1).toLong() * viewLen) / buckets).coerceAtMost(totalFrames)
            val b0 = (f0 * buckets / totalFrames).toInt().coerceIn(0, buckets - 1)
            val b1 = (((f1 - 1).coerceAtLeast(f0)) * buckets / totalFrames).toInt().coerceIn(b0, buckets - 1)
            var minV = 1f
            var maxV = 0f
            for (bb in b0..b1) {
                if (peaks.min[bb] < minV) minV = peaks.min[bb]
                if (peaks.max[bb] > maxV) maxV = peaks.max[bb]
            }
            minV = minV.coerceIn(0f, 1f)
            maxV = maxV.coerceIn(0f, 1f)
            val maxH = maxV * midY
            val minH = minV * midY
            drawLine(
                color = color,
                start = Offset(centerX, midY - maxH),
                end = Offset(centerX, midY - minH),
                strokeWidth = barW * 0.8f,
            )
            drawLine(
                color = color,
                start = Offset(centerX, midY + minH),
                end = Offset(centerX, midY + maxH),
                strokeWidth = barW * 0.8f,
            )
        }

        val handleColor = colors.primary
        drawLine(
            color = handleColor,
            start = Offset(selStart, 0f),
            end = Offset(selStart, h),
            strokeWidth = 3.dp.toPx(),
        )
        drawLine(
            color = handleColor,
            start = Offset(selEnd, 0f),
            end = Offset(selEnd, h),
            strokeWidth = 3.dp.toPx(),
        )
        drawCircle(handleColor, radius = 8.dp.toPx(), center = Offset(selStart, h / 2f))
        drawCircle(handleColor, radius = 8.dp.toPx(), center = Offset(selEnd, h / 2f))

        if (playFrame > 0 && playFrame in startFrame..endFrame) {
            val px = frameToX(playFrame, w)
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
