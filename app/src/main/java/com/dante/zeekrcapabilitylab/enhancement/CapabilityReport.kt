package com.dante.zeekrcapabilitylab.enhancement

import kotlinx.serialization.json.*

/** Declarations are evidence for a later experiment, never a recording permit. */
object ConcurrentCapabilityPolicy {
    fun classify(ids: List<String>, sdk: Int, queryFailed: Boolean, sets: Collection<Set<String>>): String = when {
        ids.size !in 2..3 || ids.any(String::isBlank) || ids.distinct().size != ids.size -> "INVALID_SELECTION"
        sdk < 30 -> "API_UNAVAILABLE"
        queryFailed -> "QUERY_FAILED"
        sets.any { it.containsAll(ids) } -> "DECLARED_NOT_TESTED"
        else -> "NOT_DECLARED"
    }
}

object CapabilityReport {
    const val MAX_BYTES = 24 * 1024
    const val FORMAT = "OPENAVM_CAPABILITIES"

    /** A valid, single JSON document even if a vendor publishes excessive metadata. */
    fun encode(header: JsonObject, cameras: List<JsonObject>, encoders: List<JsonObject>,
               cameraCount: Int = cameras.size, encoderCount: Int = encoders.size): String {
        var cameraDetails = cameras.take(8)
        var encoderDetails = encoders.take(8)
        fun render() = buildJsonObject {
            put("schemaVersion", 1); put("format", FORMAT)
            put("evidenceLevel", "DECLARATIONS_ONLY")
            put("hardwareTested", false); put("cameraOpened", false); put("codecStarted", false)
            put("header", header)
            put("cameras", JsonArray(cameraDetails)); put("encoders", JsonArray(encoderDetails))
            put("omittedCameras", (cameraCount - cameraDetails.size).coerceAtLeast(0))
            put("omittedEncoders", (encoderCount - encoderDetails.size).coerceAtLeast(0))
        }.toString()
        var result = render()
        while (result.toByteArray(Charsets.UTF_8).size > MAX_BYTES && (cameraDetails.isNotEmpty() || encoderDetails.isNotEmpty())) {
            if (encoderDetails.isNotEmpty()) encoderDetails = encoderDetails.dropLast(1)
            else cameraDetails = cameraDetails.dropLast(1)
            result = render()
        }
        if (result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return result
        // Do not silently return truncated JSON or turn a missing declaration into support.
        return buildJsonObject {
            put("schemaVersion", 1); put("format", FORMAT); put("error", "HEADER_EXCEEDS_LIMIT")
            put("hardwareTested", false); put("cameraOpened", false); put("codecStarted", false)
        }.toString()
    }
}
