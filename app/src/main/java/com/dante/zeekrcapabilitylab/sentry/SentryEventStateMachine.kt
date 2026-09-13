package com.dante.zeekrcapabilitylab.sentry

enum class SentryEventPhase { ARMED_IDLE, WATCH, EVENT_ACTIVE, HOLD, FINALIZING, DEGRADED, FAULT }
data class EventPolicy(
    val softPostUs: Long = 60_000_000,
    val hardPostUs: Long = 120_000_000,
    val mergeGapUs: Long = 30_000_000,
    val eventCapUs: Long = 300_000_000,
) {
    init { require(softPostUs > 0 && hardPostUs >= softPostUs && mergeGapUs > 0 && eventCapUs >= hardPostUs) }
}
data class EventSnapshot(
    val id: Long,
    val severity: SentrySeverity,
    val firstTriggerPtsUs: Long,
    val lastTriggerPtsUs: Long,
    val postUntilPtsUs: Long,
    val capPtsUs: Long,
    val holdUntilPtsUs: Long? = null,
    val continuationRequired: Boolean = false,
)

/** Receives mapped media timestamps only. One event/writer at a time. */
class SentryEventStateMachine(private val policy: EventPolicy = EventPolicy()) {
    var phase = SentryEventPhase.ARMED_IDLE
        private set
    var event: EventSnapshot? = null
        private set
    var lastPersistenceResult: SentryPersistenceState? = null
        private set
    private var stamp: ModeStamp? = null
    private var nextId = 0L
    private var lastPtsUs = Long.MIN_VALUE

    fun arm(stamp: ModeStamp) {
        check(this.stamp == null && event == null) { "Finalize/disarm the previous event first" }
        this.stamp = stamp
        phase = SentryEventPhase.ARMED_IDLE
        lastPtsUs = Long.MIN_VALUE
    }

    fun watch(stamp: ModeStamp) {
        if (this.stamp == stamp && phase == SentryEventPhase.ARMED_IDLE) phase = SentryEventPhase.WATCH
    }

    fun trigger(stamp: ModeStamp, type: SentryTriggerType, mediaPtsUs: Long): Boolean {
        if (!accept(stamp, mediaPtsUs) || phase in setOf(SentryEventPhase.FINALIZING, SentryEventPhase.FAULT)) return false
        val severity = if (type == SentryTriggerType.TRUSTED_PHYSICAL) SentrySeverity.HARD else SentrySeverity.SOFT
        val old = event
        val effective = if (old?.severity == SentrySeverity.HARD) SentrySeverity.HARD else severity
        val cap = old?.capPtsUs ?: (mediaPtsUs + policy.eventCapUs)
        if (mediaPtsUs >= cap) {
            event = old?.copy(continuationRequired = true)
            phase = SentryEventPhase.FINALIZING
            return false
        }
        val post = mediaPtsUs + if (effective == SentrySeverity.HARD) policy.hardPostUs else policy.softPostUs
        event = EventSnapshot(
            id = old?.id ?: ++nextId,
            severity = effective,
            firstTriggerPtsUs = old?.firstTriggerPtsUs ?: mediaPtsUs,
            lastTriggerPtsUs = mediaPtsUs,
            postUntilPtsUs = maxOf(old?.postUntilPtsUs ?: mediaPtsUs, post).coerceAtMost(cap),
            capPtsUs = cap,
        )
        phase = SentryEventPhase.EVENT_ACTIVE
        return true
    }

    fun riskCleared(stamp: ModeStamp, mediaPtsUs: Long) {
        if (!accept(stamp, mediaPtsUs) || phase != SentryEventPhase.EVENT_ACTIVE) return
        val old = event ?: return
        event = old.copy(holdUntilPtsUs = maxOf(old.postUntilPtsUs, mediaPtsUs + policy.mergeGapUs).coerceAtMost(old.capPtsUs))
        phase = SentryEventPhase.HOLD
    }

    fun tick(stamp: ModeStamp, mediaPtsUs: Long) {
        if (!accept(stamp, mediaPtsUs)) return
        val old = event ?: return
        if (mediaPtsUs >= old.capPtsUs) {
            event = old.copy(continuationRequired = phase == SentryEventPhase.EVENT_ACTIVE)
            phase = SentryEventPhase.FINALIZING
        } else if (phase == SentryEventPhase.EVENT_ACTIVE && mediaPtsUs >= old.postUntilPtsUs) {
            riskCleared(stamp, mediaPtsUs)
        } else if (phase == SentryEventPhase.HOLD && mediaPtsUs >= (old.holdUntilPtsUs ?: old.postUntilPtsUs)) {
            phase = SentryEventPhase.FINALIZING
        }
    }

    fun finalized(stamp: ModeStamp, eventId: Long, result: SentryPersistenceState): Boolean {
        if (this.stamp != stamp || phase != SentryEventPhase.FINALIZING || event?.id != eventId) return false
        event = null
        lastPersistenceResult = result
        phase = SentryEventPhase.ARMED_IDLE
        return true
    }

    fun disarm(): EventSnapshot? {
        stamp = null
        val pending = event
        event = null
        phase = SentryEventPhase.ARMED_IDLE
        return pending
    }

    private fun accept(stamp: ModeStamp, ptsUs: Long): Boolean {
        if (this.stamp != stamp || ptsUs < 0 || ptsUs < lastPtsUs) return false
        lastPtsUs = ptsUs
        return true
    }
}
