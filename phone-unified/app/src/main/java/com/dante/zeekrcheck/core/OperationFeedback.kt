package com.dante.zeekrcheck.core

/** Explicit receipt-only actions must not wait for an unrelated physical-state change. */
object OperationFeedback {
    // The tailgate command unlocks its latch; trunkOpenStatus reports opening, not that latch.
    // Keep this allowlist explicit: missing observation alone must not relax locking/other actions.
    private val receiptOnlyActions = setOf(BodyAction.HORN, BodyAction.TRUNK_UNLOCK)
    fun receiptOnly(command: VehicleCommand) = (command as? VehicleCommand.Body)?.action in receiptOnlyActions

    fun requiresAcknowledgement(command: VehicleCommand, result: CommandResult) =
        result == CommandResult.UNKNOWN && !receiptOnly(command)

    fun message(command: VehicleCommand, result: CommandResult, title: String = command.title): String =
        when ((command as? VehicleCommand.Body)?.action) {
        BodyAction.HORN -> when (result) {
            CommandResult.MATCHED, CommandResult.ACCEPTED -> "找车请求已受理 · 请留意车辆鸣笛"
            CommandResult.UNKNOWN -> "找车结果未确认 · 不会自动重发，可继续其他操作"
            CommandResult.REJECTED -> "找车请求未受理 · 可稍后重试"
        }
        BodyAction.TRUNK_UNLOCK -> when (result) {
            CommandResult.MATCHED, CommandResult.ACCEPTED -> "尾门解锁请求已受理 · 未抬起可按车尾按键"
            CommandResult.UNKNOWN -> "尾门解锁结果未确认 · 未重发，可继续其他操作"
            CommandResult.REJECTED -> "尾门解锁请求未受理 · 可稍后重试"
        }
        else -> "${title.take(95)} · ${result.label}"
    }

    fun recover(state: AssistantState): AssistantState {
        if (!state.operationPending) return state
        // Older builds did not persist the command type. Only recognize an exact sending/result message,
        // never infer it from unrelated or potentially stale history.
        val action = state.pendingBodyAction ?: receiptOnlyActions.firstOrNull {
            state.operationMessage == "正在发送：${it.title}" || state.operationMessage == "${it.title} · ${CommandResult.UNKNOWN.label}"
        }
        return if (action in receiptOnlyActions) state.copy(operationPending = false,
            pendingBodyAction = null, operationMessage = message(VehicleCommand.Body(action!!), CommandResult.UNKNOWN)) else state
    }
}
