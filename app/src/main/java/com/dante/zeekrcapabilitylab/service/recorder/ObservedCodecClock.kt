package com.dante.zeekrcapabilitylab.service.recorder

import io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline
import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor
import java.util.UUID

/** A codec run is anchored once. Later callback/writer latency cannot move its sample clock. */
internal class ObservedCodecClock(val runId: String = UUID.randomUUID().toString()) {
    data class Stamp(val ptsUs: Long, val elapsedNs: Long, val epochMs: Long)
    private var origin: Stamp? = null
    private var previousPts: Long? = null

    fun observe(ptsUs: Long, deliveredElapsedNs: Long, deliveredEpochMs: Long): Stamp {
        require(ptsUs in 0..VideoClockAnchor.MAX_TIME_US && deliveredElapsedNs in 0..VideoClockAnchor.MAX_TIME_NS)
        require(previousPts?.let { ptsUs > it } != false) { "CODEC_CLOCK_REGRESSED" }
        val first = origin ?: Stamp(ptsUs, deliveredElapsedNs, deliveredEpochMs).also { origin = it }
        val deltaUs = ptsUs - first.ptsUs
        val elapsedNs = Math.addExact(first.elapsedNs, Math.multiplyExact(deltaUs, 1_000L))
        require(elapsedNs <= VideoClockAnchor.MAX_TIME_NS)
        previousPts = ptsUs
        return Stamp(ptsUs, elapsedNs, Math.addExact(first.epochMs, deltaUs / 1000))
    }
}

/** Records only samples actually written. Rotation ends at the next keyframe's original PTS.
 * On terminal stop the bounded median of recent observed frame intervals supplies the last
 * sample duration. A one-frame/no-cadence file gets an explicit minimal 1 ms, never a guessed fps. */
internal class EncodedFileClock(private val runId: String) {
    private var first: ObservedCodecClock.Stamp? = null
    private var lastPtsUs: Long? = null
    private val recentIntervals = ArrayDeque<Long>()
    var frames: Long = 0; private set
    var maximumGapUs: Long = 0; private set

    fun written(stamp: ObservedCodecClock.Stamp) {
        val previous = lastPtsUs
        require(previous == null || stamp.ptsUs > previous)
        if (first == null) first = stamp
        if (previous != null) {
            val delta = stamp.ptsUs - previous
            maximumGapUs = maxOf(maximumGapUs, delta)
            // A source interruption is not evidence that the terminal sample lasts seconds.
            if (delta in 1..1_000_000L) {
                if (recentIntervals.size == 8) recentIntervals.removeFirst()
                recentIntervals.addLast(delta)
            }
        }
        lastPtsUs = stamp.ptsUs; frames++
    }

    data class Coverage(val first: ObservedCodecClock.Stamp, val timeline: ContinuousSegmentTimeline) {
        val durationUs: Long get() = timeline.endExclusivePtsUs - timeline.firstPtsUs
        val endElapsedNs: Long get() = Math.addExact(first.elapsedNs, Math.multiplyExact(durationUs, 1_000L))
        val endEpochMs: Long get() = Math.addExact(first.epochMs, durationUs / 1000)
        val anchor: VideoClockAnchor get() = VideoClockAnchor(elapsedRealtimeNs = first.elapsedNs, source = "CODEC_OUTPUT_OBSERVED")
    }

    fun coverage(nextKeyframePtsUs: Long? = null): Coverage? {
        val start = first ?: return null
        val last = requireNotNull(lastPtsUs)
        val sorted = recentIntervals.sorted()
        val interval = if (sorted.isEmpty()) 1_000L else sorted[(sorted.size - 1) / 2]
        val end = nextKeyframePtsUs ?: Math.addExact(last, interval)
        require(end > last && end <= VideoClockAnchor.MAX_TIME_US) { "INVALID_CODEC_FILE_BOUNDARY" }
        return Coverage(start, ContinuousSegmentTimeline(runId = runId, firstPtsUs = start.ptsUs,
            lastPtsUs = last, endExclusivePtsUs = end, frames = frames, startsWithKeyFrame = true)).also {
            require(it.endElapsedNs <= VideoClockAnchor.MAX_TIME_NS && it.anchor.valid())
        }
    }
}
