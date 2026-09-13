package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.canary.CanaryTextReportPart
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.usbexport.UsbExportRepository
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class GuardCopyReport(val fullText: String?, val parts: List<String>)

object GuardDiagnostics {
    private val compactJson = Json(GuardEventStore.json) { prettyPrint = false }

    /** Preserve every JSON value; small reports can be pasted as one complete document. */
    fun copyReport(text: String): GuardCopyReport {
        require(text.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
        val report = compactJson.parseToJsonElement(text).jsonObject
        require(report["format"]?.jsonPrimitive?.content == "OPENAVM_SENTRY_INTEGRATED_REPORT")
        val compact = compactJson.encodeToString(report)
        val fullText = compact.takeIf { it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
        return GuardCopyReport(fullText, parts(compact))
    }

    fun file(context: Context) = File(context.filesDir, "sentry/guard-copy.json")
    fun prepare(context: Context): GuardCopyReport {
        val state = SentryGuardService.state.value
        val events = GuardEventStore(context).list()
        EventLogger.flushBlocking(1_000)
        val history = runCatching { GuardDiagnosticHistory.collect(File(context.filesDir, "events"),
            EventLogger.events.value, System.currentTimeMillis()) }.getOrElse {
            GuardDiagnosticHistorySnapshot(readError = it.javaClass.simpleName)
        }
        val recorderUsb = runCatching {
            val original = GuardEventStore.json.parseToJsonElement(UsbFastTrackReportStore.reportJson(context)).jsonObject
            buildJsonObject {
                original.filterKeys { it != "events" }.forEach { (key, value) -> put(key, value) }
                val items = original["events"]?.jsonArray.orEmpty()
                put("eventCount", items.size)
                put("events", JsonArray(items.takeLast(24)))
            }
        }.getOrElse { buildJsonObject { put("readError", it.javaClass.simpleName) } }
        val report = buildJsonObject {
            put("schemaVersion", 1); put("format", "OPENAVM_SENTRY_INTEGRATED_REPORT")
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("exportBuild", BuildConfig.VERSION_NAME)
            put("exportVersionCode", BuildConfig.VERSION_CODE)
            put("sentryCaptureEnabled", BuildConfig.SENTRY_CAPTURE_ENABLED)
            put("current", GuardEventStore.json.encodeToJsonElement(state))
            put("recorderAtExport", GuardEventStore.json.encodeToJsonElement(
                GuardNormalEvidence.from(CameraRecordingService.state.value, System.currentTimeMillis())))
            put("recorderHistory", GuardEventStore.json.encodeToJsonElement(history))
            com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.initialize(context)
            put("cameraLifecycle", com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.snapshot())
            put("recorderUsb", recorderUsb)
            put("recentRuns", GuardEventStore.json.encodeToJsonElement(GuardReportStore(context).recent(state.runId)))
            put("eventCount", events.size)
            put("eventDetailsIncluded", minOf(events.size, 16))
            put("events", GuardEventStore.json.encodeToJsonElement(events.take(16).map { event ->
                event.copy(triggers = event.triggers.takeLast(4), omittedTriggers = event.omittedTriggers + (event.triggers.size - 4).coerceAtLeast(0))
            }))
            put("usb", GuardEventStore.json.encodeToJsonElement(UsbExportRepository.tasks.value.filter { task ->
                events.any { it.id == task.logicalId }
            }.takeLast(24).map { it.copy(sources = emptyList()) }))
            put("modelAcceptance", "TRIAL_ONLY_PENDING_VEHICLE_ACCURACY_TEST")
            put("videoIncluded", false)
        }
        val text = compactJson.encodeToString(report)
        require(text.toByteArray().size <= 4 * 1024 * 1024)
        val target = AtomicFile(file(context).apply { parentFile?.mkdirs() })
        val out = target.startWrite()
        try { out.write(text.toByteArray()); target.finishWrite(out) } catch (t: Throwable) { target.failWrite(out); throw t }
        return copyReport(text)
    }
    fun reopen(context: Context): GuardCopyReport {
        val file = file(context)
        return copyReport(AtomicFile(file).openRead().use { stream ->
            require(stream.channel.size() in 1..4 * 1024 * 1024)
            stream.bufferedReader().readText()
        })
    }
    /** Small code-unit chunks also bound worst-case JSON escaping, including supplementary Unicode. */
    fun parts(text: String): List<String> {
        val report = GuardEventStore.json.parseToJsonElement(text).jsonObject
        require(report["format"]?.jsonPrimitive?.content == "OPENAVM_SENTRY_INTEGRATED_REPORT")
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 16_000, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            chunks += text.substring(start, end); start = end
        }
        return chunks.mapIndexed { i, chunk -> GuardEventStore.json.encodeToString(CanaryTextReportPart(
            reportSha256 = hash, reportUtf8Bytes = bytes.size, partNumber = i + 1, partCount = chunks.size, jsonText = chunk,
        )).also { check(it.toByteArray().size <= 128 * 1024) } }
    }
}
