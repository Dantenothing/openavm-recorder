package com.dante.zeekrcapabilitylab.preflight.continuous

/** Single codec-worker owner. A cut consumes its prepared target exactly once. */
internal class ProbeCutPlan {
    data class Boundary(val cutId: Int, val nextRunPtsUs: Long, val oldDurationUs: Long)
    private var base: Long? = null
    private var last: Long? = null
    private var pending: Pair<Int, Long>? = null
    private var previousCut = 0
    private var terminal = false
    fun arm(id: Int, atOrAfterPtsUs: Long) {
        check(!terminal && pending == null && id == previousCut + 1 && atOrAfterPtsUs > (last ?: -1))
        pending = id to atOrAfterPtsUs
    }
    fun sample(ptsUs: Long, key: Boolean): Boundary? {
        check(!terminal && ptsUs >= 0 && ptsUs > (last ?: -1)) { "ENCODED_TIMESTAMP_NOT_INCREASING" }
        if (base == null) { check(key && ptsUs == 0L) { "SOURCE_PREFIX_MISSING" }; base = ptsUs }
        val p = pending
        val boundary = if (p != null && key && ptsUs >= p.second) {
            Boundary(p.first, ptsUs, ptsUs - base!!).also { base = ptsUs; previousCut = p.first; pending = null }
        } else null
        last = ptsUs
        return boundary
    }
    fun filePts(runPts: Long) = runPts - requireNotNull(base)
    fun end(endPtsUs: Long, requireAllCuts: Boolean = true): Long {
        check(!terminal && endPtsUs > (last ?: -1)); terminal = true
        check(!requireAllCuts || pending == null) { "CUT_NOT_REACHED" }
        return endPtsUs - requireNotNull(base)
    }
}
