package com.dante.zeekrcapabilitylab.service.recorder

/**
 * Pure-Kotlin retention policy for quarantined failure evidence.
 *
 * Quarantined partials (and their FAILED sidecars) are diagnostic evidence, so
 * some history is deliberately kept — but the directory must be bounded: today
 * nothing ever deletes from it, and it is excluded from the recording quota.
 *
 * Rules enforced here (and covered by JVM tests):
 *  - Evidence units are retained newest-first within [MAX_TOTAL_BYTES] and
 *    [MAX_UNITS], and nothing older than [MAX_AGE_MS] is retained.
 *  - The newest unit always survives, even when it alone breaks every limit,
 *    so the most recent failure evidence is never the first casualty.
 *  - The policy only decides among the units the caller supplies; the caller
 *    is responsible for supplying only recorder-owned quarantine files, so
 *    unknown files are never candidates.
 */
object QuarantineRetentionPolicy {

    /** Total bytes of quarantined evidence retained (newest first). */
    const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    /** Maximum number of evidence units (a partial plus its sidecar is one unit). */
    const val MAX_UNITS = 32

    /** Evidence older than this is dropped regardless of the byte budget. */
    const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    /** Orphan `.tmp` files older than this are stale write leftovers, safe to delete. */
    const val STALE_TMP_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * Returns the unit paths to delete, oldest first. The newest unit is always
     * retained; every other unit is retained only while it fits the byte
     * budget, the unit cap, and the age limit.
     */
    fun selectEvictions(
        units: List<QuarantinedEvidence>,
        nowMs: Long,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxUnits: Int = MAX_UNITS,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<String> {
        if (units.isEmpty()) return emptyList()
        val newestFirst = units.sortedWith(
            compareByDescending<QuarantinedEvidence> { it.lastModifiedMs }.thenByDescending { it.path },
        )
        val keep = HashSet<String>()
        var total = 0L
        newestFirst.forEachIndexed { index, unit ->
            val withinBudget = total + unit.totalBytes <= maxTotalBytes
            val withinCount = keep.size < maxUnits
            val withinAge = nowMs - unit.lastModifiedMs <= maxAgeMs
            if (index == 0 || (withinBudget && withinCount && withinAge)) {
                keep += unit.path
                total += unit.totalBytes
            }
        }
        return newestFirst.filter { it.path !in keep }.map { it.path }.asReversed()
    }
}

/**
 * One quarantined evidence unit as listed by the recorder: the primary file
 * (a `.partial` or final `.mp4`, or an orphaned sidecar) with the combined
 * size of the primary and its sidecar.
 */
data class QuarantinedEvidence(
    val path: String,
    val totalBytes: Long,
    val lastModifiedMs: Long,
)
