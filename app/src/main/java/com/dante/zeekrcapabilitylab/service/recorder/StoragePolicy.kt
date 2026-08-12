package com.dante.zeekrcapabilitylab.service.recorder

/**
 * Pure-Kotlin circular-storage selection logic.
 *
 * Eviction is allowed only for finalized `.mp4` segments that belong to this
 * recorder directory, have a parseable sidecar, and are not protected. Partial
 * files, unknown files, and protected/bookmarked segments are never selected.
 */
data class ManagedSegmentFile(
    val path: String,
    val bytes: Long,
    val lastModifiedMs: Long,
    val isFinalMp4: Boolean,
    val hasSidecar: Boolean,
    val protected: Boolean,
    /** Temporary upload pin; eviction treats it like [protected]. */
    val uploadPinned: Boolean = false,
    /** Sidecar/health analysis still in flight; never evict while it could be enriched. */
    val analysisInFlight: Boolean = false,
    /** An open playback surface owns this file until the player is dismissed. */
    val playing: Boolean = false,
)

object StoragePolicy {

    /** Safety reserve kept free beyond the estimated next segment (filesystem slack, muxing). */
    const val SAFETY_RESERVE_BYTES = 256L * 1024L * 1024L

    fun currentUsageBytes(files: List<ManagedSegmentFile>): Long =
        files.filter { it.isFinalMp4 }.sumOf { it.bytes }

    /** Returns the paths to delete (oldest first) so usage ends at [limitBytes]; the caller deletes mp4+sidecar. */
    fun selectEvictions(files: List<ManagedSegmentFile>, limitBytes: Long): List<String> {
        return selectEvictionsToTarget(files, limitBytes)
    }

    /**
     * Evicts oldest unprotected finalized segments until usage fits below
     * [targetBytes]. Files without a sidecar, partials, and protected/bookmarked
     * segments are never selected. The caller deletes the mp4 and its sidecar.
     */
    fun selectEvictionsToTarget(files: List<ManagedSegmentFile>, targetBytes: Long): List<String> {
        if (targetBytes < 0L) return emptyList()
        var used = currentUsageBytes(files)
        if (used <= targetBytes) return emptyList()
        val candidates = files
            .filter {
                it.isFinalMp4 && it.hasSidecar &&
                    !it.protected && !it.uploadPinned && !it.analysisInFlight && !it.playing
            }
            .sortedBy { it.lastModifiedMs }
        val evictions = mutableListOf<String>()
        for (candidate in candidates) {
            if (used <= targetBytes) break
            evictions += candidate.path
            used -= candidate.bytes
        }
        return evictions
    }

    /** Rough worst-case bytes for one segment at the requested bitrate. */
    fun estimateSegmentBytes(bitrateBps: Int, segmentSeconds: Int): Long {
        if (bitrateBps <= 0 || segmentSeconds <= 0) return 0L
        return bitrateBps.toLong() * segmentSeconds / 8L
    }

    /**
     * Pure decision run before every segment. [availableBytes] < 0 means "unknown"
     * (device could not report it) and does not block recording.
     */
    fun canStartSegment(
        usageBytes: Long,
        limitBytes: Long,
        estimatedBytes: Long,
        availableBytes: Long,
        reserveBytes: Long = SAFETY_RESERVE_BYTES,
    ): StorageDecision {
        if (limitBytes > 0L && usageBytes + estimatedBytes > limitBytes) {
            return StorageDecision(
                proceed = false,
                reason = "STORAGE_CAP_EXCEEDED usage=${usageBytes} estimated=${estimatedBytes} limit=${limitBytes}",
            )
        }
        if (availableBytes >= 0L && availableBytes < estimatedBytes + reserveBytes) {
            return StorageDecision(
                proceed = false,
                reason = "DISK_SPACE_LOW available=${availableBytes} required=${estimatedBytes + reserveBytes}",
            )
        }
        return StorageDecision(proceed = true, reason = null)
    }
}

data class StorageDecision(
    val proceed: Boolean,
    val reason: String?,
)
