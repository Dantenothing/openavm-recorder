package com.dante.zeekrcapabilitylab.service.recorder

/** One destination, one bounded cut. Storage admission cannot consume the keyframe deadline. */
internal class DirectCutProgress(private val requestedAtMs: Long) {
    enum class Phase { PREPARING_DESTINATION, PREPARING_MUXER, WAITING_KEYFRAME, WRITING_BOUNDARY, COMPLETE, CANCELLED }
    data class Snapshot(val phase: Phase, val totalMs: Long, val phaseMs: Long) {
        fun facts() = mapOf("cutPhase" to phase.name, "cutTotalMs" to totalMs.toString(), "cutPhaseMs" to phaseMs.toString())
    }
    private var phase = Phase.PREPARING_DESTINATION
    private var phaseAtMs = requestedAtMs
    @Synchronized fun advance(next: Phase, now: Long) {
        if (phase in setOf(Phase.COMPLETE, Phase.CANCELLED)) return
        check(next.ordinal > phase.ordinal && now >= phaseAtMs) { "DIRECT_CUT_STAGE_REGRESSED" }
        phase = next; phaseAtMs = now
    }
    @Synchronized fun cancel(now: Long) { advance(Phase.CANCELLED, now) }
    @Synchronized fun snapshot(now: Long) = Snapshot(phase, (now - requestedAtMs).coerceAtLeast(0), (now - phaseAtMs).coerceAtLeast(0))
    @Synchronized fun problem(now: Long): String? {
        val limit = if (phase == Phase.PREPARING_DESTINATION) 30_000 else 8_000
        if (now - phaseAtMs < limit) return null
        return when (phase) {
            Phase.PREPARING_DESTINATION -> "CONTINUOUS_OUTPUT_PREPARATION_TIMEOUT"
            Phase.PREPARING_MUXER -> "CONTINUOUS_MUXER_PREPARATION_TIMEOUT"
            Phase.WAITING_KEYFRAME -> "CONTINUOUS_KEYFRAME_CUT_TIMEOUT"
            Phase.WRITING_BOUNDARY -> "CONTINUOUS_WRITER_CUT_TIMEOUT"
            Phase.COMPLETE, Phase.CANCELLED -> null
        }
    }
    companion object { val FACT_KEYS = setOf("cutPhase", "cutTotalMs", "cutPhaseMs") }
}
