package com.dante.zeekrcapabilitylab.sentry.ai

/** Owns at most one waiting frame and one in-flight frame, even if consumers race. */
class LatestDetectorFrame<T : AutoCloseable> : AutoCloseable {
    private var pending: T? = null
    private var closed = false
    private var consuming = false
    private var offered = 0L
    private var dropped = 0L
    private var releaseFailures = 0L

    data class Snapshot(val waiting: Int, val inFlight: Int, val offered: Long, val dropped: Long, val releaseFailures: Long, val closed: Boolean)
    fun snapshot(): Snapshot = synchronized(this) { Snapshot(if (pending == null) 0 else 1, if (consuming) 1 else 0, offered, dropped, releaseFailures, closed) }

    /** Every call transfers a distinct owned frame. Replaced/disabled frames are still released. */
    fun offer(frame: T) {
        val release = synchronized(this) {
            offered++
            if (closed) { dropped++; frame } else {
                val previous = pending
                pending = frame
                if (previous != null) dropped++
                previous
            }
        }
        release?.let { release(it) }
    }
    fun consumeLatest(consume: (T) -> Unit): Boolean {
        val frame = synchronized(this) {
            if (consuming) return false
            val next = pending ?: return false
            pending = null
            consuming = true
            next
        }
        try { consume(frame) } finally {
            try { release(frame) } finally { synchronized(this) { consuming = false } }
        }
        return true
    }
    override fun close() {
        val release = synchronized(this) {
            closed = true
            pending.also { if (it != null) dropped++; pending = null }
        }
        release?.let { release(it) }
    }
    private fun release(frame: T) {
        try { frame.close() } catch (_: Exception) { synchronized(this) { releaseFailures++ } }
    }
}

enum class DetectorActivity { IDLE, WATCH, EVENT }
enum class DetectorPressure { NORMAL, WARM, HOT, CRITICAL }

/** Sampling affects inference only. ImageReader must still acquire/close frames when this says no. */
class DetectorCadence {
    private var lastAdmittedUs: Long? = null
    private var lastObservedUs = -1L
    fun targetFps(activity: DetectorActivity, pressure: DetectorPressure): Int {
        val normal = when (activity) { DetectorActivity.IDLE -> 2; DetectorActivity.WATCH -> 8; DetectorActivity.EVENT -> 12 }
        return when (pressure) { DetectorPressure.NORMAL -> normal; DetectorPressure.WARM -> minOf(normal, 2); DetectorPressure.HOT -> 1; DetectorPressure.CRITICAL -> 0 }
    }
    fun admit(nowMonotonicUs: Long, activity: DetectorActivity, pressure: DetectorPressure, enabled: Boolean): Boolean {
        if (nowMonotonicUs < 0 || nowMonotonicUs <= lastObservedUs) return false
        lastObservedUs = nowMonotonicUs
        val fps = if (enabled) targetFps(activity, pressure) else 0
        if (fps == 0) return false
        val previous = lastAdmittedUs
        if (previous != null && nowMonotonicUs - previous < (1_000_000L + fps - 1) / fps) return false
        lastAdmittedUs = nowMonotonicUs
        return true
    }
}
