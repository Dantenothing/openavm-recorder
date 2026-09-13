package com.dante.zeekrbridge.sound

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.WavValidator
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.ui.PhoneLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class SoundPhase {
    Idle,
    Importing,
    Waveform,
    Ready,
    Exporting,
    Verifying,
    Done,
    Error,
}

enum class SoundCompletionAction {
    CONTINUE_CURRENT,
    START_ANOTHER,
}

object SoundEditorFlow {
    fun afterCompletion(action: SoundCompletionAction, hasImportedAudio: Boolean): SoundPhase =
        when (action) {
            SoundCompletionAction.CONTINUE_CURRENT -> if (hasImportedAudio) SoundPhase.Ready else SoundPhase.Idle
            SoundCompletionAction.START_ANOTHER -> SoundPhase.Idle
        }
}

data class ImportedSound(
    val name: String,
    val formatLabel: String,
    val uri: Uri,
    val durationMs: Long,
    val pcmFile: File,
    val meta: PcmMeta,
    val waveform: WaveformPeaks,
)

data class EditSettings(
    val startFrame: Long = 0L,
    val endFrame: Long = 0L,
    val volumePercent: Int = 100,
    val normalize: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val outputChannels: Int = 1,
    val loopPreview: Boolean = false,
) {
    companion object {
        fun defaults(totalFrames: Long): EditSettings =
            EditSettings(startFrame = 0L, endFrame = totalFrames)
    }
}

data class ExportResult(
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val target: String,
    val savedFile: File?,
    val verified: Boolean,
    val backupName: String? = null,
    val message: String = "",
)

/**
 * State holder for the sound maker. All heavy work runs on Dispatchers.IO with
 * bounded buffers; a fresh [SoundCancellation] supports cancel at any stage.
 */
class SoundEditorController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var phase by mutableStateOf(SoundPhase.Idle)
        private set
    var statusText by mutableStateOf("")
        private set
    var progressText by mutableStateOf("")
        private set
    var imported by mutableStateOf<ImportedSound?>(null)
        private set
    var edit by mutableStateOf(EditSettings())
        private set
    var exportResult by mutableStateOf<ExportResult?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var playPositionFrame by mutableStateOf(0L)
        private set

    private var cancellation = SoundCancellation()
    private var job: Job? = null
    private var player: SelectionPlayer? = null

    fun import(uri: Uri) {
        stopPlayback()
        cancellation = SoundCancellation()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        phase = SoundPhase.Importing
        statusText = text("Analyzing…", "正在分析…")
        progressText = ""
        exportResult = null
        job = scope.launch {
            try {
                ensureCacheSpace(16L * 1024 * 1024)
                val decoded = withContext(Dispatchers.IO) {
                    SoundDecoder.decode(context, uri, cancellation) { p ->
                        progressText = text("Analyzing {0}%…", "正在分析 {0}%…", (p * 100).toInt())
                    }
                }
                phase = SoundPhase.Waveform
                statusText = text("Building waveform…", "正在生成波形…")
                val waveform = withContext(Dispatchers.IO) {
                    WaveformBuilder.build(decoded.pcmFile, decoded.meta)
                }
                imported?.pcmFile?.delete()
                imported = ImportedSound(
                    name = decoded.name,
                    formatLabel = decoded.formatLabel,
                    uri = decoded.uri,
                    durationMs = decoded.meta.durationMs,
                    pcmFile = decoded.pcmFile,
                    meta = decoded.meta,
                    waveform = waveform,
                )
                edit = EditSettings.defaults(decoded.meta.frameCount)
                phase = SoundPhase.Ready
                statusText = text(
                    "Imported {0} ({1}, {2})", "已导入 {0}（{1}，{2}）", decoded.name, decoded.formatLabel, formatDuration(decoded.meta.durationMs))
                progressText = ""
            } catch (t: SoundCancelledException) {
                phase = SoundPhase.Idle
                statusText = text("Import cancelled", "已取消导入")
            } catch (t: Throwable) {
                phase = SoundPhase.Error
                statusText = errorMessage(t)
            }
        }
    }

    fun updateEdit(new: EditSettings) {
        stopPlayback()
        edit = new
        exportResult = null
    }

    fun resetEdit() {
        val imp = imported ?: return
        stopPlayback()
        edit = EditSettings.defaults(imp.meta.frameCount)
        exportResult = null
        statusText = text("Default settings restored", "已恢复默认设置")
    }

    fun continueEditing() {
        stopPlayback()
        exportResult = null
        phase = SoundEditorFlow.afterCompletion(
            SoundCompletionAction.CONTINUE_CURRENT,
            hasImportedAudio = imported != null,
        )
        statusText = if (phase == SoundPhase.Ready) {
            text("Ready to continue editing", "可以继续编辑")
        } else {
            text("Choose an audio file", "请选择音频文件")
        }
    }

    fun startAnotherSound() {
        stopPlayback()
        cancellation.cancel()
        job?.cancel()
        job = null
        imported?.pcmFile?.delete()
        imported = null
        edit = EditSettings()
        exportResult = null
        phase = SoundEditorFlow.afterCompletion(
            SoundCompletionAction.START_ANOTHER,
            hasImportedAudio = false,
        )
        statusText = text("Choose the audio for the next sound", "请选择下一段音效的音频")
        progressText = ""
    }

    fun togglePlayback() {
        val imp = imported ?: return
        if (playing) {
            player?.pause()
            playing = false
            return
        }
        if (edit.startFrame >= edit.endFrame) {
            statusText = text("The selection is empty or out of range.", "选区为空或超出范围，请调整开始/结束位置")
            return
        }
        val p = player ?: SelectionPlayer(imp.pcmFile, imp.meta).also { player = it }
        p.startFrame = edit.startFrame
        p.endFrame = edit.endFrame
        p.loop = edit.loopPreview
        p.volume = (edit.volumePercent / 100f).coerceIn(0f, 1f)
        p.onComplete = { playing = false }
        p.onError = { msg -> statusText = msg }
        playing = true
        p.play()
    }

    fun seekPlayback(frame: Long) {
        val imp = imported ?: return
        val target = frame.coerceIn(edit.startFrame, maxOf(edit.startFrame, edit.endFrame - 1L))
        playPositionFrame = target
        player?.seekTo(target)
        if (!playing) playPositionFrame = target
    }

    fun currentPlayFrame(): Long = player?.currentFrame ?: playPositionFrame

    fun refreshPlayPosition() {
        playPositionFrame = player?.currentFrame ?: playPositionFrame
    }

    fun stopPlayback() {
        playing = false
        player?.release()
        player = null
    }

    fun exportToPhone(fileName: String) {
        startExport(fileName) { converted, name ->
            val dir = File(context.filesDir, "sounds")
            val backupDir = File(context.filesDir, "sound-backups")
            val saved = LocalWavSaver(dir, backupDir).save(converted, name)
            ExportResult(
                fileName = saved.file.name,
                sizeBytes = saved.file.length(),
                durationMs = durationOf(converted),
                target = text("Phone storage", "手机存储"),
                savedFile = saved.file,
                verified = true,
                backupName = saved.backupName,
                message = text("Saved to phone: {0}", "已保存到手机：{0}", saved.file.absolutePath),
            )
        }
    }

    fun exportToDocument(uri: Uri, fileName: String) {
        startExport(fileName) { converted, name ->
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "w")
                    ?: throw SoundIoException("无法打开保存位置，请重试", "SOURCE_UNREADABLE")
                out.use { stream ->
                    converted.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read > 0) stream.write(buf, 0, read)
                        }
                    }
                    stream.flush()
                }
                val ok = UsbSaf.verifyStream(
                    context,
                    uri,
                    converted.length(),
                    UsbSaf.sha256(converted),
                )
                if (!ok) throw SoundIoException("保存后校验失败，未报告成功", "VERIFY_FAILED")
                ExportResult(
                    fileName = name,
                    sizeBytes = converted.length(),
                    durationMs = durationOf(converted),
                    target = text("Document provider", "系统文档"),
                    savedFile = null,
                    verified = true,
                    message = text("Saved to the selected location", "已通过系统文档保存到所选位置"),
                )
            }
        }
    }

    fun exportToUsb(treeUri: Uri, fileName: String, preset: ZeekrSoundPreset) {
        startExport(fileName) { converted, name ->
            val size = converted.length()
            if (!preset.acceptsSize(size)) {
                throw SoundInputException(
                    text(
                        "The {0} WAV is too large for the Zeekr 7X preset (must be under 1 MB). Shorten the selection or use Mono.", "导出 WAV 为 {0}，超过 Zeekr 7X 预设的限制（必须小于 1 MB）。请缩短选区或使用 Mono。", formatBytes(size)),
                    "ZEEKR_FILE_TOO_LARGE",
                )
            }
            val result = UsbWavSaver.saveToDirectories(
                context = context,
                treeUri = treeUri,
                source = converted,
                requestedName = name,
                targetDirectoryNames = preset.targetDirectoryNames,
                maxWavFiles = preset.maxWavFiles,
            )
            if (!result.ok) {
                throw SoundIoException(result.message, result.errorCode ?: "USB_WRITE_FAILED")
            }
            ExportResult(
                fileName = result.finalName ?: name,
                sizeBytes = size,
                durationMs = durationOf(converted),
                target = preset.targetDirectoryNames
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "USB ", separator = " + ") { "/$it/" }
                    ?: "USB",
                savedFile = null,
                verified = true,
                backupName = result.backupName,
                message = result.message,
            )
        }
    }

    /** Durable, authenticated phone -> selected car -> Zeekr USB sound relay. */
    fun sendToCar(
        fileName: String,
        preset: ZeekrSoundPreset,
        purpose: SoundPurpose,
        targetCarDeviceId: String,
        targetCarName: String,
    ) {
        startExport(fileName) { converted, name ->
            val size = converted.length()
            if (!preset.isZeekrCompatible || size >= 1_000_000L) {
                throw SoundInputException(
                    text(
                        "The {0} export is not valid for the Zeekr relay (must be under 1,000,000 bytes).", "导出文件 {0} 不符合极氪中继要求（必须小于 1,000,000 字节）", formatBytes(size)),
                    "TOO_LARGE_FOR_CAR",
                )
            }
            val offer = OutboundOfferStore.importStream(
                displayName = name,
                targetCarDeviceId = targetCarDeviceId,
                targetCarName = targetCarName,
                presetId = preset.name,
                purpose = purpose.name,
            ) { converted.inputStream() }
            BridgeServer.sendOfferToCar(offer)
            val connected = BridgeServer.isCarConnected(targetCarDeviceId)
            ExportResult(
                fileName = offer.fileName,
                sizeBytes = offer.sizeBytes,
                durationMs = durationOf(converted),
                target = text("Vehicle USB · {0}", "车机 USB · {0}", targetCarName),
                savedFile = null,
                verified = true,
                message = if (connected) {
                    text("Queued for {0}. The vehicle is online and will install it to both Zeekr sound folders.", "已加入 {0} 队列；车机在线，将写入极氪中英文双目录。", targetCarName)
                } else {
                    text("Queued for {0}. Start the Companion receiver and connect the vehicle later to continue.", "已为 {0} 持久排队；稍后开启接收服务并连接车机即可继续。", targetCarName)
                },
            )
        }
    }

    fun cancelCurrent() {
        cancellation.cancel()
        job?.cancel()
        stopPlayback()
        if (phase == SoundPhase.Importing || phase == SoundPhase.Waveform) {
            phase = SoundPhase.Idle
        } else if (phase == SoundPhase.Exporting || phase == SoundPhase.Verifying) {
            phase = if (imported != null) SoundPhase.Ready else SoundPhase.Idle
        }
        statusText = text("Cancelled", "已取消")
    }

    fun release() {
        stopPlayback()
        cancellation.cancel()
        job?.cancel()
        imported?.pcmFile?.delete()
        imported = null
        phase = SoundPhase.Idle
        statusText = ""
    }

    private fun startExport(
        fileName: String,
        saver: suspend (File, String) -> ExportResult,
    ) {
        val imp = imported
        if (imp == null) {
            statusText = text("Import audio first", "尚未导入音频")
            return
        }
        if (edit.startFrame >= edit.endFrame || edit.endFrame > imp.meta.frameCount) {
            statusText = text("The selection is empty or out of range.", "选区为空或超出范围，请调整开始/结束位置")
            phase = SoundPhase.Ready
            return
        }
        stopPlayback()
        cancellation = SoundCancellation()
        phase = SoundPhase.Exporting
        statusText = text("Converting…", "正在转换…")
        progressText = ""
        exportResult = null
        job = scope.launch {
            try {
                ensureCacheSpace(32L * 1024 * 1024)
                val converted = withContext(Dispatchers.IO) { convertToTemp(imp) }
                phase = SoundPhase.Verifying
                statusText = text("Verifying…", "正在校验…")
                val result = saver(converted, fileName)
                exportResult = result
                phase = SoundPhase.Done
                statusText = if (result.message.isNotBlank()) {
                    result.message
                } else {
                    text(
                        "Completed: {0} ({1}, {2})", "已完成：{0}（{1}，{2}）", result.fileName, formatBytes(result.sizeBytes), formatDuration(result.durationMs))
                }
                progressText = ""
                runCatching { converted.delete() }
            } catch (t: SoundCancelledException) {
                phase = if (imported != null) SoundPhase.Ready else SoundPhase.Idle
                statusText = text("Conversion cancelled", "转换已取消")
                progressText = ""
            } catch (t: Throwable) {
                phase = SoundPhase.Error
                statusText = errorMessage(t)
                progressText = ""
            }
        }
    }

    private fun convertToTemp(imp: ImportedSound): File {
        val dir = File(context.cacheDir, "sound-export").apply { mkdirs() }
        val out = File(dir, "converted-${UUID.randomUUID().toString()}.wav")
        FilePcmInput(imp.pcmFile, imp.meta.sampleRate, imp.meta.channels, imp.meta.frameCount).use { input ->
            val params = AudioEditParams(
                startFrame = edit.startFrame,
                endFrame = edit.endFrame,
                gain = edit.volumePercent / 100.0,
                normalize = edit.normalize,
                fadeInMs = edit.fadeInMs,
                fadeOutMs = edit.fadeOutMs,
                outputChannels = edit.outputChannels,
            )
            PcmProcessor.process(
                input = input,
                params = params,
                cancel = { cancellation.cancelled },
                progress = { done, total ->
                    progressText = if (total > 0) {
                        text("Converting {0}%…", "正在转换 {0}%…", done * 100 / total)
                    } else {
                        text("Converting…", "正在转换…")
                    }
                },
                out = out,
            )
        }
        val info = WavPcmValidator.validateWavFile(out)
        if (!info.valid) {
            runCatching { out.delete() }
            throw SoundInputException("输出校验失败：${info.reason ?: "INVALID"}", "VERIFY_FAILED")
        }
        return out
    }

    private fun durationOf(file: File): Long {
        val info = WavPcmValidator.validateWavFile(file)
        return info.durationMs
    }

    private fun ensureCacheSpace(required: Long) {
        val dir = context.cacheDir.absolutePath
        val stat = StatFs(dir)
        val available = stat.availableBytes
        if (available < required) {
            throw SoundIoException("手机存储空间不足，无法继续", "SPACE_PHONE")
        }
    }

    private fun errorMessage(t: Throwable): String {
        val code = when (t) {
            is SoundInputException -> t.code
            is SoundIoException -> t.code
            else -> null
        }
        return SoundErrors.userMessage(code, t.message ?: t.javaClass.simpleName)
    }

    private fun text(en: String, zh: String, vararg args: Any?) = PhoneLanguage.text(en, zh, *args)

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000L
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
