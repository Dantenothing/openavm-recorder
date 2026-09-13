package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardDiagnosticHistory
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardNormalEvidence
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** On-demand text export; it never starts or opens a camera, AI runtime or parking service. */
object RecorderDiagnostics {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun file(context: Context) = File(context.filesDir, "diagnostics/recorder-copy.json")

    fun prepare(context: Context): DiagnosticCopyReport {
        EventLogger.flushBlocking(1_000)
        CaptureCleanupRuntime.initialize(context)
        val report = buildJsonObject {
            put("schemaVersion", 1)
            put("format", DiagnosticCopyReport.FORMAT)
            put("exportBuild", BuildConfig.VERSION_NAME)
            put("exportVersionCode", BuildConfig.VERSION_CODE)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("recorder", json.encodeToJsonElement(GuardNormalEvidence.from(CameraRecordingService.state.value, System.currentTimeMillis())))
            put("cameraLifecycle", CaptureCleanupRuntime.snapshot())
            put("recorderHistory", json.encodeToJsonElement(GuardDiagnosticHistory.collect(
                File(context.filesDir, "events"), EventLogger.events.value, System.currentTimeMillis(),
            )))
            put("usb", runCatching { json.parseToJsonElement(UsbFastTrackReportStore.reportJson(context)) }
                .getOrElse { buildJsonObject { put("readError", it.javaClass.simpleName) } })
            put("videoIncluded", false)
            put("experimentalSentryCaptureIncluded", false)
        }
        val result = DiagnosticCopyReport.from(json.encodeToString(report))
        val target = AtomicFile(file(context).apply { parentFile?.mkdirs() })
        val output = target.startWrite()
        try {
            output.write(result.text.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
        return result
    }

    fun latest(context: Context): DiagnosticCopyReport = AtomicFile(file(context)).openRead().use {
        require(it.channel.size() in 1..DiagnosticCopyReport.MAX_BYTES)
        DiagnosticCopyReport.from(it.bufferedReader(Charsets.UTF_8).readText())
    }
}
