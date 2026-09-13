package com.dante.zeekrcapabilitylab.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import com.dante.zeekrbridge.sound.*
import com.dante.zeekrcapabilitylab.transfer.PhoneSoundRelay
import com.dante.zeekrcapabilitylab.transfer.SoundRelayTask
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.BuildConfig
import io.github.dantenothing.avmtransfer.protocol.SoundOfferStates
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class VehicleSoundSource(val audio: DecodedSound, val waveform: WaveformPeaks)
internal enum class VehicleSoundPhase { IDLE, IMPORTING, PREPARING, SAVING, READY }

/** Captures immutable inputs for each job; retired jobs cannot publish into a new edit. */
internal class VehicleSoundController(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val installer = VehicleSoundInstallStore(this.context)
    var source by mutableStateOf<VehicleSoundSource?>(null); private set
    var edit by mutableStateOf(VehicleSoundEdit(0, 0)); private set
    var phase by mutableStateOf(VehicleSoundPhase.IDLE); private set
    var status by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(0f); private set
    var playing by mutableStateOf(false); private set
    var previewPositionMs by mutableLongStateOf(0); private set
    var pending by mutableStateOf<SoundRelayTask?>(null); private set
    val busy get() = phase in setOf(VehicleSoundPhase.IMPORTING, VehicleSoundPhase.PREPARING, VehicleSoundPhase.SAVING)
    private var cancellation = SoundCancellation()
    private var generation = 0L
    private var closed = false
    private var visible = true
    private var lastFailure: String? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var previewFile: File? = null
    private var focus: AudioFocusRequest? = null

    init { scope.launch { pending = withContext(Dispatchers.IO) { installer.pending() } } }

    fun message(value: String) { status = value }

    fun diagnosticJson(): String = buildJsonObject {
        put("schemaVersion", 1); put("format", "OPENAVM_SOUND_MAKER_DIAGNOSTIC")
        put("exportedAtEpochMs", System.currentTimeMillis()); put("build", BuildConfig.VERSION_NAME)
        put("sdk", Build.VERSION.SDK_INT); put("model", Build.MODEL)
        put("phase", phase.name); put("status", status); put("errorDetail", lastFailure)
        put("source", source?.let { item -> buildJsonObject {
            put("name", item.audio.name); put("mime", item.audio.mimeType)
            put("sampleRate", item.audio.meta.sampleRate); put("channels", item.audio.meta.channels)
            put("frames", item.audio.meta.frameCount); put("durationMs", item.audio.meta.durationMs)
        } } ?: JsonNull)
        putJsonObject("edit") {
            put("startFrame", edit.startFrame); put("endFrame", edit.endFrame)
            put("volumePercent", edit.volumePercent); put("normalize", edit.normalize)
            put("fadeInMs", edit.fadeInMs); put("fadeOutMs", edit.fadeOutMs)
        }
        put("pendingInstall", pending?.let { task -> buildJsonObject {
            put("fileName", task.offer.fileName); put("state", task.state); put("errorCode", task.errorCode)
            put("storageUuid", task.boundStorageUuid); put("message", task.message)
        } } ?: JsonNull)
        put("mediaIncluded", false)
    }.toString()

    fun import(uri: Uri) {
        if (busy || closed) return
        stopPreview()
        launch(VehicleSoundPhase.IMPORTING, text("Extracting audio…", "正在提取音轨…")) { id, token ->
            var decoded: DecodedSound? = null
            var lastProgress = 0L
            try {
                val ready = withContext(Dispatchers.IO) {
                    val audio = SoundDecoder.decode(context, uri, token, SoundDecodeLimits.VEHICLE) { value ->
                        val now = System.nanoTime()
                        if (now - lastProgress > 200_000_000L) {
                            lastProgress = now
                            scope.launch { if (current(id)) progress = value }
                        }
                    }.also { decoded = it }
                    token.check()
                    VehicleSoundSource(audio, WaveformBuilder.build(audio.pcmFile, audio.meta, 2048) { token.cancelled })
                }
                if (current(id)) {
                    val old = source
                    source = ready
                    edit = VehicleSoundEdit.defaults(ready.audio.meta)
                    old?.audio?.pcmFile?.delete()
                    status = text("Choose a short section and preview it.", "已导入，选好片段后试听即可。")
                }
            } finally {
                decoded?.pcmFile?.takeIf { it != source?.audio?.pcmFile }?.delete()
            }
        }
    }

    fun update(value: VehicleSoundEdit) {
        val meta = source?.audio?.meta ?: return
        if (busy) return
        if (value.startFrame < 0 || value.endFrame > meta.frameCount || value.startFrame >= value.endFrame) return
        stopPreview()
        val duration = (value.endFrame - value.startFrame) * 1000 / meta.sampleRate
        val fadeIn = value.fadeInMs.coerceIn(0, duration)
        edit = value.copy(fadeInMs = fadeIn, fadeOutMs = value.fadeOutMs.coerceIn(0, duration - fadeIn))
    }

    fun setSeconds(start: String, end: String) {
        val meta = source?.audio?.meta ?: return
        val from = start.toDoubleOrNull()?.let { VehicleSoundEdit.secondsToFrame(it, meta) }
        val to = end.toDoubleOrNull()?.let { VehicleSoundEdit.secondsToFrame(it, meta) }
        if (from == null || to == null || from >= to) {
            status = text("Enter a valid start and end time within the source.", "请输入素材范围内的开始、结束秒数，结束需晚于开始。")
        } else { update(edit.copy(startFrame = from, endFrame = to)); status = "" }
    }

    fun preview() {
        if (playing || player != null) { stopPreview(); return }
        val audio = source?.audio ?: return
        val settings = edit
        if (!canRender(audio.meta, settings) || busy) return
        launch(VehicleSoundPhase.PREPARING, text("Preparing the edited preview…", "正在准备处理后的试听…")) { id, token ->
            val output = temporary()
            try {
                withContext(Dispatchers.IO) { render(audio, settings, token, output) }
                if (current(id)) {
                    startPreview(output, settings.loop, id)
                    status = text("Preview includes volume, normalization and fades.", "试听已包含音量、响度统一和淡入淡出效果。")
                }
            } finally { if (previewFile != output) output.delete() }
        }
    }

    fun saveToUsb(rawName: String, purpose: SoundPurpose, storageUuid: String) {
        val audio = source?.audio ?: return
        val settings = edit
        if (!canRender(audio.meta, settings) || busy) return
        stopPreview()
        launch(VehicleSoundPhase.PREPARING, text("Preparing WAV…", "正在生成 WAV…")) { id, token ->
            val output = temporary()
            try {
                withContext(Dispatchers.IO) { render(audio, settings, token, output) }
                token.check()
                if (current(id)) { phase = VehicleSoundPhase.SAVING; status = text("Saving and verifying USB…", "正在写入并校验 U 盘…") }
                // Once USB commit begins, finish its journal/rollback even if the Activity closes.
                val result = withContext(NonCancellable + Dispatchers.IO) { installer.install(output, rawName, purpose, storageUuid) }
                if (current(id)) showInstallResult(result)
            } finally { output.delete() }
        }
    }

    fun saveDocument(uri: Uri) {
        val audio = source?.audio ?: return
        val settings = edit
        if (!canRender(audio.meta, settings) || busy) return
        stopPreview()
        launch(VehicleSoundPhase.PREPARING, text("Preparing WAV…", "正在生成 WAV…")) { id, token ->
            val output = temporary()
            try {
                withContext(Dispatchers.IO) { render(audio, settings, token, output) }
                token.check()
                if (current(id)) phase = VehicleSoundPhase.SAVING
                withContext(NonCancellable + Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out ->
                        output.inputStream().use { it.copyTo(out, 64 * 1024) }; out.flush()
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= output.length()) { "VERIFY_FAILED" }; digest.update(buffer, 0, n) }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(bytes == output.length() && hash == PhoneSoundRelay.sha256(output)) { "VERIFY_FAILED" }
                }
                if (current(id)) status = text("WAV saved and verified.", "WAV 已保存，重新读取校验通过。")
            } finally { output.delete() }
        }
    }

    fun retryInstall() {
        if (busy) return
        launch(VehicleSoundPhase.SAVING, text("Retrying the saved USB operation…", "正在继续上次 U 盘写入…")) { id, _ ->
            val result = withContext(NonCancellable + Dispatchers.IO) { installer.retry() }
            if (current(id)) showInstallResult(result)
        }
    }

    fun discardInstall() {
        if (busy) return
        launch(VehicleSoundPhase.SAVING, text("Clearing retry data…", "正在清除重试数据…")) { id, _ ->
            withContext(NonCancellable + Dispatchers.IO) { installer.discardPending() }
            if (current(id)) { pending = null; status = text("Retry cancelled. Existing USB sounds were kept.", "已放弃重试，U 盘现有音效文件保留。") }
        }
    }

    private fun showInstallResult(task: SoundRelayTask) {
        pending = task.takeIf { it.state !in SoundOfferStates.terminal }
        status = if (task.state == SoundOfferStates.COMPLETED)
            text("Saved {0} to both USB sound folders. Select it in vehicle settings.", "已将 {0} 写入 U 盘两个铃声目录并校验。请到原车设置选择使用。", task.offer.fileName)
        else errorText(IllegalStateException(task.errorCode ?: task.message ?: "USB_WRITE_FAILED"))
    }

    private fun canRender(meta: PcmMeta, value: VehicleSoundEdit): Boolean {
        if (value.validFor(meta)) return true
        status = text("Shorten the selection: the WAV must stay under 1 MB. About 5 seconds is recommended.",
            "请缩短选区：最终 WAV 必须小于 1 MB，建议约 5 秒。")
        return false
    }

    private fun temporary(): File = File(File(context.cacheDir, "sound-maker-render").apply { mkdirs() }, "${UUID.randomUUID()}.wav")

    private fun render(audio: DecodedSound, value: VehicleSoundEdit, token: SoundCancellation, output: File) {
        token.check()
        FilePcmInput(audio.pcmFile, audio.meta.sampleRate, audio.meta.channels, audio.meta.frameCount).use {
            PcmProcessor.process(it, value.parameters(), cancel = { token.cancelled }, out = output)
        }
        require(output.length() == value.outputBytes(audio.meta.sampleRate)) { "VERIFY_FAILED" }
    }

    private fun launch(next: VehicleSoundPhase, message: String, work: suspend (Long, SoundCancellation) -> Unit) {
        if (busy || closed) return
        val id = ++generation
        val token = SoundCancellation().also { cancellation = it }
        phase = next; status = message; progress = 0f; lastFailure = null
        job = scope.launch {
            try { work(id, token) }
            catch (_: CancellationException) { }
            catch (_: SoundCancelledException) { if (current(id)) status = text("Cancelled", "已取消") }
            catch (t: Exception) { if (current(id)) {
                lastFailure = generateSequence(t as Throwable) { it.cause }.take(3)
                    .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.take(3000)
                status = errorText(t)
            } }
            finally { if (current(id)) { phase = if (source == null) VehicleSoundPhase.IDLE else VehicleSoundPhase.READY; progress = 0f } }
        }
    }

    fun cancel() {
        if (phase == VehicleSoundPhase.SAVING) return
        generation++; cancellation.cancel(); job?.cancel(); stopPreview()
        phase = if (source == null) VehicleSoundPhase.IDLE else VehicleSoundPhase.READY
        status = text("Cancelled", "已取消")
    }

    private fun startPreview(file: File, loop: Boolean, id: Long) {
        if (!visible) return
        stopPreview()
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0) stopPreview() }.build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) error("AUDIO_FOCUS_UNAVAILABLE")
        focus = request
        val media = MediaPlayer()
        player = media; previewFile = file; previewPositionMs = 0
        try {
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.isLooping = loop
            media.setOnPreparedListener { if (current(id) && player === it) { it.start(); playing = true } }
            media.setOnCompletionListener { if (player === it) stopPreview() }
            media.setOnErrorListener { failed, _, _ -> if (player === failed) { stopPreview(); status = text("Preview failed.", "试听失败，请重新导入或换一个文件。") }; true }
            media.prepareAsync()
        } catch (t: Exception) { stopPreview(); throw t }
    }

    fun refreshPreviewPosition() { previewPositionMs = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0) }
    fun visibilityChanged(value: Boolean) { visible = value; if (!value) stopPreview() }
    fun stopPreview() {
        playing = false; previewPositionMs = 0
        player?.let { runCatching { it.release() } }; player = null
        focus?.let { runCatching { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }; focus = null
        previewFile?.delete(); previewFile = null
    }

    fun release() {
        closed = true; generation++; cancellation.cancel(); job?.cancel(); stopPreview()
        val file = source?.audio?.pcmFile
        source = null
        if (job?.isCompleted == false) job?.invokeOnCompletion { file?.delete() } else file?.delete()
    }

    private fun current(id: Long) = !closed && generation == id
    private fun text(en: String, zh: String, vararg args: Any?) = Utils.t(en, zh, *args)
    private fun errorText(t: Exception): String {
        val code = when (t) { is SoundInputException -> t.code; is SoundIoException -> t.code; else -> t.message }
        return when (code) {
            "NO_AUDIO_TRACK" -> text("This file has no usable audio track.", "这个文件没有可用的声音轨道。")
            "NO_DECODER", "PCM_FORMAT_UNSUPPORTED" -> text("The vehicle cannot decode this audio format. Try another file.", "车机不支持这段声音的解码格式，请换一个文件。")
            "SOURCE_UNREADABLE" -> text("Choose the downloaded file again; its permission may have expired.", "无法读取文件，请重新选择已下载的素材。")
            "SOURCE_TOO_LONG" -> text("Use a shorter source (up to 30 minutes).", "素材过长或解码后过大，请先换成较短片段（最长 30 分钟）。")
            "PROTECTED_AUDIO" -> text("Protected audio cannot be imported.", "受保护的音轨无法导入，请选择普通本地文件。")
            "SPACE_LOCAL" -> text("Free some vehicle storage before importing.", "车机存储空间不足，请释放空间后重试。")
            "ORIGINAL_USB_NOT_MOUNTED" -> text("Connect the same USB drive and try again.", "请插入原来选择的 U 盘后重试。")
            "PENDING_INSTALL" -> text("Retry or discard the previous unfinished install first.", "请先继续或放弃上次未完成的 U 盘写入。")
            "SOUND_FOLDER_FULL" -> text("The sound folder already contains 5 WAV files. Manage them in the toolbox.", "铃声目录已有 5 个 WAV，请先回工具箱管理已有音效。")
            "FILE_NAME_CONFLICT" -> text("A sound with that name appeared. Existing files were preserved.", "出现同名文件，已有音效已保留，请换一个名称。")
            "AUDIO_FOCUS_UNAVAILABLE" -> text("Audio is busy. Try previewing later.", "声音播放正被占用，请稍后试听。")
            else -> text("Could not finish: {0}", "未能完成：{0}", code.orEmpty())
        }
    }
}
