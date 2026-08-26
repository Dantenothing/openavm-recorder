package com.dante.zeekrcapabilitylab.player

data class RecordingPlaybackPosition(
    val segmentIndex: Int,
    val positionInSegmentMs: Long,
)

/** Pure mapping between internal segment positions and one recording timeline. */
class RecordingPlaybackTimeline(segmentDurationsMs: List<Long>) {
    val durationsMs: List<Long> = segmentDurationsMs.map { it.coerceAtLeast(0L) }
    private val offsetsMs: List<Long> = buildList {
        var offset = 0L
        durationsMs.forEach { duration ->
            add(offset)
            offset += duration
        }
    }
    val totalDurationMs: Long = durationsMs.sum()

    fun globalPosition(segmentIndex: Int, positionInSegmentMs: Long): Long {
        if (durationsMs.isEmpty()) return 0L
        val index = segmentIndex.coerceIn(durationsMs.indices)
        return (offsetsMs[index] + positionInSegmentMs.coerceIn(0L, durationsMs[index]))
            .coerceIn(0L, totalDurationMs)
    }

    fun locate(globalPositionMs: Long): RecordingPlaybackPosition {
        if (durationsMs.isEmpty()) return RecordingPlaybackPosition(0, 0L)
        val target = globalPositionMs.coerceIn(0L, totalDurationMs)
        if (target == totalDurationMs) {
            return RecordingPlaybackPosition(durationsMs.lastIndex, durationsMs.last())
        }
        val index = offsetsMs.indices.lastOrNull { offsetsMs[it] <= target } ?: 0
        return RecordingPlaybackPosition(index, (target - offsetsMs[index]).coerceAtLeast(0L))
    }
}
