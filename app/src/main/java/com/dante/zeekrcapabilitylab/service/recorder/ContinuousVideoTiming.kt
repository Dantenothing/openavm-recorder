package com.dante.zeekrcapabilitylab.service.recorder

/** Sampling never throttles Camera2 or the display reader. Source time and playback time have
 * separate units; dividing source time also preserves genuine source gaps without catch-up bursts. */
internal class ContinuousVideoTiming(val fps: Int, val multiplier: Int) {
    init { require(fps in setOf(15, 20, 30)); require(multiplier == 1 || multiplier in TimeLapsePolicy.MULTIPLIERS) }
    private var lastSource = -1L
    private var nextDueUs = 0L
    val frameDurationUs = 1_000_000L / fps
    val sampleIntervalUs = multiplier * 1_000_000L / fps
    val keyframeTimeoutMs = if (multiplier == 1) 2_000L else 2_000L + 2 * ((sampleIntervalUs + 999) / 1000)

    fun select(sourcePtsUs: Long): Long? {
        require(sourcePtsUs >= 0 && sourcePtsUs > lastSource) { "PRODUCT_SAMPLE_TIME_REGRESSED" }
        lastSource = sourcePtsUs
        if (multiplier == 1) return sourcePtsUs
        if (sourcePtsUs < nextDueUs) return null
        // Round the nominal deadlines down, like SurfaceTexture nanoseconds -> microseconds.
        val bucket = ((sourcePtsUs + 1) * fps) / (multiplier * 1_000_000L)
        nextDueUs = (bucket + 1) * multiplier * 1_000_000L / fps
        return sourcePtsUs / multiplier
    }
    fun sourceUs(playbackPtsUs: Long): Long = Math.multiplyExact(playbackPtsUs, multiplier.toLong())
    /** A manual stop between sparse samples must not claim footage from after the stop. */
    fun sourceEndUs(playbackEndUs: Long, lastSourceUs: Long): Long =
        minOf(sourceUs(playbackEndUs), lastSourceUs + frameDurationUs)

    companion object {
        fun forConfig(config: RecorderConfig) = ContinuousVideoTiming(config.requestedFrameRate,
            if (config.recordingMode == RecordingMode.TIME_LAPSE) config.timeLapseMultiplier else 1)
        fun capturePlan(config: RecorderConfig) = if (ProductContinuousPolicy.eligible(config))
            CaptureCadencePlan(CaptureSubmissionMode.REPEATING_ENCODER, config.requestedFrameRate.toDouble(), null)
        else TimeLapseCaptureCadencePolicy.plan(config.recordingMode, config.timeLapseMultiplier)
    }
}

/** Passive waiting retains the existing input Surface. It NEVER grants permission to reopen a
 * camera. Only a native contention callback plus confirmed cleanup may arm that separate path. */
internal object ContinuousSourceWaitPolicy {
    const val QUIET_AFTER_MS = 1_000L
    const val LIMIT_MS = 30 * 60_000L
    fun problem(heartbeatAgeMs: Long, sourceAgeMs: Long): String? = when {
        heartbeatAgeMs >= 5_000 -> "PRODUCT_INPUT_THREAD_STALLED"
        sourceAgeMs >= LIMIT_MS -> "PRODUCT_SOURCE_WAIT_TIMEOUT"
        else -> null
    }
}
