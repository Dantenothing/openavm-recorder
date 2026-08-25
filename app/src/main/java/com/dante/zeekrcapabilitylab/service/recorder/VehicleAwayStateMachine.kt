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
        if (!accepts(generation)) return VehicleAwayAction.None
        snapshot = snapshot.copy(appForeground = foreground)
        return if (foreground) cancelPending("APP_FOREGROUND") else armIfReady(nowMs)
    }

    fun onScreenPower(generation: Long, screenOn: Boolean, nowMs: Long): VehicleAwayAction {
        if (!accepts(generation)) return VehicleAwayAction.None
        snapshot = snapshot.copy(screenOn = screenOn)
        return if (screenOn) cancelPending("SCREEN_ON") else armIfReady(nowMs)
    }

    fun onMainDisplayPower(generation: Long, displayOn: Boolean, nowMs: Long): VehicleAwayAction {
        if (!accepts(generation)) return VehicleAwayAction.None
        snapshot = snapshot.copy(mainDisplayOn = displayOn)
        return if (displayOn) cancelPending("MAIN_DISPLAY_ON") else armIfReady(nowMs)
    }

    fun onCameraLoss(generation: Long): VehicleAwayAction {
        if (!isCurrent(generation) || snapshot.phase != VehicleAwayPhase.PENDING) {
            return VehicleAwayAction.None
        }
        return confirm("CAMERA_LOSS_DURING_PENDING")
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
