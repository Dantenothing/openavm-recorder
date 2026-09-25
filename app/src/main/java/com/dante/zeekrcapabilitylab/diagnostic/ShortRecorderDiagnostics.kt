package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCaptureEvidence
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

object ShortRecorderDiagnostics {
    @Volatile private var lastFault: JsonObject? = null
    @Volatile private var lastRun: JsonObject? = null
    private val pendingWrite = AtomicBoolean()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "recorder-fault-summary").apply { isDaemon = true } }
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "diagnostics/last-recorder-fault.json"))
    private fun runFile(context: Context) = AtomicFile(File(context.filesDir, "diagnostics/last-recorder-run.json"))
    private fun snapshot(state: RecorderState) = ShortRecorderReport.snapshot(state, RecorderCaptureEvidence.latest,
        CaptureCleanupRuntime.snapshot(), EventLogger.events.value, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.evidence)

    /** Freeze before returning to the UI can replace the visible state. No file IO on camera callbacks. */
    fun recordFault(context: Context, state: RecorderState) {
        if (state.lastError.isNullOrBlank()) return
        saveFault(context, state)
    }

    /** A display failure must be preserved without inventing a recorder/camera error. */
    fun recordPreviewFault(context: Context, state: RecorderState) = saveFault(context, state)

    /** Preserve the actual backend after Stop resets the service to its idle defaults. */
    fun recordRun(context: Context, state: RecorderState) {
        if (state.encoderSelection == null) return
        val fields = setOf("atEpochMs", "atElapsedMs", "status", "recordingMode", "segmentNumber", "storage",
            "recordingBackend", "encoderSelection", "captureSessionRevision", "cameraGeneration",
            "nativeFileSwitches", "nativePendingFiles", "requested")
        val saved = buildJsonObject {
            snapshot(state).filterKeys { it in fields }.forEach { (key, value) -> put(key, value) }
            put("capturedVersion", BuildConfig.VERSION_NAME)
            put("capturedVersionCode", BuildConfig.VERSION_CODE)
        }
        lastRun = saved
        writer.execute {
            runCatching {
                val target = runFile(context)
                target.baseFile.parentFile?.mkdirs()
                val out = target.startWrite()
                try { out.write(saved.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(out) }
                catch (error: Throwable) { target.failWrite(out); throw error }
            }
        }
    }

    private fun saveFault(context: Context, state: RecorderState) {
        lastFault = buildJsonObject {
            snapshot(state).forEach { (key, value) -> put(key, value) }
            put("capturedVersion", BuildConfig.VERSION_NAME)
            put("capturedVersionCode", BuildConfig.VERSION_CODE)
            put("processStartId", com.dante.zeekrcapabilitylab.ZeekrApp.processStartId)
        }
        if (!pendingWrite.compareAndSet(false, true)) return
        writer.execute {
            try {
                val saved = lastFault ?: return@execute
                val target = file(context)
                target.baseFile.parentFile?.mkdirs()
                val out = target.startWrite()
                try { out.write(saved.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(out) }
                catch (error: Throwable) { target.failWrite(out); throw error }
            } catch (_: Exception) {
                // The in-process snapshot survives a failed diagnostic write.
            } finally { pendingWrite.set(false) }
        }
    }

    /** Existing saved snapshot only. Call on IO; no camera probe or new fault capture. */
    internal fun loadLastFault(context: Context): JsonObject? = lastFault ?: runCatching {
            file(context).openRead().use {
                require(it.channel.size() in 1..(ShortRecorderReport.MAX_BYTES / 2).toLong())
                Json.parseToJsonElement(it.bufferedReader(Charsets.UTF_8).readText()).jsonObject
            }
        }.getOrNull()
    /** Called on IO by the diagnostics dialog. Uses snapshots only, with no new camera probe. */
    fun collect(context: Context): String {
        CaptureCleanupRuntime.initialize(context)
        val fault = loadLastFault(context)
        val run = lastRun ?: runCatching {
            runFile(context).openRead().use {
                require(it.channel.size() in 1..8192)
                Json.parseToJsonElement(it.bufferedReader(Charsets.UTF_8).readText()).jsonObject
            }
        }.getOrNull()
        val current = buildJsonObject {
            snapshot(CameraRecordingService.state.value).forEach { (key, value) -> put(key, value) }
            put("nativePublication", buildJsonObject {
                val publication = com.dante.zeekrcapabilitylab.service.recorder.NativeUsbPublication
                put("pending", publication.pending.get())
                put("completedThisProcess", publication.completed.get())
                put("retainedThisProcess", publication.failed.get())
            })
        }
        return ShortRecorderReport.report(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
            current, fault, run, ProcessExitDiagnostics.collect(context))
    }
}
