package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A getter-return time is an observation time, never a claim about the ECU measurement time. */
@Serializable
data class VehicleTelemetryValue(
    @SerialName("t") val readElapsedMs: Long,
    @SerialName("n") val number: Double? = null,
    @SerialName("s") val text: String? = null,
    @SerialName("q") val status: String = "OK",
)

@Serializable
data class VehicleTelemetrySample(
    val elapsedMs: Long,
    val epochMs: Long,
    val values: Map<String, VehicleTelemetryValue>,
    val breakBefore: Boolean = false,
) {
    private fun usable(key: String) = values[key]?.takeIf { it.status == "OK" }
    val speedKmh: Double? get() = usable("speed")?.number
    val gear: String? get() = usable("gear")?.text
    val indicator: String? get() = usable("indicator")?.text
    private fun binary(key: String) = usable(key)?.number?.let { when (it) { 0.0 -> false; 1.0 -> true; else -> null } }
    val turnLeft: Boolean? get() = binary("turn_left")
    val turnRight: Boolean? get() = binary("turn_right")
    val brakePressed: Boolean? get() = binary("brake_pedal")
    val brakeLamp: Boolean? get() = binary("stop_lamp")
}

object VehicleTelemetryPolicy {
    const val PLAYBACK_AGE_MS = 2_000L
    const val POLL_INTERVAL_MS = 250L
    const val SDK_TIMEOUT_MS = 2_000L
    val keys: Set<String> = linkedSetOf("speed", "gear", "indicator", "turn_left", "turn_right", "brake_pedal", "stop_lamp")
    private val statuses = setOf("OK", "NO_VALUE", "SENTINEL", "TINY_SENTINEL", "NON_FINITE", "OUT_OF_RANGE",
        "UNKNOWN_ENUM", "NON_INTEGER_ENUM", "UNAVAILABLE", "READ_FAILED", "INVALID_VALUE", "STALE")

    /** Retain only the values needed for playback, not SDK objects, raw diagnostics or exception text. */
    fun observed(key: String, atMs: Long, value: Double?, text: String?, valid: Boolean, status: String): VehicleTelemetryValue {
        if (!valid) return VehicleTelemetryValue(atMs, status = when {
            status in statuses && status != "OK" -> status
            status.startsWith("READ_FAILED") -> "READ_FAILED"
            else -> "UNAVAILABLE"
        })
        val candidate = if (key == "gear" || key == "indicator") VehicleTelemetryValue(atMs, text = text)
            else VehicleTelemetryValue(atMs, number = value)
        return if (validValue(key, candidate)) candidate else VehicleTelemetryValue(atMs, status = "INVALID_VALUE")
    }

    fun validValue(key: String, field: VehicleTelemetryValue): Boolean {
        if (key !in keys || field.readElapsedMs < 0 || field.status !in statuses) return false
        if (field.status != "OK") return field.number == null && field.text == null
        return when (key) {
            "speed" -> field.text == null && field.number?.let { it.isFinite() && it in 0.0..360.0 } == true
            "gear" -> field.number == null && field.text in setOf("P", "R", "N", "D")
            "indicator" -> field.number == null && field.text in setOf("off", "left", "right", "hazard")
            else -> field.text == null && field.number in setOf(0.0, 1.0)
        }
    }

    fun validStored(sample: VehicleTelemetrySample): Boolean = sample.elapsedMs >= 0 && sample.epochMs > 0 &&
        sample.values.size <= keys.size && sample.values.all { (key, value) ->
            validValue(key, value) && value.readElapsedMs <= sample.elapsedMs
        }
}

object VehicleTelemetryPlayback {
    /** No interpolation and no fallback past an invalid observation, a stop or a gap. */
    fun sampleAt(samples: List<VehicleTelemetrySample>, atMs: Long?): VehicleTelemetrySample? {
        if (atMs == null || atMs < 0) return null
        val index = samples.binarySearchBy(atMs) { it.elapsedMs }.let { if (it >= 0) it else -it - 2 }
        val sample = samples.getOrNull(index) ?: return null
        if (!VehicleTelemetryPolicy.validStored(sample) || atMs - sample.elapsedMs !in 0..VehicleTelemetryPolicy.PLAYBACK_AGE_MS) return null
        // A corrupt/skipped row has no trustworthy timestamp. Conservatively withhold the interval
        // before the next known break instead of extending the earlier controls through that hole.
        if (atMs > sample.elapsedMs && samples.getOrNull(index + 1)?.breakBefore == true) return null
        return sample.copy(values = sample.values.mapValues { (_, value) ->
            if (atMs - value.readElapsedMs in 0..VehicleTelemetryPolicy.PLAYBACK_AGE_MS) value
            else VehicleTelemetryValue(value.readElapsedMs, status = "STALE")
        })
    }
}
