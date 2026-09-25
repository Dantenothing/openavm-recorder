package com.dante.zeekrcapabilitylab.mirror

/** A pending handoff is an in-memory user command, never a persisted recording permit. */
class MirrorHandoffGate(private val timeoutMs: Long = 20_000) {
    enum class Target { PREVIEW, CABIN, RECORD }
    enum class Decision { WAIT, START, CANCEL }
    data class Request(val token: Long, val target: Target, val deadlineMs: Long, val stoppedSession: String?)
    private var serial = 0L
    var pending: Request? = null; private set
    fun begin(target: Target, now: Long, stoppedSession: String? = null): Request? {
        if (pending != null) return null
        return Request(++serial, target, now + timeoutMs, stoppedSession).also { pending = it }
    }
    fun cancel() { pending = null; serial++ }
    fun poll(token: Long, now: Long, allowed: Boolean, recorderGone: Boolean,
             previewGone: Boolean, auxiliaryGone: Boolean, nativeIdle: Boolean, glIdle: Boolean,
             stopConfirmed: Boolean = true): Decision {
        val request = pending ?: return Decision.CANCEL
        if (request.token != token) return Decision.CANCEL
        if (!allowed || now >= request.deadlineMs) { cancel(); return Decision.CANCEL }
        if (!recorderGone || !previewGone || !auxiliaryGone || !nativeIdle || !glIdle || !stopConfirmed) return Decision.WAIT
        pending = null
        return Decision.START
    }
}

object MirrorRecordingClock {
    fun text(elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/** Explicit rear/side calibration wins; an unknown or conflicting direction stays unassigned. */
object MirrorDirectionMapping {
    fun resolve(order: List<Int>, calibrated: Boolean, front: Int, rear: Int, left: Int, right: Int): List<Int> {
        val result = MutableList(4) { 0 }
        val explicit = listOf(front, rear, left, right)
        for (slot in listOf(1, 2, 3, 0)) {
            val lane = explicit[slot]
            if (lane in 1..4 && lane !in result) result[slot] = lane
        }
        if (calibrated && order.toSet() == setOf(1, 2, 3, 4) && order.size == 4) {
            for (slot in 0..3) if (result[slot] == 0 && order[slot] !in result) result[slot] = order[slot]
        }
        return result
    }
}

object MirrorWindowGeometry {
    data class Bounds(val width: Int, val imageHeight: Int, val x: Int, val y: Int)
    fun fit(width: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int,
            chromeHeight: Int, video: Boolean, minWidth: Int, maxWidth: Int): Bounds {
        val sw = screenWidth.coerceAtLeast(1)
        val sh = screenHeight.coerceAtLeast(1)
        val byHeight = if (video) ((sh - chromeHeight).coerceAtLeast(1) * 1.85f).toInt() else sw
        val cap = minOf(sw, maxWidth, byHeight).coerceAtLeast(1)
        val w = width.coerceIn(minOf(minWidth, cap), cap)
        val image = if (video) (w / 1.85f).toInt() else 0
        return Bounds(w, image, x.coerceIn(0, (sw - w).coerceAtLeast(0)),
            y.coerceIn(0, (sh - chromeHeight - image).coerceAtLeast(0)))
    }
}
