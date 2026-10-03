package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable

/** A point on the MP4's local presentation timeline and the car's monotonic clock.
 * Observation anchors include unknown pipeline latency; only REALTIME camera timestamps share
 * the elapsed clock by platform contract. Neither establishes ECU measurement timing. */
@Serializable
data class VideoClockAnchor(
    val schemaVersion: Int = 1,
    val mediaPositionUs: Long = 0,
    val elapsedRealtimeNs: Long,
    val source: String,
    val uncertaintyMs: Long? = null,
) {
    fun valid(): Boolean = schemaVersion == 1 && mediaPositionUs in 0..MAX_TIME_US &&
        elapsedRealtimeNs in 0..MAX_TIME_NS && source in SOURCES &&
        (uncertaintyMs == null || uncertaintyMs in 0..60_000)

    fun elapsedAtMediaMs(mediaMs: Long): Long? {
        if (!valid() || mediaMs !in 0..MAX_TIME_MS) return null
        val nanos = elapsedRealtimeNs + mediaMs * 1_000_000L - mediaPositionUs * 1_000L
        return (nanos / 1_000_000L).takeIf { nanos >= 0 }
    }

    companion object {
        // Far beyond an Android boot's useful lifetime; keeps every conversion within Long.
        const val MAX_TIME_MS = 100L * 365 * 24 * 60 * 60 * 1000
        const val MAX_TIME_US = MAX_TIME_MS * 1000
        const val MAX_TIME_NS = MAX_TIME_MS * 1_000_000
        val SOURCES = setOf("CAMERA_REALTIME", "CODEC_OUTPUT_OBSERVED", "CALLBACK_OBSERVED")
    }
}

data class SessionVideoSegment(
    val id: String,
    val channelId: String,
    val recordingSessionId: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    /** Actual media/container duration, never filename or wall-clock duration. */
    val mediaDurationMs: Long,
    val anchor: VideoClockAnchor? = null,
    val continuousTimeline: ContinuousSegmentTimeline? = null,
) {
    // Kept outside the constructor: no caller can inject a boundary override, and copy resets it.
    private var quantizedBoundaryEnd: Long? = null
    val mediaStartElapsedMs: Long? get() = anchor?.elapsedAtMediaMs(0) ?: startElapsedMs.takeIf { anchor == null }
    val mediaEndElapsedMs: Long? get() = quantizedBoundaryEnd ?: mediaStartElapsedMs?.let { it + mediaDurationMs }
    internal fun withProvenBoundary(endElapsedMs: Long): SessionVideoSegment = copy().also { it.quantizedBoundaryEnd = endElapsedMs }
    internal val quantizedBoundary: Boolean get() = quantizedBoundaryEnd != null
    fun valid(): Boolean = id.isNotBlank() && id.length <= 1024 && channelId in SessionPlaybackTimeline.CHANNELS &&
        recordingSessionId.isNotBlank() && startElapsedMs in 0..VideoClockAnchor.MAX_TIME_MS &&
        endElapsedMs in (startElapsedMs + 1)..VideoClockAnchor.MAX_TIME_MS &&
        mediaDurationMs in 1..SessionPlaybackTimeline.MAX_SESSION_MS && (anchor == null || anchor.valid()) &&
        (continuousTimeline == null || continuousTimeline.validate().isEmpty()) &&
        mediaStartElapsedMs?.let { it in 0..VideoClockAnchor.MAX_TIME_MS &&
            // An observed anchor may trail source capture; do not silently accept a different boot.
            kotlin.math.abs(it - startElapsedMs) <= 60_000 } == true &&
        mediaEndElapsedMs?.let { it <= VideoClockAnchor.MAX_TIME_MS } == true
}

data class SessionVideoSeek(val segmentId: String, val mediaPositionMs: Long, val sessionPositionMs: Long)
data class SessionVideoSlice(val segmentId: String, val fromMediaMs: Long, val toMediaMs: Long)

/** Parallel channels occupy one monotonic session axis. Gaps/ambiguous overlaps never pick
 * an arbitrary file. Old recordings can use explicitly approximate segment-start mapping. */
class SessionPlaybackTimeline(
    val sessionId: String,
    segments: List<SessionVideoSegment>,
    sessionStartElapsedMs: Long? = null,
) {
    val segments: List<SessionVideoSegment>
    val originElapsedMs: Long
    val durationMs: Long
    val channelIds: List<String>
    val rejectedSegments: Int
    /** Integer-ms presentation/export adjustment only; not measured synchronization accuracy. */
    val quantizedBoundaries: Int

    init {
        val bounded = segments.take(MAX_SEGMENTS)
        val duplicates = bounded.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val valid = bounded.filter { it.id !in duplicates && it.recordingSessionId == sessionId && it.valid() }.map { it.copy() }
        val earliest = valid.minOfOrNull { it.mediaStartElapsedMs!! } ?: 0L
        originElapsedMs = sessionStartElapsedMs?.takeIf { it in 0..earliest && earliest - it <= MAX_SESSION_MS } ?: earliest
        val selected = valid.filter { it.mediaEndElapsedMs!! - originElapsedMs <= MAX_SESSION_MS }
            .sortedWith(compareBy<SessionVideoSegment> { it.mediaStartElapsedMs }.thenBy { it.channelId }.thenBy { it.id })
        val resolved = selected.associateBy { it.id }.toMutableMap()
        selected.groupBy { it.channelId }.values.forEach { channel ->
            channel.zipWithNext().forEach { (before, after) ->
                if (provenQuantizationBoundary(before, after)) resolved[before.id] = before.withProvenBoundary(after.mediaStartElapsedMs!!)
            }
        }
        this.segments = selected.map { resolved.getValue(it.id) }
        quantizedBoundaries = this.segments.count { it.quantizedBoundary }
        rejectedSegments = segments.size - this.segments.size
        durationMs = this.segments.maxOfOrNull { it.mediaEndElapsedMs!! - originElapsedMs } ?: 0L
        channelIds = CHANNELS.filter { role -> this.segments.any { it.channelId == role } }
    }

    fun seek(channelId: String, sessionPositionMs: Long): SessionVideoSeek? {
        if (sessionPositionMs !in 0 until durationMs) return null
        val elapsed = originElapsedMs + sessionPositionMs
        val matches = segments.filter { it.channelId == channelId && elapsed >= it.mediaStartElapsedMs!! && elapsed < it.mediaEndElapsedMs!! }
        val item = matches.singleOrNull() ?: return null
        return SessionVideoSeek(item.id, (elapsed - item.mediaStartElapsedMs!!).coerceAtMost(item.mediaDurationMs - 1), sessionPositionMs)
    }

    fun mediaToSession(segmentId: String, mediaMs: Long): Long? = segments.singleOrNull { it.id == segmentId }?.let {
        if (mediaMs !in 0..it.mediaDurationMs) null
        else (if (mediaMs == it.mediaDurationMs) it.mediaEndElapsedMs!! else it.mediaStartElapsedMs!! + mediaMs) - originElapsedMs
    }

    fun elapsedToSession(elapsedMs: Long): Long? = (elapsedMs - originElapsedMs).takeIf { it in 0..durationMs }

    /** Exact coverage of one channel only. Caller additionally verifies codec/layout compatibility.
     * No gaps, overlap, missing-file omission, or fabricated black frames in an export.
     * Proven fractional-ms cuts may make summed integer media slices differ by 1 ms per
     * quantized boundary; original media durations and anchors are never rewritten. */
    fun range(channelId: String, fromSessionMs: Long, toSessionMs: Long): List<SessionVideoSlice>? {
        if (fromSessionMs < 0 || toSessionMs <= fromSessionMs || toSessionMs > durationMs) return null
        val start = originElapsedMs + fromSessionMs
        val end = originElapsedMs + toSessionMs
        val candidates = segments.filter { it.channelId == channelId && it.mediaEndElapsedMs!! > start && it.mediaStartElapsedMs!! < end }
        var cursor = start
        val slices = mutableListOf<SessionVideoSlice>()
        for (item in candidates) {
            val itemStart = item.mediaStartElapsedMs!!
            val itemEnd = item.mediaEndElapsedMs!!
            val coveredStart = maxOf(itemStart, start)
            if (coveredStart != cursor) return null
            val coveredEnd = minOf(itemEnd, end)
            val fromMedia = (coveredStart - itemStart).coerceAtMost(item.mediaDurationMs)
            val toMedia = if (coveredEnd == itemEnd) item.mediaDurationMs else (coveredEnd - itemStart).coerceAtMost(item.mediaDurationMs)
            if (toMedia <= fromMedia) return null // An isolated sub-ms correction is not an exportable MP4 interval.
            slices += SessionVideoSlice(item.id, fromMedia, toMedia)
            cursor = coveredEnd
        }
        return slices.takeIf { it.isNotEmpty() && cursor == end }
    }

    companion object {
        const val MAX_SEGMENTS = 2_000
        const val MAX_SESSION_MS = 7L * 24 * 60 * 60 * 1000
        val CHANNELS = listOf("SURROUND", "FRONT_CABIN", "REAR_CABIN")
        private fun provenQuantizationBoundary(before: SessionVideoSegment, after: SessionVideoSegment): Boolean {
            val a = before.continuousTimeline ?: return false
            val b = after.continuousTimeline ?: return false
            if (a.validate().isNotEmpty() || b.validate().isNotEmpty() || a.runId != b.runId ||
                a.endExclusivePtsUs != b.firstPtsUs || before.channelId != after.channelId ||
                before.recordingSessionId != after.recordingSessionId || before.anchor == null || after.anchor == null ||
                before.anchor.source != after.anchor.source) return false
            fun containerMatches(item: SessionVideoSegment, clock: ContinuousSegmentTimeline): Boolean {
                val spanUs = clock.endExclusivePtsUs - clock.firstPtsUs // validate() bounds each operand first.
                return spanUs in 1..MAX_SESSION_MS * 1000 && item.mediaDurationMs in spanUs / 1000..(spanUs + 999) / 1000
            }
            if (!containerMatches(before, a) || !containerMatches(after, b)) return false
            val sourceStartNs = before.anchor.elapsedRealtimeNs - before.anchor.mediaPositionUs * 1000
            val expectedBoundary = if (before.anchor.source == "CALLBACK_OBSERVED" && sourceStartNs % 1_000_000L == 0L) {
                // Product input preserves one integer-ms observed origin, then floors its
                // run PTS at each cut. Retain the fractional carry instead of flooring twice.
                before.mediaStartElapsedMs!! + a.endExclusivePtsUs / 1000 - a.firstPtsUs / 1000
            } else {
                // Precise codec/camera anchors retain their own fractional start timestamp.
                (sourceStartNs + (a.endExclusivePtsUs - a.firstPtsUs) * 1000) / 1_000_000L
            }
            if (after.mediaStartElapsedMs != expectedBoundary) return false
            val delta = after.mediaStartElapsedMs!! - (before.mediaStartElapsedMs!! + before.mediaDurationMs)
            // This is not a gap tolerance: exact source-PTS continuity and both measured
            // container spans are required. Larger delivery jitter remains a real unknown gap.
            return delta == -1L || delta == 1L
        }
        fun channelForRole(sourceRole: String?): String? = when (sourceRole) {
            "SURROUND" -> "SURROUND"
            "CABIN" -> "FRONT_CABIN"
            "IR" -> "REAR_CABIN"
            else -> null
        }
    }
}
