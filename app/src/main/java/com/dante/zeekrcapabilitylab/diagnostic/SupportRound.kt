package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime
import com.dante.zeekrcapabilitylab.preflight.*
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticUpload
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import java.util.UUID

/** A new evidence boundary, not a reset of live resource ownership or recording data. */
internal object SupportRound {
    data class Boundary(val id: String, val process: String, val elapsed: Long, val wall: Long)
    @Volatile private var boundary: Boundary? = null
    fun begin(): Boundary = Boundary(UUID.randomUUID().toString(), ZeekrApp.processStartId,
        SystemClock.elapsedRealtime(), System.currentTimeMillis()).also { boundary = it }
    fun current(): Boundary = boundary ?: begin()
    private val allowed = setOf("recordingSessionId", "channelId", "errorCode", "reason", "status", "authorityReason",
        "closeId", "closeStep", "safeToContinue", "deviceClosed", "cameraId", "cameraGeneration", "generation",
        "captureFrames", "captureFailures", "captureFailureReason", "failureImageCaptured", "requestTemplate",
        "previewOwner", "frames", "inputFrames", "bufferWidth", "bufferHeight", "encodedFrames", "writtenFrames",
        "recordingBackend", "actualWidth", "actualHeight", "width", "height", "bitrateBps", "requestedFrameRate",
        "trialId", "initializationOrder", "surfaceValid", "controlMode", "captureIntent", "aeMode", "afMode", "awbMode",
        "videoStabilizationMode", "aeFpsMin", "aeFpsMax", "deviceOwnerId", "producerEvidence",
        "requestedCloseOrder", "effectiveCloseOrder", "stepElapsedMs", "closeRequestedElapsedMs", "finalizeReason",
        "connection", "nativeDevice")
    fun reportSample(phase: String, elapsed: Long): JsonObject {
        val state = CameraRecordingService.state.value
        val mirror = MirrorPreviewRuntime.evidence
        return obj("phase" to phase, "relativeElapsedMs" to elapsed, "recorderStatus" to state.status,
            "sessionCorrelation" to ProcessExitReport.sessionCorrelation(state.recordingSessionId),
            "lastErrorCode" to safe(state.lastError), "previewFrames" to mirror.frames,
            "previewFrameAgeMs" to mirror.lastFrameAgeMs, "cleanupOwners" to CaptureCleanupRuntime.pendingOwners.value,
            "channels" to RecordingChannels.state.value.map { c -> obj("channelId" to c.channelId,
                "status" to c.status, "capturedFrames" to c.capturedFrames, "encodedFrames" to c.encodedFrames,
                "writtenFrames" to c.writtenFrames, "completedFiles" to c.completedFiles,
                "cleanupPending" to c.cleanupPending, "cleanupUnconfirmed" to c.cleanupUnconfirmed, "errorCode" to safe(c.errorCode)) })
    }
    private fun safe(text: String?): String? = text?.takeWhile { it.isLetterOrDigit() || it == '_' }?.take(80)
    /** IO only. Old context is explicitly separated from this round's events and verdict. */
    @Synchronized fun save(context: Context, run: String = UUID.randomUUID().toString(), outcome: String = "NOT_RUN",
             cleanup: String = "NOT_APPLICABLE", reason: String = "READ_ONLY_SNAPSHOT", samples: List<JsonObject> = emptyList(),
             sessions: Set<String> = emptySet(), experiment: String? = null): JsonObject {
        val start = current()
        val all = EventLogger.events.value.filter { it.elapsedRealtimeMs >= start.elapsed &&
            (it.eventName.startsWith("RECORDER_") || it.eventName.startsWith("MIRROR_PREVIEW_") || it.eventName.startsWith("CAMERA_CONNECTION_")) }
        val events = all.takeLast(500).map { e -> obj("event" to e.eventName, "relativeElapsedMs" to e.elapsedRealtimeMs - start.elapsed,
            "facts" to e.payload.filterKeys { it in allowed }.mapValues { (k, v) ->
                if (k == "recordingSessionId") ProcessExitReport.sessionCorrelation(v) else
                    v.takeIf { it.length <= 100 && it.all { c -> c.isLetterOrDigit() || c in "_.-" } } ?: "REDACTED" }) }
        val report = obj("schemaVersion" to 1, "format" to "OPENAVM_PREFLIGHT", "exampleOnly" to false,
            "productionSwitchAllowed" to false, "runId" to run, "version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE, "startEpochMs" to start.wall, "phase" to "PRODUCT_SUPPORT",
            "reason" to reason, "cleanup" to cleanup, "experiment" to experiment,
            "tests" to if (outcome == "NOT_RUN") emptyList<JsonObject>() else listOf(PreflightReport.test("product_lifecycle",
                if (outcome == "INCONCLUSIVE") "INCOMPLETE" else outcome, reason, "PRODUCT_RUNTIME_COUNTERS",
                actualRoute = "EXISTING_PRODUCT_CONTROLS", cleanup = cleanup)),
            "round" to obj("id" to start.id, "process" to start.process, "startElapsedMs" to start.elapsed,
                "recordingSessions" to sessions.map(ProcessExitReport::sessionCorrelation), "samples" to samples,
                "events" to events, "eventsOmitted" to (all.size - events.size)),
            "historicalContextScope" to "EXISTING_SAVED_CONTEXT_MAY_PREDATE_THIS_ROUND",
            "support" to Json.parseToJsonElement(RecorderSupportReport.collect(context)),
            "videoIncluded" to false, "coordinatesIncluded" to false, "accountDataIncluded" to false)
        val store = PreflightStore(context)
        store.read()?.let { store.write("previous.json", it) }
        store.write("latest-attempt.json", obj("runId" to run))
        store.save(report)
        PreflightRuntime.report = report
        DiagnosticUpload.finished(context, report.toString().toByteArray())
        return report
    }
}
