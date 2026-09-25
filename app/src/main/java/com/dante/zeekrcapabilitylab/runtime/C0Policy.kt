package com.dante.zeekrcapabilitylab.runtime

/** One local opt-in, one foreground baseline and at most two cloud-directed reopens. */
internal class C0Policy(val run: String, val process: String, val started: Long) {
    val deadline = started + DURATION_MS
    var revoked = false; private set
    var baselineClosedAt: Long? = null; private set
    var reopens = 0; private set
    var lastClosedAt: Long? = null; private set
    private var awaitingClose = false
    fun active(now: Long) = !revoked && now >= started && now < deadline
    fun revoke() { revoked = true }
    fun cancelRun(requestedRun: String?): Boolean {
        if (requestedRun != run) return false
        revoke()
        return true
    }
    fun closed(now: Long) { if (baselineClosedAt == null) baselineClosedAt = now; lastClosedAt = now; awaitingClose = false }
    fun ready(now: Long, foreground: Boolean, cleanup: Boolean): Boolean = active(now) && !foreground && cleanup &&
        baselineClosedAt != null && !awaitingClose && reopens < 2 && now - (lastClosedAt ?: now) >= WAIT_MS
    fun accept(now: Long, sentAt: Long, epoch: String, replyRun: String, expectedNonce: String,
               replyNonce: String, sequence: Int, foreground: Boolean, cleanup: Boolean): Boolean {
        if (!ready(now, foreground, cleanup) || epoch != process || replyRun != run ||
            expectedNonce.isBlank() || expectedNonce != replyNonce || sequence != reopens + 1 ||
            now < sentAt || now - sentAt > REPLY_FRESH_MS) return false
        reopens++
        awaitingClose = true
        return true
    }
    companion object {
        const val MIN_BUFFERS = 30
        const val DURATION_MS = 8 * 60_000L
        const val WAIT_MS = 120_000L
        const val REPLY_FRESH_MS = 8_000L
        const val CAPTURE_MS = 15_000L
    }
}

internal data class C0CaptureEvidence(val buffers:Int, val foregroundObserved:Boolean, val displayOnObserved:Boolean)

/** A late expiry/stop may close a capture before its buffer budget; never count it as a passing reopen. */
internal object C0Verdict {
    fun classify(finished:Boolean, reason:String?, cleanupConfirmed:Boolean, reopens:List<C0CaptureEvidence>):String = when {
        !finished -> "RUNNING"
        reason!="PERMIT_EXPIRED" || !cleanupConfirmed -> "INCOMPLETE"
        reopens.size!=2 || reopens.any {it.buffers<C0Policy.MIN_BUFFERS} -> "BACKGROUND_REOPEN_NOT_COMPLETED"
        reopens.all {!it.foregroundObserved && !it.displayOnObserved} -> "PASS_BACKGROUND_DISPLAY_OFF"
        else -> "BACKGROUND_REOPEN_DISPLAY_OFF_NOT_PROVEN"
    }
}
