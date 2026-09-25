package com.dante.zeekrcapabilitylab.service.recorder

/** The display owner can retire a Surface while EGL still uses it. Only the reader's
 * explicit detach acknowledgement permits release; changing the desired target is not an ack. */
internal class ContinuousPreviewLease<T>(val value: T, private val released: (T) -> Unit) {
    private var retired = false
    private var reading = false
    private var acknowledged = false

    @Synchronized fun take(): Boolean {
        if (retired || reading || acknowledged) return false
        reading = true
        return true
    }
    fun retire() {
        val release = synchronized(this) { retired = true; claimRelease() }
        if (release) released(value)
    }
    fun detachAcknowledged() {
        val release = synchronized(this) { check(reading); reading = false; claimRelease() }
        if (release) released(value)
    }
    private fun claimRelease(): Boolean {
        if (!retired || reading || acknowledged) return false
        acknowledged = true
        return true
    }
}

/** Constant-space source clock. File timestamps derive from media time, never slow file closing.
 * No per-frame ledger or diagnostic barcode is retained in the product path. */
internal class ContinuousSourceClock {
    var frames = 0; private set
    var firstNs: Long? = null; private set
    var lastNs: Long? = null; private set
    var maximumGapNs = 0L; private set
    fun accept(timestampNs: Long): Long? {
        if (timestampNs <= 0 || timestampNs == lastNs) return null
        check(lastNs == null || timestampNs > lastNs!!) { "PRODUCT_SOURCE_TIMESTAMP_REGRESSED" }
        check(frames < Int.MAX_VALUE) { "PRODUCT_SOURCE_FRAME_LIMIT" }
        lastNs?.let { maximumGapNs = maxOf(maximumGapNs, timestampNs - it) }
        if (firstNs == null) firstNs = timestampNs
        lastNs = timestampNs; frames++
        return (timestampNs - firstNs!!) / 1000L
    }
}
