package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.CaptureEvidence
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import com.dante.zeekrcapabilitylab.mirror.MirrorEvidence
import kotlinx.serialization.json.*

/** Compact, path/token-free evidence; codec counters are separate from camera result callbacks. */
object ShortRecorderReport {
    const val MAX_BYTES = 48 * 1024
    private fun code(value: String?): String? = value?.takeWhile { it.isLetterOrDigit() || it in "_-" }?.take(80)

    fun snapshot(state: RecorderState, capture: CaptureEvidence, lifecycle: JsonObject,
                 events: List<ProbeEvent>, now: Long, elapsed: Long, mirror: MirrorEvidence = MirrorEvidence()): JsonObject = buildJsonObject {
        put("atEpochMs", now)
        put("atElapsedMs", elapsed)
        put("status", code(state.status))
        put("lastErrorCode", code(state.lastError))
        put("recordingMode", state.recordingMode.name)
        put("segmentNumber", state.segmentNumber)
        put("storage", state.activeStorageKind.name)
        put("previewActive", state.previewActive)
        put("previewRequested", state.previewRequested)
        put("previewFallbackUsed", state.previewFallbackUsed)
        put("cameraGeneration", state.cameraGeneration)
        put("recordingBackend", code(state.recordingBackend))
        put("nativeFileSwitches", state.nativeFileSwitches)
        put("nativePendingFiles", state.nativePendingFiles)
        put("encoderSelection", state.encoderSelection?.let { selection -> buildJsonObject {
            put("reason", code(selection.reason)); put("codecName", selection.codecName?.take(160))
            put("cameraSizeDeclared", selection.cameraSizeDeclared)
            put("queryError", code(selection.queryError)); put("omittedCandidates", selection.omittedCandidates)
            put("candidates", buildJsonArray { selection.candidates.take(8).forEach { candidate -> add(buildJsonObject {
                put("name", candidate.name.take(160)); put("surfaceInput", candidate.surfaceInput)
                put("formatSupported", candidate.formatSupported); put("sizeAndRateSupported", candidate.sizeAndRateSupported)
                put("baselineAdvertised", candidate.baselineAdvertised)
                put("maximumWidth", candidate.maximumWidth); put("maximumHeight", candidate.maximumHeight)
                put("widthAlignment", candidate.widthAlignment); put("heightAlignment", candidate.heightAlignment)
                put("queryError", code(candidate.queryError))
            }) } })
        } } ?: JsonNull)
        put("captureSessionRevision", state.captureSessionRevision)
        put("recovery", buildJsonObject {
            put("phase", state.recovery.phase.name); put("attempts", state.recovery.attemptsMade)
            put("availability", state.recovery.availability.name); put("resumeAllowed", state.recovery.resumeAllowed)
            put("waitingForAvailability", state.recovery.waitingForAvailability)
            put("occupancyDeadlineElapsedMs", state.recovery.occupancyDeadlineAtMs)
            put("reason", code(state.recovery.lastReason)); put("deadlineElapsedMs", state.recovery.deadlineAtMs)
            put("nextAttemptElapsedMs", state.recovery.nextAttemptAtMs)
            put("cleanupPending", state.cleanupPending); put("cleanupUnconfirmed", state.cleanupUnconfirmed)
        })
        put("mirror", buildJsonObject {
            put("managedRun", state.mirrorPreviewManaged); put("active", mirror.active)
            put("destination", mirror.destination); put("reason", mirror.reason)
            put("textureUpdates", mirror.frames); put("lastFrameAgeMs", mirror.lastFrameAgeMs)
            // textureUpdates is the legacy distinct-timestamp count, not the callback count.
            put("textureCallbacks", mirror.textureCallbacks)
            put("duplicateTimestampCallbacks", mirror.duplicateTimestampCallbacks)
            put("invalidTimestampCallbacks", mirror.invalidTimestampCallbacks)
            put("lastTextureCallbackAgeMs", mirror.lastTextureCallbackAgeMs)
            put("lastTextureTimestampNs", mirror.lastTextureTimestampNs)
            put("drawPasses", mirror.drawPasses); put("lastDrawAgeMs", mirror.lastDrawAgeMs)
            put("textureAttached", mirror.textureAttached); put("textureAvailable", mirror.textureAvailable)
            put("textureShown", mirror.textureShown); put("hardwareAccelerated", mirror.hardwareAccelerated)
            put("redrawAttempts", mirror.redrawAttempts); put("redrawFramesResumed", mirror.redrawFramesResumed)
            put("textureCallbackSource", "TEXTURE_VIEW_UPDATED")
            put("displayRecoveryAttempts", mirror.displayRecoveryAttempts)
            put("gl", buildJsonObject {
                val gl = mirror.gl
                put("backend", gl.backend); put("inputNotifications", gl.inputNotifications); put("inputFrames", gl.inputFrames)
                put("inputFrameAgeMs", gl.inputFrameAgeMs); put("inputHeartbeatAgeMs", gl.inputHeartbeatAgeMs)
                put("inputPollAttempts", gl.inputPollAttempts); put("inputPolledFrames", gl.inputPolledFrames)
                put("lastInputNotificationAgeMs", gl.lastInputNotificationAgeMs); put("lastInputPollAgeMs", gl.lastInputPollAgeMs)
                put("inputTimestampNs", gl.inputTimestampNs); put("droppedDisplayFrames", gl.droppedDisplayFrames)
                put("displaySubmissions", gl.displaySubmissions); put("displaySubmitAgeMs", gl.displaySubmitAgeMs)
                put("displayWorkers", gl.displayWorkers); put("poolSlots", gl.poolSlots); put("poolBytes", gl.poolBytes)
                put("inputFailure", code(gl.inputFailure)); put("displayFailure", code(gl.displayFailure))
                put("cleanupPending", gl.cleanupPending)
            })
            put("rearLane", mirror.rearLane); put("rotation", mirror.rotation); put("mirrored", mirror.mirrored)
            put("cameraGeneration", mirror.cameraGeneration)
            put("requestedWidth", mirror.requestedWidth); put("requestedHeight", mirror.requestedHeight)
            put("declaredBufferWidth", mirror.bufferWidth); put("declaredBufferHeight", mirror.bufferHeight)
            put("textureViewWidth", mirror.viewWidth); put("textureViewHeight", mirror.viewHeight)
            put("lensMode", code(mirror.lensMode)); put("zoom", mirror.zoom)
        })
        put("wakeLockHeld", state.wakeLockHeld)
        put("requested", buildJsonObject {
            put("source", state.sourceRole?.name)
            put("width", state.profile?.size?.width)
            put("height", state.profile?.size?.height)
            put("bitrateBps", state.profile?.bitrateBps)
            put("timeLapseMultiplier", state.timeLapseMultiplier)
        })
        val matching = capture.sessionId != null && capture.sessionId == state.recordingSessionId && capture.segment == state.segmentNumber
        put("previewCapture", if (!matching) JsonNull else buildJsonObject {
            val stats = capture.preview
            put("completedWithPreviewTarget", stats.completedWithPreviewTarget)
            put("completedWithoutPreviewTarget", stats.completedWithoutPreviewTarget)
            put("lastCompletedHadPreviewTarget", stats.lastCompletedHadPreviewTarget)
            put("lastPreviewResultAgeMs", stats.lastPreviewResultElapsedMs?.takeIf { it <= elapsed }?.let { elapsed - it })
            put("lastPreviewSensorTimestampNs", stats.lastPreviewSensorTimestampNs)
            put("previewBuffersLost", stats.previewBuffersLost); put("encoderBuffersLost", stats.encoderBuffersLost)
            put("otherBuffersLost", stats.otherBuffersLost)
            put("lastBufferLostAgeMs", stats.lastBufferLostElapsedMs?.takeIf { it <= elapsed }?.let { elapsed - it })
            put("captureFailures", stats.captureFailures); put("lastFailureReason", stats.lastFailureReason)
        })
        put("measured", buildJsonObject {
            put("captureEvidenceMatchesSession", matching)
            put("captureCallbackCount", capture.frames.count.takeIf { matching })
            put("lastCaptureAgeMs", capture.lastReceivedElapsedMs?.takeIf { matching && it <= elapsed }?.let { elapsed - it })
            val first = capture.frames.firstTimestampNs
            val last = capture.frames.lastTimestampNs
            val fps = if (matching && first != null && last != null && last > first && capture.frames.count > 1)
                (capture.frames.count - 1) * 1_000_000_000.0 / (last - first) else null
            put("captureCallbackFps", fps)
            put("captureMaxGapNs", capture.frames.maxGapNs.takeIf { matching })
            val encoder = capture.encoder?.takeIf { matching }
            val encodedFirst = encoder?.firstPtsUs
            val encodedLast = encoder?.lastPtsUs
            put("encodedFps", if (encoder != null && encodedFirst != null && encodedLast != null && encodedLast > encodedFirst)
                (encoder.frames - 1) * 1_000_000.0 / (encodedLast - encodedFirst) else null)
            put("encodedFrames", encoder?.frames)
            put("lastEncodedOutputAgeMs", encoder?.lastOutputElapsedMs?.takeIf { it <= elapsed }?.let { elapsed - it })
            put("encoderQueueDepth", encoder?.queueItems)
            put("encoderQueueBytes", encoder?.queueBytes)
            put("completedCodecFiles", encoder?.completedFiles)
            put("encodedBytes", encoder?.encodedBytes)
            put("continuousStages", encoder?.continuousStages ?: JsonNull)
            put("continuousFaultStages", encoder?.continuousFaultStages ?: JsonNull)
            put("encodedPayloadMbpsEstimate", if (encoder?.encodedBytes != null && encodedFirst != null && encodedLast != null && encodedLast > encodedFirst)
                encoder.encodedBytes * 8.0 / (encodedLast - encodedFirst) else null)
            put("temperatureC", JsonNull)
        })
        put("unavailableReason", if (state.recordingBackend in setOf("CONTINUOUS_CODEC", "SHARED_INPUT_CONTINUOUS_CODEC"))
            "Codec output counters do not prove USB publication or scene motion; temperature is unavailable."
            else "V4 MediaRecorder does not expose encoded-frame/queue telemetry; capture callbacks are not encoded frames.")
        put("cameraCleanup", buildJsonObject {
            put("unsettledOwners", lifecycle["unsettledOwners"] ?: JsonNull)
            put("transactions", buildJsonArray {
                lifecycle["transactions"]?.jsonObject?.values?.toList()?.takeLast(3)?.forEach { transaction ->
                    val steps = transaction.jsonArray
                    val retained = if (steps.size <= 16) steps else steps.take(4) + steps.takeLast(12)
                    add(buildJsonObject {
                        put("omittedSteps", (steps.size - retained.size).coerceAtLeast(0))
                        put("steps", buildJsonArray {
                            retained.forEach { element -> add(buildJsonObject {
                                val step = element.jsonObject
                                put("at", step["at"] ?: JsonNull)
                                put("elapsed", step["elapsed"] ?: JsonNull)
                                put("step", code(step["step"]?.jsonPrimitive?.contentOrNull))
                            }) }
                        })
                    })
                }
            })
        })
        put("recentEvents", buildJsonArray {
            events.filter { it.eventName.startsWith("RECORDER_") || it.eventName.startsWith("SURROUND_") ||
                it.eventName.startsWith("USB_") || it.category == "LIFECYCLE" }
                .takeLast(20).forEach { event -> add(buildJsonObject {
                    put("at", event.epochMs); put("elapsed", event.elapsedRealtimeMs)
                    put("event", code(event.eventName)); put("severity", code(event.severity))
                    // Only numeric/enum evidence is permitted here; full logs remain in the detailed report.
                    put("facts", buildJsonObject {
                        listOf("reason", "status", "phase", "availability", "attempts", "resumeAllowed", "route",
                            "cameraGeneration", "frames", "lastFrameAgeMs", "generation", "segmentNumber", "outputLost", "bufferWidth", "bufferHeight", "elapsedMs",
                            "enabled", "requested", "segment", "previewActive", "previewRequested", "storageKind", "captureSessionRevision", "switches")
                            .asSequence().mapNotNull { key -> event.payload[key]?.takeIf {
                                it.toByteArray(Charsets.UTF_8).size <= 80 && it.all { c -> c.isLetterOrDigit() || c in "_.-" }
                            }?.let { key to it } }.take(8).forEach { (key, value) -> put(key, value) }
                    })
                }) }
        })
    }

    fun shouldRecordPreviewFault(reason: String, status: String, hasRecorderError: Boolean): Boolean =
        reason !in setOf("DISABLED", "RECORDING_ENDED") &&
            !(reason == "OUTPUT_RELEASED" && !hasRecorderError && status in setOf("IDLE", "STOPPED", "FINALIZING"))

    fun report(version: String, code: Int, sdk: Int, current: JsonObject, lastFault: JsonObject?, lastRun: JsonObject? = null,
               processExit: JsonObject? = null): String {
        var retainedFault = lastFault
        var retainedCurrent = current
        val omitted = mutableListOf<String>()
        fun render() = buildJsonObject {
            put("schemaVersion", 1); put("format", "OPENAVM_RECORDER_SHORT_JSON")
            put("version", version); put("versionCode", code); put("androidSdk", sdk)
            put("current", retainedCurrent); put("lastFault", retainedFault ?: JsonNull)
            put("lastRun", lastRun ?: JsonNull)
            put("processExit", processExit ?: JsonNull)
            put("omittedForSize", buildJsonArray { omitted.forEach { add(it) } })
            put("videoIncluded", false); put("pathsOrShareTokensIncluded", false)
        }.toString()
        var report = render()
        if (report.toByteArray(Charsets.UTF_8).size > MAX_BYTES && retainedFault != null) {
            retainedFault = JsonObject(retainedFault!!.filterKeys { it in setOf("atEpochMs", "atElapsedMs", "status",
                "lastErrorCode", "recordingBackend", "capturedVersion", "capturedVersionCode") })
            omitted += "lastFault.details"
            report = render()
        }
        if (report.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            retainedCurrent = JsonObject(current.filterKeys { it != "recentEvents" && it != "cameraCleanup" })
            omitted += "current.recentEvents"; omitted += "current.cameraCleanup"
            report = render()
        }
        check(report.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return report
    }
}
