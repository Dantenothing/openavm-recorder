package com.dante.zeekrcheck.core

/** Keep preferences and vehicle-bound plans, retire execution without claiming the car stopped. */
object CloudReset {
    const val message = "云端设置已暂停；先前车辆操作的结果未核实"
    fun pause(state: AssistantState): AssistantState = state.copy(
        guardEnabled = false, homeGuardEnabled = false, widgetSyncEnabled = false,
        plans = state.plans.map { it.copy(enabled = false, result = "重新登录后请确认并启用") },
        operationPending = false, pendingBodyAction = null,
        operationMessage = if (state.activePreparation != null || state.temperatureUpdate != null || state.operationPending) message else state.operationMessage,
        history = if (state.activePreparation != null || state.temperatureUpdate != null || state.operationPending)
            (listOf(OperationEntry(System.currentTimeMillis(), "云端连接变更", message)) + state.history).take(80) else state.history,
        activePreparation = null, temperatureUpdate = null, temperatureReceipt = null, temperatureRetryAfter = 0,
        parkingGuard = ParkingGuard(), homeGuard = HomeSentryGuard(), guardMessage = "云端规则已暂停，请在登录后重新开启",
    )
}
