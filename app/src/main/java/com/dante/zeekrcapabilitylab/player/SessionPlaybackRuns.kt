package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.SessionVideoSegment

/** A persistent playlist is only valid over exact, same-channel coverage.
 * Real gaps/overlaps or changed pixel layouts start another run; never hide them
 * by concatenating media time onto the session's vehicle/GPS clock. */
internal fun sessionPlaybackRuns(segments: List<SessionVideoSegment>,
    compatible: (SessionVideoSegment, SessionVideoSegment) -> Boolean): List<List<SessionVideoSegment>> {
    val result = mutableListOf<MutableList<SessionVideoSegment>>()
    segments.groupBy { it.channelId }.values.forEach { channel ->
        var run: MutableList<SessionVideoSegment>? = null
        channel.sortedBy { it.mediaStartElapsedMs }.forEach { next ->
            val previous = run?.lastOrNull()
            if (previous == null || previous.recordingSessionId != next.recordingSessionId ||
                previous.mediaEndElapsedMs != next.mediaStartElapsedMs || !compatible(previous, next)) {
                run = mutableListOf<SessionVideoSegment>().also(result::add)
            }
            requireNotNull(run).add(next)
        }
    }
    return result
}
