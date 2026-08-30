package com.dante.zeekrbridge.sound

/**
 * Deterministic linear-interpolation resampler toward 48,000 Hz.
 * Output length is the ceiling of inputFrames * outRate / inRate, matching the
 * streamed interpolation loop (last output position < inputFrames).
 */
object AudioResampler {
    const val TARGET_RATE = 48000

    fun outputFrameCount(inputFrames: Long, inRate: Int, outRate: Int = TARGET_RATE): Long {
        require(inputFrames >= 0) { "negative frame count" }
        require(inRate > 0 && outRate > 0) { "rates must be positive" }
        if (inputFrames == 0L) return 0L
        return (inputFrames * outRate + inRate - 1) / inRate
    }

    /**
     * Source frames (relative to a selection start) needed to produce output
     * frames [outputStart, outputEnd). Returns [firstSourceFrame, frameCount].
     */
    fun sourceWindowForOutput(
        outputStart: Long,
        outputEnd: Long,
        inRate: Int,
        outRate: Int,
        inFrames: Long,
    ): LongArray {
        require(outputEnd > outputStart) { "empty output window" }
        require(inFrames > 0) { "empty input" }
        val firstPos = outputStart.toDouble() * inRate / outRate
        val lastPos = (outputEnd - 1).toDouble() * inRate / outRate
        val i0 = firstPos.toLong().coerceIn(0L, inFrames - 1L)
        val i1 = (lastPos.toLong() + 1L).coerceIn(i0, inFrames - 1L)
        return longArrayOf(i0, i1 - i0 + 1L)
    }
}
