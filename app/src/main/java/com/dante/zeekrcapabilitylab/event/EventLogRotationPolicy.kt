package com.dante.zeekrcapabilitylab.event

/**
 * Pure-Kotlin rotation/retention policy for the JSONL event log.
 *
 * Rules enforced here (and covered by JVM tests):
 *  - The product event stream is bounded on disk: one active file capped at
 *    [MAX_ACTIVE_FILE_BYTES] plus rotated files retained newest-first within
 *    [MAX_ROTATED_TOTAL_BYTES].
 *  - The newest rotated file is always kept, even when it alone exceeds the
 *    byte budget, so the most recent evidence is never the first casualty.
 *  - Only names this policy produced are ever selected for deletion; explicit
 *    lab session files and unknown files are never candidates.
 */
object EventLogRotationPolicy {

    /** Rotate the active product stream once it reaches this size. */
    const val MAX_ACTIVE_FILE_BYTES = 2L * 1024L * 1024L

    /** Total bytes of rotated history retained (newest first). */
    const val MAX_ROTATED_TOTAL_BYTES = 8L * 1024L * 1024L

    /** Bounded in-memory write queue; overflow drops the oldest queued events. */
    const val MAX_PENDING_EVENTS = 512

    const val ACTIVE_FILE_NAME = "app.jsonl"
    const val ROTATED_PREFIX = "app-"
    const val ROTATED_SUFFIX = ".jsonl"

    /** Pre-rotation stream written by earlier builds; adopted as a rotated file. */
    const val LEGACY_FILE_NAME = "no-session.jsonl"

    fun shouldRotate(activeFileBytes: Long, maxBytes: Long = MAX_ACTIVE_FILE_BYTES): Boolean =
        activeFileBytes >= maxBytes

    fun rotatedName(epochMs: Long): String = "$ROTATED_PREFIX$epochMs$ROTATED_SUFFIX"

    fun isRotatedName(name: String): Boolean =
        name.startsWith(ROTATED_PREFIX) &&
            name.endsWith(ROTATED_SUFFIX) &&
            timestampOrNull(name) != null

    private fun timestampOrNull(name: String): Long? =
        name.removePrefix(ROTATED_PREFIX).removeSuffix(ROTATED_SUFFIX).toLongOrNull()

    /**
     * Rotated file names to delete, oldest first, so the retained rotated files
     * fit within [maxTotalBytes]. The newest rotated file is always retained.
     * Names that do not match the rotated pattern are never selected.
     */
    fun selectRotatedDeletions(
        files: List<RotatedLogFile>,
        maxTotalBytes: Long = MAX_ROTATED_TOTAL_BYTES,
    ): List<String> {
        val known = files
            .filter { isRotatedName(it.name) }
            .sortedByDescending { timestampOrNull(it.name) ?: 0L }
        if (known.isEmpty()) return emptyList()
        val keep = HashSet<String>()
        var total = 0L
        known.forEachIndexed { index, file ->
            if (index == 0 || total + file.bytes <= maxTotalBytes) {
                keep += file.name
                total += file.bytes
            }
        }
        return known.filter { it.name !in keep }.map { it.name }.asReversed()
    }
}

/** Name + size of one rotated log file, as listed from the events directory. */
data class RotatedLogFile(
    val name: String,
    val bytes: Long,
)
