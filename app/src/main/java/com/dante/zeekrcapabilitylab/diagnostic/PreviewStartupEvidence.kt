package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*

/** Small, source/display-specific evidence that survives timeline clipping. No paths or camera images. */
internal object PreviewStartupEvidence {
    val KEYS = setOf("reason", "target", "previewOwner", "recordingSessionId", "stoppedSession", "status",
        "sourceObserved", "sourceFrames", "captureFrames", "frames", "inputFrames", "inputFrameAgeMs",
        "inputNotifications", "bufferWidth", "bufferHeight", "lastFrameAgeMs", "safeToContinue", "deviceClosed")
    fun accepts(name: String): Boolean = name.startsWith("MIRROR_PREVIEW_") || name.startsWith("RECORDER_SOURCE_STARTUP_") ||
        name.startsWith("RECORDER_MIRROR_HANDOFF_") || name in setOf("RECORDER_MIRROR_STATE", "RECORDER_MIRROR_BUFFER_SELECTED", "RECORDER_MIRROR_UNAVAILABLE")
    fun report(rows: List<AwayRow>): JsonObject {
        val process = rows.lastOrNull()?.process
        val selected = rows.filter { it.process == process && accepts(it.event) }.takeLast(10).map { row ->
            buildJsonObject {
                put("event", row.event); put("elapsed", row.elapsed)
                put("facts", buildJsonObject {
                    row.facts.filterKeys { it in KEYS }.forEach { (key, value) ->
                        if (value.length in 1..80 && value.all { it.isLetterOrDigit() || it in "_.-" }) put(key, value)
                    }
                })
            }
        }
        var take = selected.size
        while (true) {
            val report = buildJsonObject {
                put("scope", "LATEST_PROCESS_PREVIEW_EVENTS_NOT_SELECTED_CLIP")
                put("process", process)
                put("events", JsonArray(selected.takeLast(take)))
                put("eventsOmittedForSize", selected.size - take)
            }
            if (report.toString().toByteArray(Charsets.UTF_8).size <= 3 * 1024 || take == 0) return report
            take--
        }
    }
}
