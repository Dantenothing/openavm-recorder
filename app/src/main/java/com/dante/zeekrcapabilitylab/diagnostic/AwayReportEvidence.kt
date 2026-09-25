package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Pure export helpers: no camera work, timers, networking, or extra vehicle tests. */
internal object AwayReportEvidence {
    const val MAX_BYTES = 24 * 1024
    const val CONTINUOUS_FAILURE = "RECORDER_CONTINUOUS_FAILED"
    const val FIRST_CONTINUOUS_FAILURE = "RECORDER_CONTINUOUS_FIRST_FAILURE"
    private val enumCode = Regex("[A-Z][A-Z0-9_]{1,79}")

    fun errorCode(message: String?): String? = message?.takeWhile { it.isLetterOrDigit() || it == '_' }
        ?.takeIf { enumCode.matches(it) }

    private fun edge(row: AwayRow?): JsonElement = row?.let {
        buildJsonObject {
            put("event", it.event); put("wall", it.wall); put("elapsed", it.elapsed); put("process", it.process)
            put("facts", buildJsonObject {
                listOf("recordingMode", "authorityReason", "finalizeReason", "reason", "errorCode", "generation", "segment",
                    "recordingSessionId", "cameraGeneration", "source", "status", "callbackType", "cameraErrorCode",
                    "callbackEntryElapsedMs", "staleCallback", "closeId", "closeStep", "stepElapsedMs", "closeRequestedElapsedMs",
                    "closeDurationMs", "safeToContinue", "deviceClosed", "terminal", "outputLost", "cleanupPending", "cleanupUnconfirmed",
                    "screenOn", "mainDisplayOn", "backgroundPowerOffEvidence", "appForeground", "deviceOwnerId")
                    .forEach { key -> it.facts[key]?.let { value ->
                        if (value.length<=80 && value.all { c -> c.isLetterOrDigit() || c in "_.-" }) put(key, value)
                    } }
                com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureEvidence.FACT_KEYS.forEach { key ->
                    it.facts[key]?.takeIf { value -> value.length in 1..160 &&
                        value.all { c -> c.isLetterOrDigit() || c in "_.$-" } }?.let { value -> put(key, value) }
                }
            })
        }
    } ?: JsonNull

    /** Independent of the clipped timeline; labels events within the test, not hardware success. */
    fun recording(rows: List<AwayRow>): JsonObject = buildJsonObject {
        put("startRequests", rows.count { it.event=="RECORDER_START" })
        put("recordingObserved", rows.any { it.event=="RECORDER_START" || it.facts["recorder"]=="RECORDING" })
        put("stopRequests", rows.count { it.event=="RECORDER_STOP" })
        put("lastStart", edge(rows.lastOrNull { it.event=="RECORDER_START" }))
        put("lastStop", edge(rows.lastOrNull { it.event=="RECORDER_STOP" }))
        put("lastContinuousFailure", edge(rows.lastOrNull { it.event==CONTINUOUS_FAILURE }))
        val startIndex = rows.indexOfLast { it.event == "RECORDER_START" }
        val start = rows.getOrNull(startIndex)
        val failures = (if (startIndex >= 0) rows.drop(startIndex) else rows).filter {
            it.event in setOf(CONTINUOUS_FAILURE, FIRST_CONTINUOUS_FAILURE) && (start == null || it.process == start.process &&
                (it.facts["recordingSessionId"] == null || AwayTimeline.sameRun(start, it)))
        }
        put("firstContinuousFailure", edge(failures.minWithOrNull(compareBy<AwayRow> {
            it.facts["faultElapsedMs"]?.toLongOrNull() ?: it.elapsed
        }.thenBy { it.facts["faultCaptureOrder"]?.toLongOrNull() ?: Long.MAX_VALUE })))
        put("lastWakeLockRelease", edge(rows.lastOrNull { it.event=="RECORDER_WAKE_LOCK_RELEASED" }))
    }

    /** First cause and final cleanup survive timeline clipping. Never infer a normal-away stop. */
    fun lifecycle(rows: List<AwayRow>): JsonObject {
        val startIndex = rows.indexOfLast { it.event == "RECORDER_START" }
        val start = rows.getOrNull(startIndex)
        val run = (if (startIndex >= 0) rows.drop(startIndex) else rows).filter {
            start == null || it.process == start.process &&
                (it.facts["recordingSessionId"] == null || AwayTimeline.sameRun(start, it))
        }
        val terminal = run.lastOrNull { it.event == "RECORDER_SESSION_TERMINATED" }
        val closeId = run.lastOrNull { it.event == "RECORDER_CLOSE_REQUESTED" }?.facts?.get("closeId")
        val closeRows = run.filter { closeId != null && it.facts["closeId"] == closeId }
        return buildJsonObject {
            put("scope", if (start != null) "LAST_RECORDING_REQUEST_IN_TEST" else "OBSERVED_TEST_WINDOW")
            put("firstCameraFailure", edge(run.firstOrNull {
                it.facts["staleCallback"] != "true" && (it.event == "RECORDER_CAMERA_LOSS_OBSERVED" ||
                    it.event == "RECORDER_CAMERA_CALLBACK" && it.facts["callbackType"] in setOf("ON_ERROR", "ON_DISCONNECTED"))
            }))
            put("termination", edge(terminal))
            put("lastCleanupTimeout", edge(run.lastOrNull { it.event == "RECORDER_CLOSE_UNCONFIRMED" }))
            put("closeRequest", edge(closeRows.firstOrNull { it.event == "RECORDER_CLOSE_REQUESTED" }))
            put("closeProgress", JsonArray(closeRows.filter { it.event == "RECORDER_CLOSE_PROGRESS" }.take(4).map(::edge)))
            put("closeTimeout", edge(closeRows.firstOrNull { it.event == "RECORDER_CLOSE_UNCONFIRMED" }))
            put("closeCallback", edge(run.lastOrNull {
                it.event == "RECORDER_CAMERA_CALLBACK" && it.facts["callbackType"] == "ON_CLOSED" &&
                    closeRows.firstOrNull()?.let { request -> it.elapsed >= request.elapsed &&
                        request.facts["deviceOwnerId"] !in setOf(null, "NONE") &&
                        request.facts["deviceOwnerId"] == it.facts["deviceOwnerId"] } == true
            }))
            put("cleanupResult", edge(closeRows.lastOrNull { it.event == "RECORDER_CLOSE_SETTLED" }))
            put("screenOffAfterTermination", edge(run.firstOrNull {
                terminal != null && it.elapsed >= terminal.elapsed && (it.event == "SCREEN_OFF" ||
                    "0:OFF" in it.facts["displays"].orEmpty().split(','))
            }))
            put("usbLastVideoIntegrity", "NOT_VERIFIED_BY_THIS_REPORT")
        }
    }

    /** Saved faults may outlive a test or reboot. Export only a matching observation-window snapshot. */
    fun faultInWindow(rows: List<AwayRow>, saved: JsonObject?, versionCode: Int): JsonObject {
        val process = (saved?.get("processStartId") as? JsonPrimitive)?.contentOrNull
        val at = (saved?.get("atElapsedMs") as? JsonPrimitive)?.longOrNull
        val version = (saved?.get("capturedVersionCode") as? JsonPrimitive)?.intOrNull
        val window = rows.filter { it.process==process }
        val reason = when {
            saved==null -> "NO_SAVED_FAULT"
            version!=versionCode -> "OTHER_VERSION"
            process==null -> "LEGACY_FAULT_WITHOUT_PROCESS_ID"
            window.isEmpty() -> "OTHER_PROCESS"
            at==null || at<window.first().elapsed || at>window.last().elapsed -> "OUTSIDE_TEST_WINDOW"
            else -> "MATCHED_TEST_WINDOW"
        }
        return buildJsonObject {
            put("match", reason)
            put("snapshot", if(reason!="MATCHED_TEST_WINDOW") JsonNull else buildJsonObject {
                put("processStartId", process)
                listOf("atEpochMs", "atElapsedMs", "capturedVersionCode", "segmentNumber", "cameraGeneration", "captureSessionRevision")
                    .forEach { key -> (saved?.get(key) as? JsonPrimitive)?.longOrNull?.let { put(key, it) } }
                listOf("lastErrorCode", "status", "recordingMode", "recordingBackend", "storage")
                    .forEach { key -> put(key, errorCode((saved?.get(key) as? JsonPrimitive)?.contentOrNull)) }
                put("measured", buildJsonObject {
                    val measured = saved?.get("measured") as? JsonObject
                    listOf("lastCaptureAgeMs", "encodedFrames", "lastEncodedOutputAgeMs", "encoderQueueDepth",
                        "encoderQueueBytes", "completedCodecFiles", "encodedBytes").forEach { key ->
                        (measured?.get(key) as? JsonPrimitive)?.longOrNull?.let { put(key, it) }
                    }
                })
            })
        }
    }

    /** Keep the stop/fault summary while trimming routine timeline rows to one clipboard payload. */
    fun bounded(base: JsonObject, rows: List<AwayRow>): JsonObject {
        var take = minOf(48, rows.size)
        while(true) {
            val result = buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("eventsRetained", rows.size)
                put("eventsOmittedFromCopy", rows.size-take)
                put("timeline", Json.parseToJsonElement(Json.encodeToString(rows.takeLast(take))))
            }
            if(result.toString().toByteArray(Charsets.UTF_8).size<=MAX_BYTES) return result
            check(take>0) { "AWAY_SUMMARY_TOO_LARGE" }
            take--
        }
    }
}
