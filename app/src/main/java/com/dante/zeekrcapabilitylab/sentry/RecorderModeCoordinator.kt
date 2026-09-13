package com.dante.zeekrcapabilitylab.sentry

sealed interface ModeEffect {
    data class Acquire(val ticket: CameraLeaseTicket) : ModeEffect
    data class Release(val ticket: CameraLeaseTicket) : ModeEffect
    /** A late open callback must close its own resource, never another owner's camera. */
    data class CloseStaleResource(val ticket: CameraLeaseTicket) : ModeEffect
}

/** S0 control contract. Call on one control executor; no Android or IO operations. */
class RecorderModeCoordinator(private val featureAvailable: Boolean = false) {
    val lease = ExclusiveCameraLease()
    var permit = RunPermit()
        private set
    var policy = GuardPolicy.OFF
        private set
    var phase = RecorderModePhase.STOPPED
        private set
    var desiredMode: CaptureMode? = null
        private set
    var stopReason: SentryStopReason? = null
        private set
    var parkingBehavior = ParkingBehavior.SENTRY
        private set
    private var transition = 0L
    val stamp get() = ModeStamp(permit.generation, transition)

    fun setPolicy(value: GuardPolicy): List<ModeEffect> {
        val wasAuto = policy == GuardPolicy.AUTO
        policy = value
        return if (wasAuto && value == GuardPolicy.OFF) stop(SentryStopReason.MASTER_OFF) else emptyList()
    }

    fun explicitStart(parking: ParkingBehavior = ParkingBehavior.SENTRY): List<ModeEffect> {
        if (!featureAvailable || policy != GuardPolicy.AUTO ||
            permit.state == RunPermitState.ARMED || lease.snapshot != null
        ) return emptyList()
        permit = RunPermit(RunPermitState.ARMED, permit.generation + 1)
        parkingBehavior = parking
        stopReason = null
        return requestMode(CaptureMode.NORMAL)
    }

    fun presence(generation: Long, presence: VehiclePresencePhase): List<ModeEffect> {
        if (!permit.accepts(generation) || phase in setOf(RecorderModePhase.DEGRADED, RecorderModePhase.FAULT)) {
            return emptyList()
        }
        return when (presence) {
            VehiclePresencePhase.AWAY_CONFIRMED -> requestMode(
                if (parkingBehavior == ParkingBehavior.SENTRY) CaptureMode.SENTRY else null)
            VehiclePresencePhase.OCCUPIED -> requestMode(CaptureMode.NORMAL)
            else -> emptyList()
        }
    }

    private fun requestMode(mode: CaptureMode?): List<ModeEffect> {
        if (desiredMode == mode) return emptyList()
        desiredMode = mode
        transition++
        phase = if (mode == null) RecorderModePhase.TRANSITION_TO_AWAKE_IDLE
        else if (mode == CaptureMode.SENTRY) RecorderModePhase.TRANSITION_TO_SENTRY
        else if (lease.snapshot == null) RecorderModePhase.STARTING_NORMAL else RecorderModePhase.TRANSITION_TO_NORMAL
        val owned = lease.snapshot
        return if (owned != null) {
            if (lease.beginRelease(owned.ticket)) listOf(ModeEffect.Release(owned.ticket)) else emptyList()
        } else acquireDesired()
    }

    private fun acquireDesired(): List<ModeEffect> {
        if (!permit.accepts(permit.generation) || policy != GuardPolicy.AUTO) return emptyList()
        // An armed idle run owns no camera. Only an exact close acknowledgement gets here.
        val mode = desiredMode ?: run {
            if (lease.snapshot == null) phase = RecorderModePhase.AWAKE_IDLE
            return emptyList()
        }
        return lease.acquire(stamp, mode)?.let { listOf(ModeEffect.Acquire(it)) } ?: emptyList()
    }

    fun acquired(ticket: CameraLeaseTicket): List<ModeEffect> {
        if (permit.accepts(ticket.stamp.run) && ticket.stamp == stamp &&
            lease.snapshot == CameraLeaseSnapshot(ticket, CameraLeasePhase.ACTIVE)
        ) return emptyList()
        if (!permit.accepts(ticket.stamp.run) || ticket.stamp != stamp || !lease.acquired(ticket)) {
            return listOf(ModeEffect.CloseStaleResource(ticket))
        }
        phase = if (ticket.mode == CaptureMode.NORMAL) RecorderModePhase.NORMAL_ACTIVE else RecorderModePhase.SENTRY_ACTIVE
        return emptyList()
    }

    fun released(ticket: CameraLeaseTicket): List<ModeEffect> {
        // Cleanup acknowledgements remain valid for their exact retired lease, even after disarming.
        if (!lease.released(ticket)) return emptyList()
        if (permit.state == RunPermitState.DISARMED) {
            if (phase != RecorderModePhase.FAULT) phase = RecorderModePhase.STOPPED
            return emptyList()
        }
        return acquireDesired()
    }

    fun fault(ticket: CameraLeaseTicket, reason: SentryStopReason): List<ModeEffect> {
        if (lease.snapshot?.ticket != ticket || ticket.stamp != stamp || !permit.accepts(ticket.stamp.run)) {
            return emptyList()
        }
        return stop(reason)
    }

    fun stop(reason: SentryStopReason = SentryStopReason.MANUAL_STOP): List<ModeEffect> {
        // Authority is revoked atomically before any returned teardown effect can execute.
        permit = RunPermit(RunPermitState.DISARMED, permit.generation + 1)
        transition++
        desiredMode = null
        stopReason = reason
        phase = if (reason in setOf(SentryStopReason.MANUAL_STOP, SentryStopReason.MASTER_OFF, SentryStopReason.RUN_LIMIT)) {
            if (lease.snapshot == null) RecorderModePhase.STOPPED else RecorderModePhase.STOPPING
        } else RecorderModePhase.FAULT
        val ticket = lease.snapshot?.ticket ?: return emptyList()
        return if (lease.beginRelease(ticket)) listOf(ModeEffect.Release(ticket)) else emptyList()
    }

    fun releaseTimedOut(ticket: CameraLeaseTicket): List<ModeEffect> =
        if (lease.snapshot == CameraLeaseSnapshot(ticket, CameraLeasePhase.RELEASING)) {
            stop(SentryStopReason.RELEASE_TIMEOUT)
        } else emptyList()
}
