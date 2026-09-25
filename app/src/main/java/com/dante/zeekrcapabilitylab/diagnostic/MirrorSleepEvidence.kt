package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*

/** Compares observations before/after sleep; never certifies vehicle power or continuous visibility. */
internal object MirrorSleepEvidence {
    private fun released(row: AwayRow) = row.facts["captureReleased"] == "true" &&
        listOf("recorderRunning", "previewRunning", "auxiliaryActive", "wakeLock").all { row.facts[it] == "false" } &&
        row.facts["cleanupOwners"] == "0" && row.facts["nativeIdle"] == "true" && row.facts["glIdle"] == "true"

    fun report(rows: List<AwayRow>): JsonObject {
        val startIndex = rows.indexOfLast { it.event == "MIRROR_SLEEP_SUSPENDED" }
        val start = rows.getOrNull(startIndex)
        val after = if (start != null) rows.drop(startIndex + 1) else emptyList()
        val cycle = after.filter { it.process == start?.process &&
            it.facts["controlsSession"] == start?.facts?.get("controlsSession") &&
            it.facts["sleepCycle"] == start?.facts?.get("sleepCycle") }
        val returned = cycle.firstOrNull { it.event == "MIRROR_SLEEP_RETURN_OBSERVED" }
        val beforeReturn = cycle.takeWhile { it !== returned }
        val closed = beforeReturn.lastOrNull { it.event == "MIRROR_SLEEP_CONTROLS_CLOSED" }
        val ready = beforeReturn.lastOrNull { it.event == "MIRROR_SLEEP_CAPTURE_RELEASED" &&
            it.facts["screenUsable"] == "false" && it.facts["overlayAttached"] == "true" && released(it) }
        val sameWindow = ready != null && returned != null &&
            ready.facts["windowSession"] !in setOf(null, "NONE", "") &&
            ready.facts["windowSession"] == returned.facts["windowSession"]
        val interrupted = after.takeWhile { it !== returned }.any { it.process != start?.process }
        val captureStarted = ready != null && after.any { it.process == ready.process && it.elapsed >= ready.elapsed &&
            (returned == null || it.elapsed <= returned.elapsed) &&
            it.event in setOf("RECORDER_START", "MIRROR_SLEEP_RESUME_TAPPED", "MIRROR_SLEEP_AUTO_REQUESTED") }
        val result = when {
            start == null -> "NOT_TESTED"
            closed != null -> "CONTROL_SESSION_CLOSED"
            interrupted -> "PROCESS_CHANGED_RETENTION_NOT_PROVEN"
            returned == null -> "AWAITING_RETURN"
            !released(returned) -> "CAPTURE_RELEASE_UNCONFIRMED_AT_RETURN"
            ready == null -> "NO_RELEASED_WINDOW_OBSERVATION_BEFORE_RETURN"
            !sameWindow || returned.facts["overlayAttached"] != "true" -> "WINDOW_RETENTION_NOT_PROVEN"
            captureStarted -> "CAPTURE_ACTIVITY_DURING_PAUSE"
            else -> "SAME_PROCESS_AND_WINDOW_RETURNED_CAPTURE_RELEASED"
        }
        val sleepGap = if (ready != null && returned != null && ready.process == returned.process &&
            returned.elapsed >= ready.elapsed && returned.uptime >= ready.uptime)
            ((returned.elapsed - ready.elapsed) - (returned.uptime - ready.uptime)).coerceAtLeast(0) else null
        fun edge(row: AwayRow?): JsonElement = row?.let { buildJsonObject {
            put("event", it.event); put("process", it.process); put("elapsed", it.elapsed); put("uptime", it.uptime)
            put("facts", buildJsonObject { it.facts.filterKeys { key -> key in setOf("controlsSession", "sleepCycle", "windowSession",
                "captureReleased", "overlayAttached", "screenUsable", "cleanupOwners", "wakeLock", "deviceClosed", "safeToContinue", "previewOwner", "returnMode", "reason",
                "profileAttempt", "profileWaitMs", "profileStatus", "requestedRole") }
                .forEach { (key, value) -> put(key, value.take(80)) } })
        } } ?: JsonNull
        val lastPreviewClose = rows.lastOrNull { it.event == "MIRROR_PREVIEW_CLOSE_SETTLED" && it.process == start?.process }
        return buildJsonObject {
            put("scope", "LATEST_PAUSED_MIRROR_CYCLE"); put("classification", result)
            put("sameProcess", ready != null && returned != null && ready.process == returned.process)
            put("sameWindow", sameWindow); put("cpuSleepGapMs", sleepGap?.let(::JsonPrimitive) ?: JsonNull)
            put("wholeVehicleSleep", "NOT_PROVEN"); put("whileCpuAsleep", "NOT_OBSERVABLE")
            // This describes the designed policy, not an observation proving no camera activity.
            val mode = start?.facts?.get("returnMode")
            put("resumePolicy", if (mode in setOf("PREVIEW", "RECORD")) "USER_OPTED_IN_$mode" else "EXPLICIT_TAP_ONLY")
            put("automaticReturn", edge(cycle.lastOrNull { it.event in setOf("MIRROR_SLEEP_AUTO_REQUESTED",
                "MIRROR_SLEEP_AUTO_STARTED", "MIRROR_SLEEP_AUTO_FAILED", "MIRROR_SLEEP_AUTO_BLOCKED", "MIRROR_SLEEP_AUTO_CANCELLED") }))
            put("profilePreparation", edge(cycle.lastOrNull { it.event.startsWith("MIRROR_SLEEP_PROFILE_") }))
            put("releaseBeforeReturn", edge(ready)); put("returnObservation", edge(returned))
            put("lastPreviewClose", edge(lastPreviewClose))
        }
    }
}
