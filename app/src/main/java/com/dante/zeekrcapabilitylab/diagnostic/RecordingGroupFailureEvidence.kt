package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*

/** Immutable cached observations taken before group cancellation changes any member. */
internal object RecordingGroupFailureEvidence {
    const val FORMAT = "OPENAVM_GROUP_FIRST_FAILURE"
    data class Member(val config: RecorderConfig, val state: RecorderState, val capture: CaptureEvidence)

    fun capture(session: String, error: String, epochMs: Long, elapsedMs: Long, version: String,
                members: List<Member>): JsonObject = sanitize(buildJsonObject {
        put("schemaVersion", 1); put("format", FORMAT)
        put("sessionCorrelation", requireNotNull(ProcessExitReport.sessionCorrelation(session)))
        put("capturedVersion", version); put("atEpochMs", epochMs); put("atElapsedMs", elapsedMs)
        put("errorCode", code(error)); put("scope", "CACHED_BEFORE_GROUP_STOP_NOT_HARDWARE_QUERY")
        put("channels", buildJsonArray {
            members.filter { it.state.recordingSessionId == session }.take(3).forEach { m ->
                val state = m.state; val capture = m.capture
                val matches = capture.matches(state) && capture.cameraGeneration == state.cameraGeneration
                val frames = capture.takeIf { matches }; val encoder = frames?.encoder
                add(buildJsonObject {
                    put("channelId", ProcessExitReport.channel(state.channelId))
                    put("status", code(state.status)); put("recordingBackend", code(state.recordingBackend))
                    put("cameraGeneration", state.cameraGeneration); put("segmentNumber", state.segmentNumber)
                    put("captureEvidenceMatches", matches); put("cleanupPending", state.cleanupPending)
                    put("cleanupUnconfirmed", state.cleanupUnconfirmed)
                    put("requestedWidth", m.config.profile.size.width); put("requestedHeight", m.config.profile.size.height)
                    put("requestedBitrateBps", m.config.profile.bitrateBps); put("requestedFrameRate", m.config.requestedFrameRate)
                    put("capturedFrames", frames?.frames?.count)
                    put("completedCaptureCallbacks", frames?.preview?.let { it.completedWithPreviewTarget + it.completedWithoutPreviewTarget })
                    put("captureFailures", frames?.preview?.captureFailures)
                    put("lastCaptureFailureReason", frames?.preview?.lastFailureReason)
                    put("previewBuffersLost", frames?.preview?.previewBuffersLost)
                    put("encoderBuffersLost", frames?.preview?.encoderBuffersLost)
                    put("otherBuffersLost", frames?.preview?.otherBuffersLost)
                    put("encodedFrames", encoder?.frames); put("writtenFrames", encoder?.writtenFrames)
                    put("queuedBytes", encoder?.queueBytes); put("queuedItems", encoder?.queueItems)
                    put("formatNotifications", encoder?.formatNotifications); put("formatDecision", code(encoder?.formatDecision))
                    put("lastFrameElapsedMs", frames?.lastReceivedElapsedMs)
                    put("lastEncodedElapsedMs", encoder?.lastOutputElapsedMs); put("lastWriteElapsedMs", encoder?.lastWriteElapsedMs)
                })
            }
        })
    })

    private val numbers = setOf("cameraGeneration", "segmentNumber", "requestedWidth", "requestedHeight",
        "requestedBitrateBps", "requestedFrameRate", "capturedFrames", "completedCaptureCallbacks", "captureFailures",
        "lastCaptureFailureReason", "previewBuffersLost", "encoderBuffersLost", "otherBuffersLost", "encodedFrames",
        "writtenFrames", "queuedBytes", "queuedItems", "formatNotifications", "lastFrameElapsedMs", "lastEncodedElapsedMs", "lastWriteElapsedMs")
    private val booleans = setOf("captureEvidenceMatches", "cleanupPending", "cleanupUnconfirmed")
    private val symbols = setOf("status", "recordingBackend", "formatDecision")
    private fun code(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
        ?: value?.let { "UNKNOWN" }

    fun sanitize(value: JsonObject): JsonObject = buildJsonObject {
        require(value["format"]?.jsonPrimitive?.content == FORMAT)
        put("schemaVersion", 1); put("format", FORMAT)
        val correlation = value["sessionCorrelation"]?.jsonPrimitive?.content
        require(correlation != null && correlation.matches(Regex("sha256:[0-9a-f]{16}")))
        put("sessionCorrelation", correlation)
        put("capturedVersion", value["capturedVersion"]?.jsonPrimitive?.content?.takeIf {
            it.length in 1..64 && it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: "UNKNOWN")
        listOf("atEpochMs", "atElapsedMs").forEach { key -> put(key, value[key]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }) }
        put("errorCode", code(value["errorCode"]?.jsonPrimitive?.contentOrNull))
        put("scope", "CACHED_BEFORE_GROUP_STOP_NOT_HARDWARE_QUERY")
        put("channels", buildJsonArray {
            value["channels"]?.jsonArray.orEmpty().take(3).forEach { item ->
                val row = item.jsonObject
                val channel = row["channelId"]?.jsonPrimitive?.content
                if (channel !in RecordingChannelId.all) return@forEach
                add(buildJsonObject {
                    put("channelId", channel)
                    numbers.forEach { key -> put(key, row[key]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }) }
                    booleans.forEach { key -> put(key, row[key]?.jsonPrimitive?.booleanOrNull) }
                    symbols.forEach { key -> put(key, code(row[key]?.jsonPrimitive?.contentOrNull)) }
                })
            }
        })
    }
}
