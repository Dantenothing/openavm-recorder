package com.dante.zeekrcapabilitylab.sentry

enum class SignalValue { ON, OFF, UNKNOWN }
enum class VehiclePresencePhase { UNKNOWN, OCCUPIED, AWAY_PENDING, AWAY_CONFIRMED, RETURN_PENDING }
data class PresenceSignals(
    val appForeground: SignalValue,
    val screen: SignalValue,
    val mainDisplay: SignalValue,
    val observedAtMs: Long,
) {
    val away get() = appForeground == SignalValue.OFF && screen == SignalValue.OFF && mainDisplay == SignalValue.OFF
    // Conservative canary candidate; subject to vehicle acceptance before automatic use.
    val returned get() = appForeground == SignalValue.ON && screen == SignalValue.ON && mainDisplay == SignalValue.ON
}
data class PresencePolicy(
    val awayMs: Long = 30_000, val returnMs: Long = 5_000, val maxSnapshotAgeMs: Long = 2_000,
    val returnRequiresForeground: Boolean = true,
    val maxObservationGapMs: Long = Long.MAX_VALUE,
) {
    init { require(awayMs > 0 && returnMs > 0 && maxSnapshotAgeMs > 0 && maxObservationGapMs > 0) }
    companion object {
        // All three OFF signals already follow the vehicle's shutdown delay. Do not
        // spend another 30 seconds waiting while the OEM suspends the normal recorder.
        fun integrated() = PresencePolicy(awayMs = 1_000, returnRequiresForeground = false,
            maxObservationGapMs = 3_000)
    }
}
data class PresenceDeadline(val generation: Long, val token: Long, val atMs: Long)

/** Timers must supply a fresh snapshot. Cached or unknown power evidence cannot confirm presence. */
class VehiclePresenceStateMachine(private val policy: PresencePolicy = PresencePolicy()) {
    var phase = VehiclePresencePhase.UNKNOWN
        private set
    var deadline: PresenceDeadline? = null
        private set
    private var permit = RunPermit()
    private var token = 0L
    private var lastAtMs = Long.MIN_VALUE
    private var confirmedAway = false

    fun begin(permit: RunPermit) {
        this.permit = permit
        phase = VehiclePresencePhase.UNKNOWN
        deadline = null
        confirmedAway = false
        lastAtMs = Long.MIN_VALUE
        token++
    }

    fun end() {
        permit = permit.copy(state = RunPermitState.DISARMED)
        deadline = null
        phase = VehiclePresencePhase.UNKNOWN
        token++
    }

    fun observe(generation: Long, signals: PresenceSignals, nowMs: Long): VehiclePresencePhase {
        if (!permit.accepts(generation) || signals.observedAtMs < lastAtMs || signals.observedAtMs > nowMs) return phase
        if (lastAtMs != Long.MIN_VALUE && signals.observedAtMs - lastAtMs > policy.maxObservationGapMs && deadline != null) {
            // elapsedRealtime advances during deep sleep. An expired deadline after a
            // sampling gap is not proof that OFF/ON remained continuously observed.
            deadline = null
            phase = if (confirmedAway) VehiclePresencePhase.AWAY_CONFIRMED else VehiclePresencePhase.UNKNOWN
            token++
        }
        lastAtMs = signals.observedAtMs
        val fresh = signals.observedAtMs <= nowMs && nowMs - signals.observedAtMs <= policy.maxSnapshotAgeMs
        val returned = signals.screen == SignalValue.ON && signals.mainDisplay == SignalValue.ON &&
            (!policy.returnRequiresForeground || signals.appForeground == SignalValue.ON)
        val candidate = when {
            fresh && signals.away && !confirmedAway -> VehiclePresencePhase.AWAY_PENDING
            fresh && returned && confirmedAway -> VehiclePresencePhase.RETURN_PENDING
            else -> null
        }
        if (candidate == null) {
            deadline = null
            token++
            phase = when {
                confirmedAway -> VehiclePresencePhase.AWAY_CONFIRMED
                fresh && returned -> VehiclePresencePhase.OCCUPIED
                else -> VehiclePresencePhase.UNKNOWN
            }
        } else if (phase != candidate) {
            phase = candidate
            deadline = PresenceDeadline(generation, ++token, nowMs + if (confirmedAway) policy.returnMs else policy.awayMs)
        }
        return phase
    }

    fun onTimer(expected: PresenceDeadline, freshSignals: PresenceSignals, nowMs: Long): VehiclePresencePhase {
        if (deadline != expected || !permit.accepts(expected.generation) || nowMs < expected.atMs) return phase
        if (freshSignals.observedAtMs < lastAtMs || freshSignals.observedAtMs > nowMs) return phase
        observe(expected.generation, freshSignals, nowMs)
        if (deadline != expected) return phase
        confirmedAway = phase == VehiclePresencePhase.AWAY_PENDING
        phase = if (confirmedAway) VehiclePresencePhase.AWAY_CONFIRMED else VehiclePresencePhase.OCCUPIED
        deadline = null
        token++
        return phase
    }
}
