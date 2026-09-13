package com.dante.zeekrbridge.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
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
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.PairedDevice
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.sound.EditSettings
import com.dante.zeekrbridge.sound.AudioResampler
import com.dante.zeekrbridge.sound.SafSoundTargetAction
import com.dante.zeekrbridge.sound.SafSoundTargetResolution
import com.dante.zeekrbridge.sound.SoundEditorController
import com.dante.zeekrbridge.sound.SoundFileNames
import com.dante.zeekrbridge.sound.SoundPhase
import com.dante.zeekrbridge.sound.SoundPurpose
import com.dante.zeekrbridge.sound.UsbSaf
import com.dante.zeekrbridge.sound.ZeekrSoundPreset
import io.github.dantenothing.avmtransfer.protocol.SoundOfferStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class DragHandle { None, Start, End, Move }

private data class PendingUsbSelection(
    val treeUri: android.net.Uri,
    val resolution: SafSoundTargetResolution,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundEditorScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SoundEditorController(context, scope) }
    val imported = controller.imported
    val edit = controller.edit

    var fileName by remember { mutableStateOf("") }
    var pendingUsbSelection by remember { mutableStateOf<PendingUsbSelection?>(null) }
    var preset by remember { mutableStateOf(ZeekrSoundPreset.ZEEKR_7X_AUNZ) }
    var purpose by remember { mutableStateOf(SoundPurpose.UNLOCK) }
    val pairedCars by PairingManager.devices.collectAsState()
    val offerRevision by OutboundOfferStore.revision.collectAsState()
    val relayOffers = remember(offerRevision) { OutboundOfferStore.offers().filter { it.targetCarDeviceId != null } }
    val recentRelayOffers = relayOffers.take(5)
    var targetCarId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairedCars) {
        if (targetCarId !in pairedCars.map { it.carDeviceId }) targetCarId = pairedCars.firstOrNull()?.carDeviceId
    }
    val targetCar = pairedCars.firstOrNull { it.carDeviceId == targetCarId }
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
            scope.launch {
                val resolution = withContext(Dispatchers.IO) {
                    UsbSaf.resolveSoundTargets(context, uri, preset.targetDirectoryNames)
                }
                pendingUsbSelection = PendingUsbSelection(uri, resolution)
            }
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
                title = { Text(t("Zeekr Lock / Unlock Sound Maker", "极氪解闭锁音效制作器")) },
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
                t(
                    "Local audio editor: import common audio, trim by waveform, preview, adjust volume and fades, then output 48 kHz / 16-bit PCM WAV. Processing stays on this phone.",
                    "本地音频编辑：导入常见音频，使用波形裁切、试听、音量和淡入淡出，输出 48 kHz / 16-bit PCM WAV。所有处理仅在本机完成。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                t(
                    "Recommended: 5 seconds or less. Zeekr-compatible output must remain under 1 MB.",
                    "建议长度不超过 5 秒；Zeekr 兼容输出必须小于 1 MB。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))

            SoundPresetCard(
                preset = preset,
                purpose = purpose,
                onPreset = { selected ->
                    preset = selected
                    if (selected.isZeekrCompatible && controller.imported != null && controller.edit.outputChannels != 1) {
                        controller.updateEdit(controller.edit.copy(outputChannels = 1))
                    }
                },
                onPurpose = { purpose = it },
            )
            Spacer(Modifier.height(10.dp))
            if (recentRelayOffers.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t("Recent vehicle USB sends", "最近的车机 USB 发送"), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(
                                onClick = { scope.launch { withContext(Dispatchers.IO) { OutboundOfferStore.clearFinished() } } },
                                enabled = relayOffers.any { it.state in SoundOfferStates.terminal },
                            ) {
                                Text(t("Clear finished", "清理已结束"))
                            }
                        }
                        recentRelayOffers.forEach { offer ->
                            Text(
                                "${offer.fileName} · ${offer.targetCarName ?: offer.targetCarDeviceId} · ${offer.state}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (offer.state == "FAILED" || offer.state == "FAILED_RECOVERABLE") {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            offer.statusMessage?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            when (controller.phase) {
                SoundPhase.Idle -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(t("Choose an audio file", "选择音频文件"), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t(
                                    "Common formats: MP3, M4A/AAC, WAV, FLAC and OGG. An unavailable device decoder is reported clearly.",
                                    "支持的常见格式：MP3、M4A/AAC、WAV、FLAC、OGG。若设备缺少某格式解码器，会明确提示原因。",
                                ),
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
                                if (controller.phase == SoundPhase.Importing) t("Analyzing…", "正在分析…")
                                else t("Building waveform…", "正在生成波形…"),
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
                            preset = preset,
                            purpose = purpose,
                            pairedCars = pairedCars,
                            targetCar = targetCar,
                            onTargetCar = { targetCarId = it.carDeviceId },
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
                                if (controller.phase == SoundPhase.Exporting) t("Converting…", "正在转换…")
                                else t("Verifying…", "正在校验…"),
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
                                Text(t("Destination: {0}", "目标：{0}", result.target), style = MaterialTheme.typography.bodyMedium)
                                Text(t("File: {0}", "文件名：{0}", result.fileName), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    t(
                                        "Size: {0} | Duration: {1}", "大小：{0} | 时长：{1}", SoundEditorController.formatBytes(result.sizeBytes), SoundEditorController.formatDuration(result.durationMs)),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (result.backupName != null) {
                                    Text(
                                        t(
                                            "The previous same-name file was backed up as {0}", "同名原文件已备份：{0}", result.backupName),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (result.savedFile != null) {
                                    Text(
                                        t("Path: {0}", "路径：{0}", result.savedFile.absolutePath),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (result.verified) {
                                    Text(
                                        t(
                                            "The WAV header, data length, sample rate, bit depth, channels and duration were reread and verified.",
                                            "已重新读取并校验 WAV 头、数据长度、采样率、位深、声道数与时长。",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (result.target.startsWith("USB") && preset.isZeekrCompatible) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        t(
                                            "Next: insert this USB into the vehicle's external-storage / Sentry USB port, then choose Personalised Sound Effect in the vehicle's lock/unlock feedback settings.",
                                            "下一步：将 USB 插回车辆的外部存储 / 哨兵 USB 口，然后在车辆解闭锁反馈设置中选择 Personalised Sound Effect。",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        t(
                                            "Other Android/System folders on the USB were not modified and normally do not affect vehicle scanning.",
                                            "USB 上的其他 Android/System 文件夹未被修改，通常也不会影响车辆扫描。",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { controller.continueEditing() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(t("Continue editing current audio", "继续编辑当前音频")) }
                                OutlinedButton(
                                    onClick = { controller.startAnotherSound() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(t("Make another lock / unlock sound", "制作另一段解闭锁音效")) }
                                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                    Text(t("Back to Toolbox", "返回工具箱"))
                                }
                            }
                        }
                    }
                }

                SoundPhase.Error -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                t("Processing failed", "处理失败"),
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

    val usbSelection = pendingUsbSelection
    val usbImported = imported
    if (usbSelection != null && usbImported != null) {
        val usbUri = usbSelection.treeUri
        val targetResolution = usbSelection.resolution
        val selFrames = edit.endFrame - edit.startFrame
        val outFrames = AudioResampler.outputFrameCount(selFrames, usbImported.meta.sampleRate)
        val estBytes = 44L + outFrames * edit.outputChannels * 2L
        AlertDialog(
            onDismissRequest = { pendingUsbSelection = null },
            title = { Text(t("Write to USB?", "写入 USB？")) },
            text = {
                Column {
                    Text(
                        t(
                            "Preset: {0}", "预设：{0}", soundPresetLabel(preset)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        t(
                            "Purpose label: {0}", "用途标签：{0}", soundPurposeLabel(purpose)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        t(
                            "Selected tree: {0}", "已选目录：{0}", targetResolution.selectedDisplayName ?: targetResolution.selectedDocumentId ?: t("Unknown", "未知")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        t(
                            "Resolved destination: {0}", "最终写入目录：{0}", soundTargetDirectories(preset)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (targetResolution.action == SafSoundTargetAction.REJECT_AMBIGUOUS) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    if (targetResolution.action == SafSoundTargetAction.REJECT_AMBIGUOUS) {
                        Text(
                            t(
                                "Bilingual-folder mode requires the USB root. Cancel and select the drive itself.",
                                "中英文双目录模式必须选择 U 盘根目录。请取消并选择 U 盘本身。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(t("File: {0}", "文件名：{0}", SoundFileNames.wavFileName(fileName)), style = MaterialTheme.typography.bodySmall)
                    Text(
                        t(
                            "Estimated size: {0}", "预计大小：{0}", SoundEditorController.formatBytes(estBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preset.acceptsSize(estBytes)) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("Parameters: ", "参数：") + "48 kHz / 16-bit PCM / " +
                            (if (edit.outputChannels == 2) "Stereo" else "Mono") +
                            t(" | Volume {0}%", " | 音量 {0}%", edit.volumePercent) +
                            (if (edit.normalize) t(" | Normalize on", " | 标准化 开") else "") +
                            (if (edit.fadeInMs > 0) t(" | Fade in {0}s", " | 淡入 {0}s", edit.fadeInMs / 1000.0) else "") +
                            (if (edit.fadeOutMs > 0) t(" | Fade out {0}s", " | 淡出 {0}s", edit.fadeOutMs / 1000.0) else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t(
                            "A same-name file is backed up before writing. Other USB folders are never modified.",
                            "同名文件将先备份再写入；USB 上的其他目录绝不会被修改。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = preset.acceptsSize(estBytes) &&
                        targetResolution.action != SafSoundTargetAction.REJECT_AMBIGUOUS,
                    onClick = {
                    pendingUsbSelection = null
                    controller.exportToUsb(usbUri, fileName, preset)
                }) { Text(t("Generate and write", "生成并写入")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUsbSelection = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun SoundPresetCard(
    preset: ZeekrSoundPreset,
    purpose: SoundPurpose,
    onPreset: (ZeekrSoundPreset) -> Unit,
    onPurpose: (SoundPurpose) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(t("Vehicle preset", "车辆预设"), style = MaterialTheme.typography.titleMedium)
            Text(
                t(
                    "Zeekr 7X · Australia / New Zealand",
                    "Zeekr 7X · 澳洲 / 新西兰",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZeekrSoundPreset.entries.forEach { candidate ->
                if (candidate == preset) {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Text("${soundPresetLabel(candidate)} ✓")
                    }
                } else {
                    OutlinedButton(onClick = { onPreset(candidate) }, modifier = Modifier.fillMaxWidth()) {
                        Text(soundPresetLabel(candidate))
                    }
                }
            }
            Text(
                when (preset) {
                    ZeekrSoundPreset.ZEEKR_7X_AUNZ ->
                        t(
                            "Creates both /Lock Status Tones/ and /解闭锁音效/ for all vehicle languages.",
                            "同时创建 /Lock Status Tones/ 和 /解闭锁音效/，兼容中英文车机。",
                        )
                    ZeekrSoundPreset.GENERIC_WAV ->
                        t("No Zeekr folder rule; write to the folder you choose.", "不套用 Zeekr 目录规则，写入你选择的目录。")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(t("Management label", "用途管理标签"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundPurpose.entries.forEach { candidate ->
                    if (candidate == purpose) {
                        Button(onClick = {}) { Text("${soundPurposeLabel(candidate)} ✓") }
                    } else {
                        OutlinedButton(onClick = { onPurpose(candidate) }) { Text(soundPurposeLabel(candidate)) }
                    }
                }
            }
            Text(
                t(
                    "Unlock / Lock is only an organizer label. It does not change the WAV format, folder or required file name.",
                    "解锁 / 闭锁只用于管理标记，不会改变 WAV 格式、目录或文件名规则。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun soundPresetLabel(preset: ZeekrSoundPreset): String = when (preset) {
    ZeekrSoundPreset.ZEEKR_7X_AUNZ -> t("Zeekr 7X · bilingual folders", "极氪 7X · 中英文双目录")
    ZeekrSoundPreset.GENERIC_WAV -> t("Generic WAV", "通用 WAV")
}

private fun soundTargetDirectories(preset: ZeekrSoundPreset): String =
    preset.targetDirectoryNames
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" + ") { "/$it/" }
        ?: t("the selected folder", "所选目录")

private fun soundPurposeLabel(purpose: SoundPurpose): String = when (purpose) {
    SoundPurpose.UNLOCK -> t("Unlock sound", "解锁音效")
    SoundPurpose.LOCK -> t("Lock sound", "闭锁音效")
}

@Composable
private fun EditorSection(
    controller: SoundEditorController,
    imported: com.dante.zeekrbridge.sound.ImportedSound,
    edit: EditSettings,
    fileName: String,
    preset: ZeekrSoundPreset,
    purpose: SoundPurpose,
    pairedCars: List<PairedDevice>,
    targetCar: PairedDevice?,
    onTargetCar: (PairedDevice) -> Unit,
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
                t(
                    "Format {0} | Source {1} Hz | {2} | Duration {3}",
                    "格式 {0} | 源采样率 {1} Hz | {2} | 总时长 {3}",
                    imported.formatLabel, imported.meta.sampleRate,
                    if (imported.meta.channels == 2) t("Stereo", "立体声") else t("Mono", "单声道"),
                    SoundEditorController.formatDuration(imported.durationMs),
                ),
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
                t(
                    "Selection {0} — {1} (output {2})",
                    "选区 {0} — {1}（输出时长 {2}）",
                    SoundEditorController.formatDuration(edit.startFrame * 1000L / imported.meta.sampleRate),
                    SoundEditorController.formatDuration(edit.endFrame * 1000L / imported.meta.sampleRate),
                    SoundEditorController.formatDuration(selDurationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { controller.togglePlayback() }) {
                    Text(if (controller.playing) t("Pause", "暂停") else t("Play selection", "播放选区"))
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
            Text(t("Volume {0}%", "音量 {0}%", edit.volumePercent), style = MaterialTheme.typography.bodySmall)
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
            Text(t("Fade in {0}.{1} s", "淡入 {0}.{1} 秒", edit.fadeInMs / 1000L, (edit.fadeInMs % 1000L) / 100L), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = edit.fadeInMs.toFloat(),
                onValueChange = { controller.updateEdit(edit.copy(fadeInMs = it.toLong())) },
                valueRange = 0f..10000f,
                steps = 99,
            )
            Text(t("Fade out {0}.{1} s", "淡出 {0}.{1} 秒", edit.fadeOutMs / 1000L, (edit.fadeOutMs % 1000L) / 100L), style = MaterialTheme.typography.bodySmall)
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
            Text(t("Export 48 kHz / 16-bit PCM WAV", "导出 48 kHz / 16-bit PCM WAV"), style = MaterialTheme.typography.titleMedium)
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
            ) { Text(t("Write to Zeekr USB", "写入 Zeekr USB（SAF 授权）")) }
            Spacer(Modifier.height(4.dp))
            Text(t("Target vehicle", "目标车机"), style = MaterialTheme.typography.labelLarge)
            if (pairedCars.isEmpty()) {
                Text(t("Pair a vehicle on the receiver page first.", "请先在接收页配对车机。"), color = MaterialTheme.colorScheme.error)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pairedCars.forEach { car ->
                        if (car.carDeviceId == targetCar?.carDeviceId) {
                            Button(onClick = {}) { Text("${car.name} ✓") }
                        } else {
                            OutlinedButton(onClick = { onTargetCar(car) }) { Text(car.name) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    targetCar?.let { controller.sendToCar(fileName, preset, purpose, it.carDeviceId, it.name) }
                },
                enabled = targetCar != null && preset.isZeekrCompatible,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Send to vehicle USB", "发送到车机 USB")) }
            Spacer(Modifier.height(6.dp))
            Text(
                t(
                    "USB safety: only the chosen sound folder is managed. Same-name files are backed up; temporary and final files are reread and verified. Android, LOST.DIR, recordings and unknown folders are untouched.",
                    "USB 安全规则：只管理所选音效目录；同名文件先备份，临时文件和最终文件都会重新读取校验。Android、LOST.DIR、录像及未知目录均不会被触碰。",
                ),
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
