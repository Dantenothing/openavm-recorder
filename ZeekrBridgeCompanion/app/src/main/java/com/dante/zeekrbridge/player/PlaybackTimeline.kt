package com.dante.zeekrbridge.player

import com.dante.zeekrbridge.core.IndexedLayoutKind

fun canPreparePlayback(
    layoutKind: IndexedLayoutKind,
    customSurfaceAttached: Boolean,
): Boolean = layoutKind != IndexedLayoutKind.FOUR_LANE_V1 || customSurfaceAttached

data class PlaylistPosition(
    val mediaItemIndex: Int,
    val positionMs: Long,
)

class PlaybackTimeline(durationsMs: List<Long>) {
    val durationsMs: List<Long> = durationsMs.map { it.coerceAtLeast(0L) }
    val totalDurationMs: Long = this.durationsMs.sum()

    fun globalPosition(mediaItemIndex: Int, positionMs: Long): Long {
        if (durationsMs.isEmpty()) return 0L
        val index = mediaItemIndex.coerceIn(durationsMs.indices)
        val before = durationsMs.take(index).sum()
        val local = positionMs.coerceIn(0L, durationsMs[index])
        return (before + local).coerceIn(0L, totalDurationMs)
    }

    fun resolve(globalPositionMs: Long): PlaylistPosition {
        if (durationsMs.isEmpty()) return PlaylistPosition(0, 0L)
        val target = globalPositionMs.coerceIn(0L, totalDurationMs)
        var elapsed = 0L
        durationsMs.forEachIndexed { index, duration ->
            val end = elapsed + duration
            if (target < end || index == durationsMs.lastIndex) {
                return PlaylistPosition(index, (target - elapsed).coerceIn(0L, duration))
            }
            elapsed = end
        }
        return PlaylistPosition(durationsMs.lastIndex, durationsMs.last())
    }
}
