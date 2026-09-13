package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.EventPolicy
import com.dante.zeekrcapabilitylab.sentry.EventSnapshot
import com.dante.zeekrcapabilitylab.sentry.MediaClockMapper
import com.dante.zeekrcapabilitylab.sentry.ModeStamp
import com.dante.zeekrcapabilitylab.sentry.SentryEventPhase
import com.dante.zeekrcapabilitylab.sentry.SentryEventStateMachine
import com.dante.zeekrcapabilitylab.sentry.SentryPersistenceState
import com.dante.zeekrcapabilitylab.sentry.SentrySeverity
import com.dante.zeekrcapabilitylab.sentry.SentryTriggerType
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerDecisionCode {
    ACCEPTED, DUPLICATE, DISARMED, STALE_SESSION, STALE_TIME, INVALID_TRIGGER,
    CLOCK_UNAVAILABLE, CLOCK_UNCERTAIN, EVENT_FINALIZING,
}
@Serializable
data class TriggerDecision(
    val code: TriggerDecisionCode,
    val eventId: Long? = null,
    val severity: SentrySeverity? = null,
    val mediaPtsUs: Long? = null,
    val observationMediaPtsUs: Long? = null,
    val clockUncertaintyUs: Long? = null,
)

/**
 * Preparation for the serialized recorder owner: authenticate provider/type, map clocks, dedup,
 * merge, extend and cap ONE logical event. It does not start a recorder or claim to save an MP4.
 * Call process + receive + advance on the same owner, with decision-time monotonic timestamps.
 */
class SentryTriggerCoordinator(
    private val stamp: ModeStamp,
    private val detectorVersion: String,
    private val ruleConfigVersion: String,
    eventPolicy: EventPolicy = EventPolicy(),
    private val dedupUs: Long = 2_000_000,
    private val maxTriggerAgeUs: Long = 2_000_000,
    private val maxClockUncertaintyUs: Long = 250_000,
    trustedImpactProviders: Set<String> = emptySet(),
) {
    private val trustedImpactProviders = trustedImpactProviders.toSet()
    private val machine = SentryEventStateMachine(eventPolicy).also { it.arm(stamp) }
    private val clock = MediaClockMapper()
    private var active = true
    private var lastAppliedUs = -1L
    private val lastTriggers = linkedMapOf<String, Long>()
    private var evidenceEventId: Long? = null
    private val eventLanes = mutableSetOf<Int>()
    private val eventTypes = mutableSetOf<SentryTriggerType>()
    val event: EventSnapshot? get() = machine.event
    val phase: SentryEventPhase get() = machine.phase
    val involvedLanes: Set<Int> get() = eventLanes.toSet()
    val triggerTypes: Set<SentryTriggerType> get() = eventTypes.toSet()

    init {
        require(dedupUs in 0..30_000_000 && maxTriggerAgeUs in 1..10_000_000 && maxClockUncertaintyUs in 0..5_000_000)
        require(this.trustedImpactProviders.size <= 4 && this.trustedImpactProviders.all {
            it.isNotBlank() && it.length <= 64 && it !in setOf("manual", "visual-risk", "visual-shake")
        })
    }

    fun anchorClock(stamp: ModeStamp, monotonicUs: Long, mediaPtsUs: Long, uncertaintyUs: Long): Boolean {
        if (!active || stamp != this.stamp || clock.anchor != null || !validTime(monotonicUs) ||
            !validTime(mediaPtsUs) || uncertaintyUs < 0) return false
        clock.anchor(monotonicUs, mediaPtsUs, uncertaintyUs)
        return true
    }
    fun observeClock(stamp: ModeStamp, monotonicUs: Long, mediaPtsUs: Long): Boolean =
        active && stamp == this.stamp && validTime(monotonicUs) && validTime(mediaPtsUs) && clock.observe(monotonicUs, mediaPtsUs)

    fun receive(signal: SentryTriggerSignal, nowMonotonicUs: Long): TriggerDecision {
        if (!active) return TriggerDecision(TriggerDecisionCode.DISARMED)
        if (signal.stamp() != stamp) return TriggerDecision(TriggerDecisionCode.STALE_SESSION)
        if (!validTime(nowMonotonicUs) || !validTime(signal.monotonicUs) || signal.monotonicUs < lastAppliedUs ||
            signal.monotonicUs > nowMonotonicUs || nowMonotonicUs - signal.monotonicUs > maxTriggerAgeUs) {
            return TriggerDecision(TriggerDecisionCode.STALE_TIME)
        }
        if (!validSignal(signal)) return TriggerDecision(TriggerDecisionCode.INVALID_TRIGGER)
        val uncertainty = clock.anchor?.uncertaintyUs
        val mapped = clock.toMediaPtsUs(signal.monotonicUs) ?: return TriggerDecision(TriggerDecisionCode.CLOCK_UNAVAILABLE)
        if (uncertainty == null || uncertainty > maxClockUncertaintyUs) return TriggerDecision(TriggerDecisionCode.CLOCK_UNCERTAIN)
        val observationPts = signal.observedAtMonotonicUs?.let { clock.toMediaPtsUs(it) }
        if (signal.observedAtMonotonicUs != null && observationPts == null) return TriggerDecision(TriggerDecisionCode.CLOCK_UNAVAILABLE)
        val key = "${signal.providerId}/${signal.type}/${signal.lanes.sorted().joinToString(",")}"
        val previous = lastTriggers[key]
        if (previous != null && signal.monotonicUs - previous < dedupUs) return TriggerDecision(TriggerDecisionCode.DUPLICATE)
        val accepted = machine.trigger(stamp, signal.type, mapped)
        lastAppliedUs = signal.monotonicUs
        if (!accepted) return TriggerDecision(TriggerDecisionCode.EVENT_FINALIZING, eventId = machine.event?.id)
        lastTriggers[key] = signal.monotonicUs
        while (lastTriggers.size > 64) lastTriggers.remove(lastTriggers.keys.first())
        val current = machine.event!!
        if (current.id != evidenceEventId) {
            evidenceEventId = current.id
            eventLanes.clear()
            eventTypes.clear()
        }
        eventLanes += signal.lanes
        eventTypes += signal.type
        return TriggerDecision(TriggerDecisionCode.ACCEPTED, current.id, current.severity, mapped, observationPts, uncertainty)
    }

    fun advance(stamp: ModeStamp, nowMonotonicUs: Long, riskPresent: Boolean): Boolean {
        if (!active || stamp != this.stamp || !validTime(nowMonotonicUs) || nowMonotonicUs < lastAppliedUs) return false
        val mapped = clock.toMediaPtsUs(nowMonotonicUs) ?: return false
        if ((clock.anchor?.uncertaintyUs ?: Long.MAX_VALUE) > maxClockUncertaintyUs) return false
        lastAppliedUs = nowMonotonicUs
        if (!riskPresent) machine.riskCleared(stamp, mapped)
        machine.tick(stamp, mapped)
        return true
    }

    /** Only the future writer's verified result may call this; the risk engine cannot say COMPLETE. */
    fun finalized(stamp: ModeStamp, eventId: Long, persistence: SentryPersistenceState): Boolean {
        if (!active || !machine.finalized(stamp, eventId, persistence)) return false
        evidenceEventId = null
        eventLanes.clear()
        eventTypes.clear()
        lastTriggers.clear()
        return true
    }
    fun disarm(): EventSnapshot? {
        active = false
        lastTriggers.clear()
        eventLanes.clear()
        eventTypes.clear()
        return machine.disarm()
    }

    private fun validSignal(signal: SentryTriggerSignal): Boolean {
        if (!signal.confidence.isFinite() || signal.confidence !in 0.0..1.0 || signal.wallEpochMs < 0 ||
            signal.lanes.any { it !in 1..4 } || signal.tracks.size > 16 || signal.tracks.any {
                it.lane !in signal.lanes || it.trackId <= 0 || !it.confidence.isFinite() || it.confidence !in 0.0..1.0 || it.score !in 0..100
            }) return false
        if (signal.observedAtMonotonicUs?.let { !validTime(it) || it > signal.monotonicUs || signal.monotonicUs - it > maxTriggerAgeUs } == true) return false
        return when (signal.providerId) {
            "manual" -> signal.type == SentryTriggerType.MANUAL
            "visual-risk" -> signal.type == SentryTriggerType.VISUAL_RISK && signal.lanes.isNotEmpty() && signal.tracks.isNotEmpty() &&
                signal.observedAtMonotonicUs != null && signal.detectorVersion == detectorVersion && signal.ruleConfigVersion == ruleConfigVersion
            "visual-shake" -> signal.type == SentryTriggerType.SUSPECTED_IMPACT && signal.lanes.isNotEmpty()
            else -> signal.providerId in trustedImpactProviders && signal.type == SentryTriggerType.TRUSTED_PHYSICAL
        }
    }
    private fun validTime(value: Long) = value in 0..Long.MAX_VALUE / 4
}
