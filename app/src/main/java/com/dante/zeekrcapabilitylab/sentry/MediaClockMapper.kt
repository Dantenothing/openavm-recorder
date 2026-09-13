package com.dante.zeekrcapabilitylab.sentry

import kotlin.math.abs

data class MediaClockAnchor(val monotonicUs: Long, val mediaPtsUs: Long, val uncertaintyUs: Long)

/**
 * Explicit clock-domain mapping. Anchor source must be measured, never assume equal epochs.
 * A callback-arrival anchor is approximate and must report its encoder-latency uncertainty.
 * One anchor per capture session; discontinuity requires a new session/mapping.
 */
class MediaClockMapper(private val maxDiscontinuityUs: Long = 2_000_000) {
    var anchor: MediaClockAnchor? = null
        private set
    var healthy = true
        private set
    fun anchor(monotonicUs: Long, mediaPtsUs: Long, uncertaintyUs: Long) {
        require(monotonicUs >= 0 && mediaPtsUs >= 0 && uncertaintyUs >= 0)
        check(anchor == null)
        anchor = MediaClockAnchor(monotonicUs, mediaPtsUs, uncertaintyUs)
    }
    fun toMediaPtsUs(monotonicUs: Long): Long? {
        if (!healthy) return null
        val a = anchor ?: return null
        val delta = monotonicUs - a.monotonicUs
        return runCatching { Math.addExact(a.mediaPtsUs, delta) }.getOrNull()?.takeIf { it >= 0 }
    }
    fun observe(monotonicUs: Long, mediaPtsUs: Long): Boolean {
        val mapped = toMediaPtsUs(monotonicUs) ?: return false
        if (abs(mapped - mediaPtsUs) > maxDiscontinuityUs) healthy = false
        return healthy
    }
}
