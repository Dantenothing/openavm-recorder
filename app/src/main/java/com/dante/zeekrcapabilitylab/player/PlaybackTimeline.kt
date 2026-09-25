package com.dante.zeekrcapabilitylab.player

data class PlaybackSeek(val index: Int, val positionMs: Long)

/** File order is fixed for one dialog. Late sidecar hints cannot overwrite actual track durations. */
class PlaybackTimeline(count: Int) {
    private val durations = MutableList(count) { 0L }
    private val confirmed = BooleanArray(count)
    fun hint(index: Int, durationMs: Long) {
        if (index in durations.indices && !confirmed[index] && durationMs > 0) durations[index] = durationMs
    }
    fun confirm(index: Int, durationMs: Long) {
        if (index in durations.indices && durationMs > 0) {
            durations[index] = durationMs
            confirmed[index] = true
        }
    }
    fun durations(): List<Long> = durations.toList()
    fun total(): Long = durations.sum()
    fun position(index: Int, localMs: Long): Long = durations.take(index.coerceIn(0, durations.size)).sum() + localMs.coerceAtLeast(0L)
    fun seek(globalMs: Long): PlaybackSeek? {
        if (durations.isEmpty()) return null
        var remaining = globalMs.coerceIn(0L, total())
        for (index in durations.indices) {
            val duration = durations[index]
            // An unresolved item is a boundary, not a zero-length item to silently skip.
            if (duration <= 0L || remaining < duration || index == durations.lastIndex) {
                return PlaybackSeek(index, if (duration > 0L) remaining.coerceAtMost(duration) else 0L)
            }
            remaining -= duration
        }
        return null
    }
}
