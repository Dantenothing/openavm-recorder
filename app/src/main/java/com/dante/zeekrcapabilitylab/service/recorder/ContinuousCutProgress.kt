package com.dante.zeekrcapabilitylab.service.recorder

/** One rolling cut trace. Closing a previous USB file cannot masquerade as a missing keyframe. */
internal class ContinuousCutProgress(private val keyframeTimeoutMs: () -> Long = { 2_000L }) {
    data class Snapshot(val file: Int, val requestedAtMs: Long, val keyAcceptedAtMs: Long? = null,
        val writerReceivedAtMs: Long? = null, val sourceWaitMs: Long = 0)
    private var current: Snapshot? = null
    private var quietSince: Long? = null
    private var cancelled = false
    @Synchronized fun request(file: Int, now: Long) {
        check(!cancelled) { "PRODUCT_CUT_AUTHORITY_ENDED" }
        check(current == null || current!!.writerReceivedAtMs != null) { "PRODUCT_CUT_ALREADY_REQUESTED" }
        check(current == null || file > current!!.file) { "PRODUCT_CUT_FILE_REUSED" }
        current = Snapshot(file, now)
        quietSince = null
    }
    @Synchronized fun keyAccepted(now: Long) {
        val value = requireNotNull(current)
        check(value.keyAcceptedAtMs == null && now >= value.requestedAtMs)
        current = value.copy(keyAcceptedAtMs = now,
            sourceWaitMs = value.sourceWaitMs + (quietSince?.let { now - it } ?: 0))
        quietSince = null
    }
    @Synchronized fun writerReceived(now: Long) {
        val value = requireNotNull(current)
        check(value.writerReceivedAtMs == null && value.keyAcceptedAtMs != null && now >= value.keyAcceptedAtMs)
        current = value.copy(writerReceivedAtMs = now)
    }
    @Synchronized fun problem(now: Long, sourceQuiet: Boolean = false): String? {
        if (cancelled) return null
        var value = current ?: return null
        if (value.keyAcceptedAtMs == null) {
            if (sourceQuiet) {
                if (quietSince == null) quietSince = now
                return null // The separate input watchdog still bounds source/worker silence.
            }
            quietSince?.let { since ->
                value = value.copy(sourceWaitMs = value.sourceWaitMs + now - since)
                current = value; quietSince = null
            }
        }
        return when {
            value.keyAcceptedAtMs == null && now - value.requestedAtMs - value.sourceWaitMs >= keyframeTimeoutMs() -> "PRODUCT_KEYFRAME_CUT_TIMEOUT"
            value.keyAcceptedAtMs != null && value.writerReceivedAtMs == null && now - value.keyAcceptedAtMs >= 2_000 -> "PRODUCT_WRITER_CUT_TIMEOUT"
            else -> null
        }
    }
    @Synchronized fun snapshot() = current
    @Synchronized fun cancel() { cancelled = true }
}
