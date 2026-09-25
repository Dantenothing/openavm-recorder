package com.dante.zeekrcapabilitylab.service.recorder

/** A cut belongs immediately BEFORE a keyframe; that frame starts the next file at PTS zero. */
internal class KeyframeSegmentPlanner {
    data class Placement(val ptsUs: Long, val cutBefore: Boolean)
    private var firstPts: Long? = null
    private var lastPts: Long? = null
    private var cutRequested = false

    fun requestCut() { check(!cutRequested); cutRequested = true }
    fun accept(ptsUs: Long, keyFrame: Boolean): Placement? {
        check(ptsUs >= 0 && (lastPts == null || ptsUs > lastPts!!)) { "ENCODED_TIMESTAMP_NOT_INCREASING" }
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

internal object ContinuousRecordingPolicy {
    fun eligible(mode: RecordingMode, mirror: Boolean, source: RecordingSourceRole): Boolean =
        mode == RecordingMode.NORMAL && mirror && source == RecordingSourceRole.SURROUND

    fun mayCommitRotation(tokenMatches: Boolean, sameRecorder: Boolean, sameOutput: Boolean,
                          stopping: Boolean, closing: Boolean): Boolean =
        tokenMatches && sameRecorder && sameOutput && !stopping && !closing
}
