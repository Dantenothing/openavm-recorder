package com.dante.zeekrcapabilitylab.mirror

/** A parked control window alone grants nothing; an explicit tap or one-shot opted-in return may resume. */
internal class MirrorSleepRetention {
    enum class Phase { ACTIVE, RELEASING, PAUSED, CLOSED }
    var phase = Phase.ACTIVE
        private set
    val suspended get() = phase == Phase.RELEASING || phase == Phase.PAUSED

    fun suspend(): Boolean {
        if (phase != Phase.ACTIVE) return false
        phase = Phase.RELEASING
        return true
    }

    fun observe(resources: MirrorSleepResources) {
        if (suspended) phase = if (resources.released) Phase.PAUSED else Phase.RELEASING
    }
    fun resume(userTap: Boolean, screenUsable: Boolean, windowAttached: Boolean,
               resources: MirrorSleepResources): Boolean = resumeAuthorized(userTap, screenUsable, windowAttached, resources)

    fun resumeAutomatic(decision: MirrorReturnGate.Decision, screenUsable: Boolean, windowAttached: Boolean,
                        resources: MirrorSleepResources): Boolean = resumeAuthorized(
        decision in setOf(MirrorReturnGate.Decision.PREVIEW, MirrorReturnGate.Decision.RECORD),
        screenUsable, windowAttached, resources)

    private fun resumeAuthorized(authorized: Boolean, screenUsable: Boolean, windowAttached: Boolean,
                                 resources: MirrorSleepResources): Boolean {
        observe(resources)
        if (!authorized || !screenUsable || !windowAttached || phase != Phase.PAUSED) return false
        phase = Phase.ACTIVE
        return true
    }
    fun close() { phase = Phase.CLOSED }

    /** Reattaches presentation for an already running, verified same Session; opens no camera. */
    fun restoreExistingRecording(authorized: Boolean, screenUsable: Boolean, attached: Boolean, sameSessionReady: Boolean): Boolean {
        if (!suspended || !authorized || !screenUsable || !attached || !sameSessionReady) return false
        phase = Phase.ACTIVE
        return true
    }
}

internal data class MirrorSleepResources(
    val recorderRunning: Boolean = false,
    val previewRunning: Boolean = false,
    val auxiliaryActive: Boolean = false,
    val cleanupOwners: Int = 0,
    val nativeIdle: Boolean = true,
    val glIdle: Boolean = true,
    val wakeLockHeld: Boolean = false,
) {
    val released get() = !recorderRunning && !previewRunning && !auxiliaryActive &&
        cleanupOwners == 0 && nativeIdle && glIdle && !wakeLockHeld
}
