package com.dante.zeekrcapabilitylab.service.recorder

/** One re-submission of the existing request, before the first frame of a camera session. */
internal class FirstFrameRecovery(private val startedAtMs: Long) {
    private var finished = false
    fun request(nowMs: Long, sourceFrames: Long, allowed: Boolean): Boolean {
        if (!allowed || sourceFrames > 0 || nowMs - startedAtMs >= ContinuousSourceWaitPolicy.LIMIT_MS) finished = true
        if (finished || nowMs < startedAtMs || nowMs - startedAtMs < WAIT_MS) return false
        finished = true
        return true
    }
    fun cancel() { finished = true }
    companion object { const val WAIT_MS = 4_000L }
}
