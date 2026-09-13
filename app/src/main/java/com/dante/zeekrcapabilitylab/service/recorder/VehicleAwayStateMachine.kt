package com.dante.zeekrcapabilitylab.service.recorder

data class VehicleAwayPolicy(
    val confirmWindowMs: Long = 30_000L,
) {
    init {
        require(confirmWindowMs > 0L)
    }
}

enum class VehicleAwayPhase { DISARMED, ACTIVE, PENDING, CONFIRMED }

data class VehicleAwaySnapshot(
    val generation: Long = 0L,
    val phase: VehicleAwayPhase = VehicleAwayPhase.DISARMED,
    val appForeground: Boolean = true,
    val screenOn: Boolean = true,
    val mainDisplayOn: Boolean = true,
    val pendingToken: Long = 0L,
    val pendingSinceMs: Long? = null,
    val confirmAtMs: Long? = null,
    val backgroundSinceMs: Long? = null,
    val backgroundPowerOffEvidence: Boolean = false,
    val sawScreenOffWhileBackground: Boolean = false,
    val sawMainDisplayOffWhileBackground: Boolean = false,
    val powerOffEvidenceAtMs: Long? = null,
    val lastReason: String? = null,
)

sealed interface VehicleAwayAction {
    data object None : VehicleAwayAction
    data class Schedule(val generation: Long, val pendingToken: Long, val atMs: Long) : VehicleAwayAction
    data class Cancel(val reason: String) : VehicleAwayAction
    data class Confirm(val generation: Long, val reason: String) : VehicleAwayAction
}

/**
 * Pure, manual-Session-scoped vehicle-away policy.
 *
 * Camera callbacks and timers remain owned by RecorderSession. This class only
 * combines independently observed power/lifecycle signals and returns bounded,
 * generation-tagged actions, so a stale lock event cannot stop a newer Session.
 */
class VehicleAwayStateMachine(
    private val policy: VehicleAwayPolicy = VehicleAwayPolicy(),
) {
    var snapshot: VehicleAwaySnapshot = VehicleAwaySnapshot()
        private set

    fun beginManualSession(generation: Long) {
        snapshot = VehicleAwaySnapshot(
            generation = generation,
            phase = VehicleAwayPhase.ACTIVE,
        )
    }

    fun endSession(generation: Long, reason: String) {
        if (!isCurrent(generation)) return
        snapshot = snapshot.copy(
            phase = VehicleAwayPhase.DISARMED,
            pendingToken = snapshot.pendingToken + 1L,
            pendingSinceMs = null,
            confirmAtMs = null,
            lastReason = reason,
        )
    }

    fun onAppForeground(generation: Long, foreground: Boolean, nowMs: Long): VehicleAwayAction {
        return onPowerSnapshot(
            generation = generation,
            appForeground = foreground,
            interactive = snapshot.screenOn,
            mainDisplayOn = snapshot.mainDisplayOn,
            nowMs = nowMs,
        )
    }

    fun onScreenPower(generation: Long, screenOn: Boolean, nowMs: Long): VehicleAwayAction {
        return onPowerSnapshot(
            generation = generation,
            appForeground = snapshot.appForeground,
            interactive = screenOn,
            mainDisplayOn = snapshot.mainDisplayOn,
            nowMs = nowMs,
        )
    }

    fun onMainDisplayPower(generation: Long, displayOn: Boolean, nowMs: Long): VehicleAwayAction {
        return onPowerSnapshot(
            generation = generation,
            appForeground = snapshot.appForeground,
            interactive = snapshot.screenOn,
            mainDisplayOn = displayOn,
            nowMs = nowMs,
        )
    }

    /**
     * Atomically reconciles cached lifecycle/power state with a fresh Android snapshot.
     * Broadcasts and display callbacks are wake-up hints; this value is the decision input.
     */
    fun onPowerSnapshot(
        generation: Long,
        appForeground: Boolean,
        interactive: Boolean,
        mainDisplayOn: Boolean,
        nowMs: Long,
    ): VehicleAwayAction {
        if (!accepts(generation)) return VehicleAwayAction.None
        val enteringBackground = snapshot.appForeground && !appForeground
        snapshot = if (appForeground) {
            snapshot.copy(
                appForeground = true,
                screenOn = interactive,
                mainDisplayOn = mainDisplayOn,
                backgroundSinceMs = null,
                backgroundPowerOffEvidence = false,
                sawScreenOffWhileBackground = false,
                sawMainDisplayOffWhileBackground = false,
                powerOffEvidenceAtMs = null,
            )
        } else {
            snapshot.copy(
                appForeground = false,
                screenOn = interactive,
                mainDisplayOn = mainDisplayOn,
                backgroundSinceMs = if (enteringBackground) nowMs else snapshot.backgroundSinceMs,
            )
        }
        if (!appForeground) latchBackgroundPowerOffEvidence(nowMs)
        return when {
            appForeground -> cancelPending("APP_FOREGROUND")
            interactive -> cancelPending("SCREEN_ON")
            mainDisplayOn -> cancelPending("MAIN_DISPLAY_ON")
            else -> armIfReady(nowMs)
        }
    }

    fun onCameraLoss(generation: Long): VehicleAwayAction {
        return onBackgroundResourceLoss(generation, "CAMERA_LOSS")
    }

    /**
     * USB power can disappear before Camera2 disconnects or the away timer expires.
     * A fallback must not start a fresh internal recorder during that shutdown.
     * Recheck after cleanup too: the power edge can arrive while USB is draining.
     */
    fun onUsbFallback(generation: Long): VehicleAwayAction {
        return onBackgroundResourceLoss(generation, "USB_FALLBACK")
    }

    private fun onBackgroundResourceLoss(generation: Long, reason: String): VehicleAwayAction {
        if (!isCurrent(generation)) {
            return VehicleAwayAction.None
        }
        return when {
            snapshot.phase == VehicleAwayPhase.PENDING -> confirm("${reason}_DURING_PENDING")
            snapshot.phase == VehicleAwayPhase.ACTIVE &&
                !snapshot.appForeground && snapshot.backgroundPowerOffEvidence -> {
                confirm("${reason}_AFTER_BACKGROUND_POWER_OFF")
            }
            else -> VehicleAwayAction.None
        }
    }

    fun onTimer(generation: Long, pendingToken: Long, nowMs: Long): VehicleAwayAction {
        if (!isCurrent(generation) || snapshot.phase != VehicleAwayPhase.PENDING ||
            pendingToken != snapshot.pendingToken
        ) {
            return VehicleAwayAction.None
        }
        val confirmAt = snapshot.confirmAtMs ?: return VehicleAwayAction.None
        if (nowMs < confirmAt) return VehicleAwayAction.None
        if (!signalsHold()) return cancelPending("POWER_SIGNALS_WITHDRAWN")
        return confirm("POWER_SIGNALS_STABLE")
    }

    fun nextWakeAtMs(): Long? = if (snapshot.phase == VehicleAwayPhase.PENDING) {
        snapshot.confirmAtMs
    } else {
        null
    }

    private fun armIfReady(nowMs: Long): VehicleAwayAction {
        if (!signalsHold() || snapshot.phase == VehicleAwayPhase.PENDING) return VehicleAwayAction.None
        val token = snapshot.pendingToken + 1L
        val confirmAt = nowMs + policy.confirmWindowMs
        snapshot = snapshot.copy(
            phase = VehicleAwayPhase.PENDING,
            pendingToken = token,
            pendingSinceMs = nowMs,
            confirmAtMs = confirmAt,
            lastReason = "POWER_SIGNALS_DETECTED",
        )
        return VehicleAwayAction.Schedule(snapshot.generation, token, confirmAt)
    }

    /**
     * A lock/sleep power edge may bounce back to ON before the HAL reports Camera
     * disconnect. Preserve that history for the current background episode so the
     * later Camera loss is not misclassified as an OEM-camera interruption.
     */
    private fun latchBackgroundPowerOffEvidence(nowMs: Long) {
        if (snapshot.appForeground) return
        val screenEvidence = !snapshot.screenOn
        val displayEvidence = !snapshot.mainDisplayOn
        if (!screenEvidence && !displayEvidence) return
        snapshot = snapshot.copy(
            backgroundPowerOffEvidence = true,
            sawScreenOffWhileBackground = snapshot.sawScreenOffWhileBackground || screenEvidence,
            sawMainDisplayOffWhileBackground =
                snapshot.sawMainDisplayOffWhileBackground || displayEvidence,
            powerOffEvidenceAtMs = snapshot.powerOffEvidenceAtMs ?: nowMs,
            lastReason = if (snapshot.phase == VehicleAwayPhase.ACTIVE) {
                "BACKGROUND_POWER_OFF_EVIDENCE"
            } else {
                snapshot.lastReason
            },
        )
    }

    private fun cancelPending(reason: String): VehicleAwayAction {
        if (snapshot.phase != VehicleAwayPhase.PENDING) return VehicleAwayAction.None
        snapshot = snapshot.copy(
            phase = VehicleAwayPhase.ACTIVE,
            pendingToken = snapshot.pendingToken + 1L,
            pendingSinceMs = null,
            confirmAtMs = null,
            lastReason = reason,
        )
        return VehicleAwayAction.Cancel(reason)
    }

    private fun confirm(reason: String): VehicleAwayAction.Confirm {
        snapshot = snapshot.copy(
            phase = VehicleAwayPhase.CONFIRMED,
            confirmAtMs = null,
            lastReason = reason,
        )
        return VehicleAwayAction.Confirm(snapshot.generation, reason)
    }

    private fun signalsHold(): Boolean = !snapshot.appForeground &&
        !snapshot.screenOn && !snapshot.mainDisplayOn

    private fun accepts(generation: Long): Boolean = isCurrent(generation) &&
        snapshot.phase in setOf(VehicleAwayPhase.ACTIVE, VehicleAwayPhase.PENDING)

    private fun isCurrent(generation: Long): Boolean = generation == snapshot.generation
}
