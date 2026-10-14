package com.dante.zeekrcapabilitylab.service.recorder

/** Exact rejected sample, captured before cleanup can replace the counters. No retiming. */
internal class EncodedTimestampException(val previousPtsUs: Long?, val rejectedPtsUs: Long,
                                        val keyFrame: Boolean, val cutPending: Boolean) :
    IllegalStateException("ENCODED_TIMESTAMP_NOT_INCREASING") {
    fun facts(): Map<String, String> = buildMap {
        previousPtsUs?.let { put("timestampPreviousPtsUs", it.toString()) }
        put("timestampRejectedPtsUs", rejectedPtsUs.toString())
        put("timestampKeyFrame", keyFrame.toString())
        put("timestampCutPending", cutPending.toString())
    }
    companion object {
        val NUMERIC_KEYS = setOf("timestampPreviousPtsUs", "timestampRejectedPtsUs")
        val BOOLEAN_KEYS = setOf("timestampKeyFrame", "timestampCutPending")
        val FACT_KEYS = NUMERIC_KEYS + BOOLEAN_KEYS
        fun from(error: Throwable?): EncodedTimestampException? {
            var cause = error
            repeat(4) {
                if (cause is EncodedTimestampException) return cause as EncodedTimestampException
                cause = runCatching { cause?.cause }.getOrNull()
            }
            return null
        }
    }
}

/** A cut belongs immediately BEFORE a keyframe; that frame starts the next file at PTS zero. */
internal class KeyframeSegmentPlanner {
    data class Placement(val ptsUs: Long, val cutBefore: Boolean)
    private var firstPts: Long? = null
    private var lastPts: Long? = null
    private var cutRequested = false

    fun requestCut() { check(!cutRequested); cutRequested = true }
    fun cancelCut() { cutRequested = false }
    fun accept(ptsUs: Long, keyFrame: Boolean): Placement? {
        if (ptsUs < 0 || (lastPts != null && ptsUs <= lastPts!!))
            throw EncodedTimestampException(lastPts, ptsUs, keyFrame, cutRequested)
        if (firstPts == null && !keyFrame) return null
        val cut = firstPts != null && cutRequested && keyFrame
        if (firstPts == null || cut) firstPts = ptsUs
        if (cut) cutRequested = false
        lastPts = ptsUs
        return Placement(ptsUs - firstPts!!, cut)
    }
}

/** Credits include the sample currently blocked in a writer, not just samples awaiting it. */
internal class BoundedEncodedQueue<T>(private val maxBytes: Long, private val maxItems: Int) {
    class Ticket<T> internal constructor(val value: T, val bytes: Int) { internal var released = false }
    private val pending = ArrayDeque<Ticket<T>>()
    private var bytes = 0L
    private var items = 0
    @Synchronized fun offer(size: Int, create: () -> T): Boolean {
        require(size >= 0)
        if (size.toLong() > maxBytes - bytes || items >= maxItems) return false
        // Allocation happens only after admission. A giant encoder buffer is never copied first.
        val value = create()
        pending.addLast(Ticket(value, size))
        bytes += size; items++
        return true
    }
    /** The writer may never observe a cut unless its first keyframe was admitted as well. */
    @Synchronized fun offerSample(size: Int, boundary: (() -> T)?, frame: () -> T): Boolean {
        require(size >= 0)
        val count = if (boundary == null) 1 else 2
        if (size.toLong() > maxBytes - bytes || count > maxItems - items) return false
        // Allocate both before mutating the queue. Failed copies leave all credits and items intact.
        val marker = if (boundary != null) Ticket<T>(boundary(), 0) else null
        val sample = Ticket(frame(), size)
        if (marker != null) pending.addLast(marker)
        pending.addLast(sample)
        bytes += size; items += count
        return true
    }
    @Synchronized fun peek(): T? = pending.firstOrNull()?.value
    @Synchronized fun poll(): Ticket<T>? = pending.removeFirstOrNull()
    @Synchronized fun release(ticket: Ticket<T>) {
        check(!ticket.released) { "SAMPLE_CREDIT_RELEASED_TWICE" }
        ticket.released = true
        bytes -= ticket.bytes; items--
    }
    @Synchronized fun clear() { while (pending.isNotEmpty()) release(pending.removeFirst()) }
    @Synchronized fun snapshot(): Pair<Long, Int> = bytes to items
}

/** Shared admission point for a cut marker and its first independently decodable sample. */
internal fun <T> offerContinuousSample(queue: BoundedEncodedQueue<T>, bytes: Int,
                                      boundary: (() -> T)?, frame: () -> T): Boolean =
    queue.offerSample(bytes, boundary, frame)

internal object ContinuousRecordingPolicy {
    fun eligible(mode: RecordingMode, mirror: Boolean, source: RecordingSourceRole): Boolean =
        mode == RecordingMode.NORMAL && mirror && source == RecordingSourceRole.SURROUND

    fun mayAdmitCut(tokenMatches: Boolean, sameRecorder: Boolean, sameSession: Boolean,
                          stopping: Boolean, closing: Boolean): Boolean =
        tokenMatches && sameRecorder && sameSession && !stopping && !closing
}
