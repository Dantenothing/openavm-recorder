package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*

/** Small, source/display-specific evidence that survives timeline clipping. No paths or camera images. */
internal object PreviewStartupEvidence {
    val KEYS = setOf("reason", "target", "previewOwner", "recordingSessionId", "stoppedSession", "status",
        "sourceObserved", "sourceFrames", "captureFrames", "frames", "inputFrames", "inputFrameAgeMs",
        "inputNotifications", "bufferWidth", "bufferHeight", "lastFrameAgeMs", "safeToContinue", "deviceClosed",
        "cameraGeneration", "channelId", "previewBackend", "captureFailures", "previewBuffersLost", "encoderBuffersLost", "encodedFrames",
        "inputPollAttempts", "inputPolledFrames", "inputHeartbeatAgeMs", "displaySubmissions", "displaySubmitAgeMs",
        "captureFailureReason", "failureFrameNumber", "failureSequenceId", "failureImageCaptured", "requestTemplate",
        "cameraId", "connection", "nativeDevice")
    private val FAILURE_KEYS = setOf("previewOwner", "captureFrames", "captureFailures", "captureFailureReason",
        "failureFrameNumber", "failureSequenceId", "failureImageCaptured", "requestTemplate")
    fun accepts(name: String): Boolean = name.startsWith("MIRROR_PREVIEW_") || name.startsWith("CAMERA_CONNECTION_") || name.startsWith("RECORDER_SOURCE_STARTUP_") ||
        name.startsWith("RECORDER_MIRROR_HANDOFF_") || name in setOf("RECORDER_MIRROR_STATE", "RECORDER_MIRROR_BUFFER_SELECTED",
            "RECORDER_MIRROR_UNAVAILABLE", "RECORDER_MIRROR_PIPELINE_SNAPSHOT")
    fun report(rows: List<AwayRow>): JsonObject {
        val process = rows.lastOrNull()?.process
        val current = rows.filter { it.process == process && accepts(it.event) }
        val owner = current.lastOrNull { it.facts["previewOwner"] != null || it.facts["recordingSessionId"] != null }
            ?.facts?.let { it["previewOwner"] ?: it["recordingSessionId"] }
        // Keep the first native failure independently of the last ten lifecycle rows.
        // Never attribute another preview/recording owner's failure to the latest run.
        val firstFailure = owner?.let { id -> current.firstOrNull {
            it.event == "MIRROR_PREVIEW_FIRST_CAPTURE_FAILED" && it.facts["previewOwner"] == id
        } }?.let { event(it, FAILURE_KEYS) }
        val selected = current.takeLast(10).map { event(it, KEYS) }
        var take = selected.size
        while (true) {
            val report = buildJsonObject {
                put("scope", "LATEST_PROCESS_PREVIEW_EVENTS_NOT_SELECTED_CLIP")
                put("process", process)
                put("firstSourceFailure", firstFailure ?: JsonNull)
                put("events", JsonArray(selected.takeLast(take)))
                put("eventsOmittedForSize", selected.size - take)
            }
            if (report.toString().toByteArray(Charsets.UTF_8).size <= 3 * 1024 || take == 0) return report
            take--
        }
    }
    private fun event(row: AwayRow, keys: Set<String>) = buildJsonObject {
        put("event", row.event); put("elapsed", row.elapsed)
        put("facts", buildJsonObject {
            row.facts.filterKeys { it in keys }.forEach { (key, value) ->
                if (value.length in 1..80 && value.all { it.isLetterOrDigit() || it in "_.-" }) put(key, value)
            }
        })
    }
}
