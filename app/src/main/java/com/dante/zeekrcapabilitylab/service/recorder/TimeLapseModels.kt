package com.dante.zeekrcapabilitylab.service.recorder

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class RecordingMode {
    NORMAL,
    TIME_LAPSE,
}

/** Source-independent time-lapse policy shared by UI, recorder, metadata and tests. */
object TimeLapsePolicy {
    const val PLAYBACK_FPS = 30.0
    const val SAFETY_CHUNK_SECONDS = 5 * 60
    const val DEFAULT_MULTIPLIER = 30
    val MULTIPLIERS = listOf(2, 5, 10, 15, 30, 60, 90, 120, 150)

    fun captureRateFps(multiplier: Int): Double = PLAYBACK_FPS / multiplier.toDouble()
}

@Serializable
enum class TimeLapseAccuracy {
    PASS,
    DEGRADED,
    INCORRECT,
    INSUFFICIENT_SAMPLE,
    UNAVAILABLE,
}

@Serializable
data class TimeLapseMeasurement(
    val requestedMultiplier: Int,
    val realDurationMs: Long,
    val encodedDurationMs: Long? = null,
    val measuredMultiplier: Double? = null,
    val relativeError: Double? = null,
    val accuracy: TimeLapseAccuracy,
)

/** Compares requested time-lapse speed with finalized MP4 duration. */
object TimeLapseMeasurementPolicy {
    private const val MIN_EXPECTED_OUTPUT_MS = 2_000.0
    private const val PASS_ERROR_LIMIT = 0.15
    private const val DEGRADED_ERROR_LIMIT = 0.35

    fun measure(
        requestedMultiplier: Int,
        realDurationMs: Long,
        encodedDurationMs: Long?,
    ): TimeLapseMeasurement {
        if (
            requestedMultiplier < 2 ||
            realDurationMs <= 0L ||
            encodedDurationMs == null ||
            encodedDurationMs <= 0L
        ) {
            return TimeLapseMeasurement(
                requestedMultiplier = requestedMultiplier,
                realDurationMs = realDurationMs,
                encodedDurationMs = encodedDurationMs,
                accuracy = TimeLapseAccuracy.UNAVAILABLE,
            )
        }
        val measuredMultiplier = realDurationMs.toDouble() / encodedDurationMs
        val relativeError = abs(measuredMultiplier - requestedMultiplier) / requestedMultiplier
        val expectedOutputMs = realDurationMs.toDouble() / requestedMultiplier
        val accuracy = when {
            expectedOutputMs < MIN_EXPECTED_OUTPUT_MS -> TimeLapseAccuracy.INSUFFICIENT_SAMPLE
            relativeError <= PASS_ERROR_LIMIT -> TimeLapseAccuracy.PASS
            relativeError <= DEGRADED_ERROR_LIMIT -> TimeLapseAccuracy.DEGRADED
            else -> TimeLapseAccuracy.INCORRECT
        }
        return TimeLapseMeasurement(
            requestedMultiplier = requestedMultiplier,
            realDurationMs = realDurationMs,
            encodedDurationMs = encodedDurationMs,
            measuredMultiplier = measuredMultiplier,
            relativeError = relativeError,
            accuracy = accuracy,
        )
    }
}
