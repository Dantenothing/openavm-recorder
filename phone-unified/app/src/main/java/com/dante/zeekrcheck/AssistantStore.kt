package com.dante.zeekrcheck

import android.content.Context
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

class AssistantStore private constructor(private val context: Context) {
    private val disk = SecureRecordStore(context, "assistant", SealedConfig.Purpose.ASSISTANT) { AssistantState.parse(it) }
    private val mutable = MutableStateFlow(runCatching { disk.load()?.let(AssistantState::parse) }.getOrNull() ?: AssistantState())
    val state = mutable.asStateFlow()
    private val addressScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var addressJob: Job? = null
    private var addressAttemptAt = 0L
    @Synchronized fun edit(transform: (AssistantState) -> AssistantState) {
        val previous = mutable.value
        val changed = transform(previous)
        val next = if (previous.home != changed.home || previous.homeRadius != changed.homeRadius ||
            previous.homeGuardEnabled != changed.homeGuardEnabled || previous.paused != changed.paused)
            changed.copy(homeGuard = changed.homeGuard.resetConfirmation()) else changed
        disk.save(next.encode()); mutable.value = next
        VehicleWidgetProvider.updateAll(context)
    }
    fun record(title: String, result: String) = edit { it.copy(history = (listOf(OperationEntry(System.currentTimeMillis(), title.take(160), result.take(180))) + it.history).take(80)) }
    fun recoverInterrupted() {
        state.value.temperatureUpdate?.let { old ->
            val next = old.interrupted(System.currentTimeMillis())
            if (next != old) edit { it.copy(temperatureUpdate = next) }
        }
        val recoveredOperation = OperationFeedback.recover(state.value)
        if (recoveredOperation != state.value) edit { recoveredOperation }
        val homeAttempt = state.value.homeGuard.takeIf { it.vehicleKey == state.value.parkingGuard.vehicleKey && !it.held }?.attemptedAt ?: 0
        val recoveredGuard = state.value.parkingGuard.afterAutomaticOff(homeAttempt)
        if (recoveredGuard != state.value.parkingGuard) edit { it.copy(parkingGuard = recoveredGuard, guardMessage = "到家后哨兵已关闭") }
        if(PreparationProgress.legacyAcceptedGate(state.value)) edit { it.copy(operationPending=false,operationMessage="备车已受理 · 自动恢复进度观察") }
        state.value.activePreparation?.let { current ->
            val recovered=PreparationProgress.interrupted(current)
            if(current!=recovered) edit { it.copy(activePreparation=recovered) }
        }
        if (state.value.operationPending) edit { it.copy(operationMessage = "上次操作中断，车辆结果待核实；不会重新发送") }
    }
    fun observePreparation(snapshot: ClimateSnapshot) {
        val current=state.value.activePreparation ?: return
        if(current.finished || current.vehicleKey!=OverviewStore.get(context).state.value.vehicleKey) return
        updatePreparation(current, PreparationProgress.observe(current,snapshot,System.currentTimeMillis()))
    }
    fun observePreparationStatus(probe: Probe) {
        val current = state.value.activePreparation ?: return
        if (current.finished || current.vehicleKey != OverviewStore.get(context).state.value.vehicleKey ||
            probe.endpoint != Endpoint.STATUS || probe.outcome != ProbeOutcome.SUCCESS) return
        val now = System.currentTimeMillis()
        // Driving takes precedence over the remote pre-climate flag becoming false.
        val vehicle = PreparationProgress.observeVehicle(current, probe, now)
        updatePreparation(current, PreparationProgress.observe(vehicle, ClimateSnapshot.parse(probe), now))
    }
    fun updatePreparation(expected: PreparationSession, next: PreparationSession) {
        if (next == expected) return
        edit { state ->
            if (state.activePreparation != expected) state else state.copy(activePreparation = next,
                history = if (!expected.finished && next.finished) (listOf(OperationEntry(
                    next.endedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), "智能备车",
                    "${PreparationProgress.title(next.phase)} · ${PreparationProgress.detail(next,System.currentTimeMillis())}".take(180))) + state.history).take(80)
                else state.history)
        }
    }
    fun updateLocation(probe: Probe) {
        val point = CarLocation.from(probe) ?: return
        edit { previous -> previous.copy(location = VehicleAddress.updated(point, previous.location)) }
        resolveLocationAddress()
    }
    private fun resolveLocationAddress() {
        val overview = OverviewStore.get(context)
        val key = overview.state.value.vehicleKey ?: return
        val epoch = overview.epoch()
        val point = state.value.location?.takeIf { it.address.isBlank() } ?: return
        if (HomeZone.area(point, state.value.home, state.value.homeRadius) in setOf(HomeZone.Area.HOME, HomeZone.Area.NEAR_HOME)) return
        val now = System.currentTimeMillis()
        if (addressJob?.isActive == true || now - addressAttemptAt in 0..59_999) return
        addressAttemptAt = now
        addressJob = addressScope.launch {
            val label = try { PlaceLookup(context).nearbyRoad(point) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (label != null && key == overview.state.value.vehicleKey && epoch == overview.epoch())
                edit { it.copy(location = VehicleAddress.resolved(it.location, point, label)) }
        }
    }
    @Synchronized fun observeGuard(key: String, probes: List<Probe>) {
        val before = state.value.parkingGuard
        val beforeHome = state.value.homeGuard
        var next = if (before.vehicleKey == key) before else ParkingGuard(vehicleKey = key)
        var nextHome = if (beforeHome.vehicleKey == key) beforeHome else HomeSentryGuard(vehicleKey = key)
        val settings = state.value
        probes.forEach {
            val now = System.currentTimeMillis()
            val oldConfirmedAt = next.journey.confirmedAt
            next = next.observe(it, now, automaticOffAt = if (nextHome.held) 0 else nextHome.attemptedAt)
            if (next.journey.confirmedAt > oldConfirmedAt) nextHome = nextHome.afterMissedJourney(next.journey)
            nextHome = nextHome.observe(it, settings.home, settings.homeRadius, settings.location?.verified == true, now)
        }
        if (before != next || beforeHome != nextHome) edit { it.copy(parkingGuard = next, homeGuard = nextHome, guardMessage = when {
            nextHome.held && !beforeHome.held -> "检测到家中哨兵重新开启 · 本次保持开启"
            next.journey.confirmedAt > before.journey.confirmedAt -> "里程已确认新一次停车 · 重新判断守护"
            next.paused && !before.paused -> "哨兵已关闭 · 本次不自动重开"
            before.paused && !next.paused -> if (nextHome.attemptedAt > 0) "到家后哨兵已关闭" else "已确认新行程 · 下次停车恢复自动守护"
            else -> it.guardMessage
        }) }
    }
    @Synchronized fun pauseGuard(key: String) {
        val current = state.value.parkingGuard.takeIf { it.vehicleKey == key } ?: ParkingGuard(vehicleKey = key)
        edit { it.copy(parkingGuard = current.pause(System.currentTimeMillis()), guardMessage = "手动暂停 · 本次停车不再自动开启") }
    }
    @Synchronized fun resumeGuard(key: String) {
        val current = state.value.parkingGuard.takeIf { it.vehicleKey == key } ?: ParkingGuard(vehicleKey = key)
        edit { it.copy(parkingGuard = current.resume(), guardMessage = "本次已恢复 · 等待有效停车证据") }
    }
    @Synchronized fun claimGuard(expected: ParkingGuard, home: CarLocation?, radius: Int): Boolean {
        val current = state.value
        if (!current.guardEnabled || current.paused || current.parkingGuard != expected || expected.paused || expected.attemptedAt > 0 ||
            current.home != home || current.homeRadius != radius) return false
        edit { it.copy(parkingGuard = expected.copy(attemptedAt = System.currentTimeMillis()), guardMessage = "正在开启哨兵 · 等待车辆确认") }
        return true
    }
    @Synchronized fun holdHomeGuard(key: String) {
        val current = state.value.homeGuard.takeIf { it.vehicleKey == key } ?: HomeSentryGuard(vehicleKey = key)
        val parking = state.value.parkingGuard.takeIf { it.vehicleKey == key } ?: ParkingGuard(vehicleKey = key)
        val now = System.currentTimeMillis()
        edit { it.copy(homeGuard = current.hold(now), parkingGuard = parking.copy(journey = parking.journey.manualChoice(now)),
            guardMessage = "手动开启优先 · 本次到家不会自动关闭") }
    }
    @Synchronized fun resumeHomeGuard(key: String) {
        val current = state.value.homeGuard.takeIf { it.vehicleKey == key } ?: HomeSentryGuard(vehicleKey = key)
        edit { it.copy(homeGuard = current.resume(), guardMessage = "已恢复到家自动关闭 · 重新确认位置与驻车") }
    }
    @Synchronized fun claimHomeGuard(expected: HomeSentryGuard, home: CarLocation?, radius: Int): Boolean {
        val current = state.value
        if (!current.homeGuardEnabled || current.paused || current.homeGuard != expected || expected.held || expected.attemptedAt > 0 ||
            current.home != home || current.homeRadius != radius || current.location?.verified != true) return false
        // Commit the attempt before the network write, including process death / unknown results.
        edit { it.copy(homeGuard = expected.copy(attemptedAt = System.currentTimeMillis()), guardMessage = "已确认到家 · 正在关闭哨兵，等待车辆回传") }
        return true
    }
    @Synchronized fun claimHomeFollowUp(): Boolean {
        val current = state.value
        if (!current.homeGuardEnabled || current.paused || !current.homeGuard.needsFollowUp(System.currentTimeMillis())) return false
        edit { it.copy(homeGuard = it.homeGuard.copy(followUps = it.homeGuard.followUps + 1)) }
        return true
    }
    @Synchronized fun claimJourneyFollowUp(): Boolean {
        val current = state.value
        if ((!current.guardEnabled && !current.homeGuardEnabled) || current.paused ||
            !current.parkingGuard.journey.needsFollowUp(System.currentTimeMillis())) return false
        edit { it.copy(parkingGuard = it.parkingGuard.copy(journey = it.parkingGuard.journey.copy(followUps = it.parkingGuard.journey.followUps + 1))) }
        return true
    }
    fun forgetVehicle() {
        addressJob?.cancel(); addressAttemptAt = 0
        state.value.plans.forEach { DepartureScheduler.cancel(context, it.id) }
        edit { it.copy(plans = emptyList(), paused = true, guardEnabled = false, homeGuardEnabled = false, location = null,
            activePreparation = null, temperatureUpdate = null, temperatureReceipt = null, temperatureRetryAfter = 0,
            operationPending = false, pendingBodyAction = null, operationMessage = "", guardMessage = "账号已更改，规则已停用",
            parkingGuard = ParkingGuard(), homeGuard = HomeSentryGuard(), carBluetoothAddress = "", carBluetoothName = "") }
        AwayGuardService.schedule(context)
        context.stopService(android.content.Intent(context, PreparationService::class.java))
        context.stopService(android.content.Intent(context, TemperatureUpdateService::class.java))
    }
    companion object {
        @Volatile private var instance: AssistantStore? = null
        fun get(context: Context): AssistantStore = instance ?: synchronized(this) {
            instance ?: AssistantStore(context.applicationContext).also { instance = it }
        }
    }
}
