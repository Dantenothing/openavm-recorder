package com.dante.zeekrcapabilitylab.sentry.runtime

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class GuardClockEvidence(
    val source: String, val status: String, val matchedFrames: Int,
    val latestSensorUs: Long?, val latestEncoderUs: Long?, val latestMatchArrivalUs: Long?,
    val generation: Long, val lastResetReason: String?, val largestMatchedDeltaUs: Long,
)

data class GuardFrameTime(val framePtsUs: Long, val nowPtsUs: Long, val arrivedUs: Long, val generation: Long)

/**
 * Match timestamps only within this CameraDevice instance. UNKNOWN timestamps are never
 * equated with elapsedRealtime. Visual rules use matched media time; callback time is a
 * separate freshness fence. Neither callback arrival alone nor a fixed offset proves a match.
 */
class GuardCaptureClock(private val realtimeSource: Boolean) {
    private data class Sample(val ptsUs: Long, val arrivedUs: Long?)
    private data class Matched(val sensorUs: Long, val encoderUs: Long, val arrivedUs: Long?)
    private val sensors = ArrayDeque<Sample>()
    private val outputs = ArrayDeque<Sample>()
    private val matched = ArrayDeque<Matched>()
    private var lastSensor: Sample? = null
    private var lastOutput: Sample? = null
    private var generation = 0L
    private var lastResetReason: String? = null
    private var largestMatchedDeltaUs = 0L

    @Synchronized fun sensor(timestampUs: Long, arrivedUs: Long? = null) {
        if (add(timestampUs, arrivedUs, sensor = true)) match()
    }
    @Synchronized fun encoded(ptsUs: Long, arrivedUs: Long? = null) {
        if (add(ptsUs, arrivedUs, sensor = false)) match()
    }

    @Synchronized fun calibrated(nowUs: Long): Boolean {
        val last = matched.lastOrNull() ?: return false
        val at = last.arrivedUs ?: return false
        return matched.size >= REQUIRED_MATCHES && matched.takeLast(REQUIRED_MATCHES).all { it.arrivedUs != null } &&
            nowUs >= at && nowUs - at <= MAX_GAP_US
    }

    /** Compatibility helper; unlike the old implementation this returns the matched media PTS. */
    @Synchronized fun imagePts(timestampUs: Long, nowUs: Long): Long? =
        frameTime(timestampUs, nowUs, nowUs)?.framePtsUs

    @Synchronized fun frameTime(timestampUs: Long, imageArrivalUs: Long, nowUs: Long): GuardFrameTime? {
        if (!calibrated(nowUs) || timestampUs < 0 || imageArrivalUs !in 0..nowUs ||
            nowUs - imageArrivalUs > MAX_FRAME_AGE_US) return null
        val pair = matched.lastOrNull { abs(it.sensorUs - timestampUs) <= MATCH_TOLERANCE_US } ?: return null
        val arrived = minOf(pair.arrivedUs ?: return null, imageArrivalUs)
        val latestPts = matched.last().encoderUs
        if (nowUs < arrived || nowUs - arrived > MAX_FRAME_AGE_US || pair.encoderUs > latestPts ||
            latestPts - pair.encoderUs > MAX_FRAME_AGE_US) return null
        return GuardFrameTime(pair.encoderUs, latestPts, arrived, generation)
    }

    @Synchronized fun acceptsTrigger(ptsUs: Long, observedAtUs: Long?, proofGeneration: Long?, nowUs: Long): Boolean {
        if (!calibrated(nowUs) || proofGeneration != generation || observedAtUs == null ||
            observedAtUs !in 0..nowUs || nowUs - observedAtUs > MAX_FRAME_AGE_US) return false
        val latest = matched.last().encoderUs
        return ptsUs in 0..latest && latest - ptsUs <= MAX_FRAME_AGE_US
    }

    @Synchronized fun evidence(nowUs: Long) = GuardClockEvidence(
        if (realtimeSource) "REALTIME" else "UNKNOWN", if (calibrated(nowUs)) "SAME_CAMERA_MATCHED" else "WAITING_FOR_MATCHES",
        matched.size, lastSensor?.ptsUs, lastOutput?.ptsUs, matched.lastOrNull()?.arrivedUs,
        generation, lastResetReason, largestMatchedDeltaUs)

    private fun add(ptsUs: Long, arrivedUs: Long?, sensor: Boolean): Boolean {
        if (ptsUs < 0 || arrivedUs?.let { it < 0 } == true) { reset("INVALID_TIMESTAMP"); return false }
        val at = arrivedUs ?: ptsUs.takeIf { realtimeSource }
        val old = if (sensor) lastSensor else lastOutput
        if (old?.ptsUs == ptsUs) return false
        val reason = when {
            old == null -> null
            ptsUs < old.ptsUs -> "MEDIA_TIMESTAMP_RESTART"
            ptsUs - old.ptsUs > MAX_GAP_US -> "MEDIA_TIMESTAMP_GAP"
            at != null && old.arrivedUs != null && at < old.arrivedUs -> "CALLBACK_TIME_RESTART"
            at != null && old.arrivedUs != null && at - old.arrivedUs > MAX_GAP_US -> "CALLBACK_GAP"
            else -> null
        }
        if (reason != null) reset(reason)
        val next = Sample(ptsUs, at)
        val queue = if (sensor) sensors else outputs
        if (sensor) lastSensor = next else lastOutput = next
        queue.addLast(next)
        while (queue.size > MAX_SAMPLES) queue.removeFirst()
        return true
    }

    private fun match() {
        while (sensors.isNotEmpty() && outputs.isNotEmpty()) {
            val delta = sensors.first().ptsUs - outputs.first().ptsUs
            when {
                abs(delta) <= MATCH_TOLERANCE_US -> {
                    val sensor = sensors.removeFirst(); val output = outputs.removeFirst()
                    val at = if (sensor.arrivedUs != null && output.arrivedUs != null &&
                        abs(sensor.arrivedUs - output.arrivedUs) <= MAX_GAP_US)
                        minOf(sensor.arrivedUs, output.arrivedUs) else null
                    matched.addLast(Matched(sensor.ptsUs, output.ptsUs, at))
                    largestMatchedDeltaUs = maxOf(largestMatchedDeltaUs, abs(delta))
                    while (matched.size > MAX_SAMPLES) matched.removeFirst()
                }
                delta < 0 -> sensors.removeFirst()
                else -> outputs.removeFirst()
            }
        }
    }

    private fun reset(reason: String) {
        sensors.clear(); outputs.clear(); matched.clear()
        lastSensor = null; lastOutput = null
        generation++; lastResetReason = reason; largestMatchedDeltaUs = 0
    }

    companion object {
        private const val REQUIRED_MATCHES = 8
        private const val MAX_SAMPLES = 90
        private const val MATCH_TOLERANCE_US = 2_000L
        private const val MAX_GAP_US = 2_000_000L
        private const val MAX_FRAME_AGE_US = 1_500_000L
    }
}
