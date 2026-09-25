package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

enum class TemperaturePhase { REFRESHING, CHECKING, WAKING, STARTING, OBSERVING, STOPPING, DONE, FAILED, NEEDS_STOP, HANDED_OVER }

/** The sample and the actuator cleanup are independent outcomes. */
data class TemperatureUpdate(
    val vehicleKey: String, val id: Long, val target: Int,
    val phase: TemperaturePhase = TemperaturePhase.CHECKING,
    val message: String = "正在核对车况 · 尚未开启空调",
    val startSent: Long = 0, val stopSent: Long = 0,
    val ownsAc: Boolean = false, val sawRunning: Boolean = false,
    val temperature: Double? = null, val source: Long? = null,
    val cancelRequested: Boolean = false, val prepareAfter: Int? = null,
    val finishedAt: Long = 0,
    val combined: Boolean = false,
) {
    val active get() = phase in setOf(TemperaturePhase.REFRESHING, TemperaturePhase.CHECKING, TemperaturePhase.WAKING,
        TemperaturePhase.STARTING, TemperaturePhase.OBSERVING, TemperaturePhase.STOPPING)
    val needsStop get() = ownsAc && phase == TemperaturePhase.NEEDS_STOP
    fun expiredOwnership(now: Long) = needsStop && now - startSent > 420_000
    fun button(now: Long, small: Boolean = false) = when {
        expiredOwnership(now) -> "核对空调"
        active && cancelRequested -> if (small) "结束中" else "正在结束取温"
        active -> "结束取温"
        needsStop -> if (small) "停止空调" else "停止临时空调"
        small -> "短开空调取温"
        else -> "更新车温\n短暂开空调"
    }
    fun visible(now: Long) = active || needsStop || finishedAt > 0 && now - finishedAt in 0..60_000
    fun observeFinished(probe: Probe, now: Long): TemperatureUpdate {
        if (!needsStop || probe.endpoint != Endpoint.STATUS || probe.outcome != ProbeOutcome.SUCCESS) return this
        if (ParkingEvidence.from(probe)?.let { it.drivingMode && it.recent(now) } == true)
            return copy(ownsAc = false, prepareAfter = null, phase = TemperaturePhase.HANDED_OVER,
                message = "车辆已接管 · 已结束车温更新", finishedAt = now)
        val snapshot = ClimateSnapshot.parse(probe)
        val at = snapshot.sourceTime?.toEpochMilli() ?: return this
        if (stopSent <= 0 || at < stopSent || now - at !in -30_000..180_000 ||
            now - probe.fetchedAt.toEpochMilli() !in -30_000..30_000 || snapshot.acOn != false) return this
        return copy(ownsAc = false, prepareAfter = null, phase = if (temperature == null) TemperaturePhase.FAILED else TemperaturePhase.DONE,
            message = if (temperature == null) "未获得新车温 · 临时空调已结束" else "车温上报已更新 · 临时空调已结束", finishedAt = now)
    }
    fun interrupted(now: Long) = if (!active) this else copy(
        phase = if (ownsAc) TemperaturePhase.NEEDS_STOP else TemperaturePhase.FAILED,
        message = if (ownsAc) "车温更新已中断 · 临时空调停止待确认" else if (combined) "刷新已中断 · 未启动临时空调" else "车温更新已中断 · 未启动临时空调",
        prepareAfter = null, finishedAt = now)
    fun json() = buildJsonObject {
        put("vehicleKey", vehicleKey); put("id", id); put("target", target); put("phase", phase.name)
        put("message", message); put("startSent", startSent); put("stopSent", stopSent)
        put("ownsAc", ownsAc); put("sawRunning", sawRunning)
        put("temperature", temperature?.let(::JsonPrimitive) ?: JsonNull)
        put("source", source?.let(::JsonPrimitive) ?: JsonNull)
        put("cancelRequested", cancelRequested); put("prepareAfter", prepareAfter?.let(::JsonPrimitive) ?: JsonNull)
        put("finishedAt", finishedAt)
        put("combined", combined)
    }
    companion object {
        fun parse(e: JsonElement?): TemperatureUpdate? = runCatching {
            TemperatureUpdate(e.at("vehicleKey").text()!!.also { require(it.length <= 80) },
                e.at("id").text()!!.toLong().also { require(it > 0) },
                e.at("target").text()!!.toInt().also { require(it in 18..28) },
                TemperaturePhase.valueOf(e.at("phase").text()!!), e.at("message").text()!!.take(180),
                e.at("startSent").text()?.toLongOrNull() ?: 0, e.at("stopSent").text()?.toLongOrNull() ?: 0,
                e.at("ownsAc").text() == "true", e.at("sawRunning").text() == "true",
                e.at("temperature").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..100.0 },
                e.at("source").text()?.toLongOrNull(), e.at("cancelRequested").text() == "true",
                e.at("prepareAfter").text()?.toIntOrNull()?.takeIf { it in setOf(0,15) },
                e.at("finishedAt").text()?.toLongOrNull() ?: 0, e.at("combined").text() == "true")
        }.getOrNull()
    }
}

/** No automatic restart or retransmission of a positive climate command. */
class TemperatureUpdateFlow(
    private val initial: TemperatureUpdate,
    private val load: () -> TemperatureUpdate?,
    private val save: (TemperatureUpdate) -> Unit,
    private val read: suspend () -> Probe,
    private val refreshEvidence: suspend (Probe) -> Probe = { it },
    private val send: suspend (ClimateTarget, () -> Unit, (Probe) -> Unit) -> CommandResult,
    private val publish: (Probe) -> Unit = {},
    private val current: () -> Boolean = { true },
    private val preparationRunning: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val initialProbe: Probe? = null,
) {
    private class Replaced : Exception()
    private var latest: Probe? = null
    private var baselineSource: Long? = null
    private var observingExisting = false
    private fun state() = load()?.takeIf { it.id == initial.id && it.vehicleKey == initial.vehicleKey }
    private fun valid() = current() && state() != null
    private fun change(block: (TemperatureUpdate) -> TemperatureUpdate) {
        if (valid()) state()?.let { save(block(it)) }
    }
    private fun finish(phase: TemperaturePhase, message: String) = change {
        it.copy(phase = phase, message = message, finishedAt = now())
    }
    private fun check() { if (!valid()) throw Replaced() }
    private fun freshClimate(probe: Probe) = probe.outcome == ProbeOutcome.SUCCESS &&
        ClimateSnapshot.parse(probe).sourceTime?.toEpochMilli()?.let { now() - it in -30_000..180_000 } == true &&
        now() - probe.fetchedAt.toEpochMilli() in -30_000..30_000
    private fun parked(probe: Probe) = ParkingEvidence.from(probe)?.let { it.parked && it.recent(now()) } == true
    private fun accept(probe: Probe) {
        check()
        publish(probe)
        if (probe.outcome != ProbeOutcome.SUCCESS) return
        latest = probe
        val snapshot = ClimateSnapshot.parse(probe)
        val source = snapshot.sourceTime?.toEpochMilli() ?: return
        if (!freshClimate(probe)) return
        val motion = ParkingEvidence.from(probe)
        if (motion?.drivingMode == true && motion.recent(now())) {
            change { it.copy(ownsAc = false, prepareAfter = null) }
            finish(TemperaturePhase.HANDED_OVER, "车辆已接管 · 已结束车温更新")
            return
        }
        change { old ->
            val running = old.sawRunning || old.startSent > 0 && source >= old.startSent && snapshot.acOn == true
            val threshold = old.startSent.takeIf { it > 0 } ?: old.id
            val eligible = old.startSent > 0 && running || observingExisting && old.phase == TemperaturePhase.OBSERVING
            val newReport = eligible && source >= threshold && (baselineSource == null || source > baselineSource!!) &&
                snapshot.cabinTemperature != null
            val stopped = old.ownsAc && old.stopSent > 0 && source >= old.stopSent && snapshot.acOn == false
            old.copy(sawRunning = running, ownsAc = old.ownsAc && !stopped,
                temperature = if (newReport) snapshot.cabinTemperature else old.temperature,
                source = if (newReport) source else old.source)
        }
    }
    private suspend fun fetch(): Probe {
        check()
        val probe = withTimeout(12_000) { read() }
        check()
        accept(probe)
        if (probe.outcome != ProbeOutcome.SUCCESS) throw CheckFailure(probe.outcome)
        return probe
    }
    private suspend fun pause() {
        repeat(4) {
            if (state()?.cancelRequested == true || state()?.phase == TemperaturePhase.HANDED_OVER) return
            delay(1_000); check()
        }
    }
    private suspend fun stopOwned(explicit: Boolean) {
        check()
        val s = state() ?: return
        if (!s.ownsAc || preparationRunning()) return
        if (s.startSent <= 0 || now() - s.startSent !in 0..420_000) return
        var probe = latest
        if (probe == null || !freshClimate(probe) || !parked(probe) || !s.sawRunning && !explicit) probe = fetch()
        if (!parked(probe) || !freshClimate(probe)) return
        if (!explicit && !state()!!.sawRunning) return
        if (state()?.phase == TemperaturePhase.HANDED_OVER) return
        change { it.copy(phase = TemperaturePhase.STOPPING, message =
            if (it.temperature != null) "已收到车温 · 正在结束临时空调" else "正在结束临时空调") }
        val result = withTimeout(25_000) {
            send(ClimateTarget(ClimateChannel.AC, 0, 5), {
                check()
                change { it.copy(stopSent = now()) }
            }, ::accept)
        }
        if (result == CommandResult.REJECTED || state()?.phase == TemperaturePhase.HANDED_OVER) return
        // A successful receipt alone is never a stop acknowledgement.
        repeat(2) {
            if (state()?.ownsAc != true) return
            delay(2_000); fetch()
        }
    }

    suspend fun run(stopOnly: Boolean = false) {
        var failure: String? = null
        var observedExisting = false
        var cancelled = false
        try {
            check()
            var probe = initialProbe?.takeIf { it.endpoint == Endpoint.STATUS && it.outcome == ProbeOutcome.SUCCESS &&
                now() - it.fetchedAt.toEpochMilli() in 0..30_000 }?.also(::accept) ?: fetch()
            baselineSource = ClimateSnapshot.parse(probe).sourceTime?.toEpochMilli()
            if (state()?.phase == TemperaturePhase.HANDED_OVER) return
            val existing = preparationRunning() || ClimateSnapshot.parse(probe).let { it.acOn == true || it.blowerActive == true }
            if (!stopOnly && existing) {
                observedExisting = true
                observingExisting = true
                change { it.copy(phase = TemperaturePhase.OBSERVING, message = "空调已在运行 · 只读取车温") }
                accept(probe)
                withTimeoutOrNull(25_000) {
                    while (state()?.temperature == null && state()?.cancelRequested != true &&
                        state()?.phase != TemperaturePhase.HANDED_OVER) { pause(); fetch() }
                }
                return
            }
            if ((!parked(probe) || !freshClimate(probe)) && state()?.cancelRequested != true) {
                change { it.copy(phase = TemperaturePhase.WAKING, message = "正在核对新车况 · 尚未开启空调") }
                probe = withTimeout(55_000) { refreshEvidence(probe) }
                accept(probe)
                if (state()?.phase == TemperaturePhase.HANDED_OVER) return
                baselineSource = ClimateSnapshot.parse(probe).sourceTime?.toEpochMilli()
            }
            if (!parked(probe) || !freshClimate(probe)) {
                failure = "驻车或空调状态待确认 · 未启动临时空调"; return
            }
            if (stopOnly) {
                withTimeout(35_000) { stopOwned(explicit = true) }
                return
            }
            if (state()?.cancelRequested == true) return
            val climate = ClimateSnapshot.parse(probe)
            if (climate.acOn != false || climate.blowerActive != false || preparationRunning()) {
                failure = "空调已有活动或状态未知 · 未启动临时空调"; return
            }
            change { it.copy(phase = TemperaturePhase.STARTING, message = "正在短暂开启空调获取车温") }
            val result = withTimeout(25_000) {
                send(ClimateTarget(ClimateChannel.AC, initial.target, 5), {
                    check()
                    if (state()?.cancelRequested == true || preparationRunning()) throw Replaced()
                    change { it.copy(ownsAc = true, startSent = now(), temperature = null, source = null) }
                }, ::accept)
            }
            if (result == CommandResult.REJECTED) {
                change { it.copy(ownsAc = false) }
                failure = "临时空调请求未受理 · 保留上次车温"; return
            }
            if (state()?.phase == TemperaturePhase.HANDED_OVER) return
            change { it.copy(phase = TemperaturePhase.OBSERVING, message = "空调取温中 · 等待车辆上报") }
            withTimeoutOrNull(25_000) {
                while (state()?.temperature == null && state()?.cancelRequested != true &&
                    state()?.phase != TemperaturePhase.HANDED_OVER) { pause(); fetch() }
            }
        } catch (_: Replaced) {
            failure = "车温更新已取消 · 未继续发送"
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (_: Exception) {
            failure = "车温读取未完成 · 保留可用记录"
        } finally {
            // Cleanup remains bounded. A changed account/vehicle or driver takeover forbids further writes.
            if (valid() && state()?.ownsAc == true && !stopOnly && state()?.phase != TemperaturePhase.HANDED_OVER) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(35_000) { runCatching { stopOwned(explicit = false) } }
                }
            }
            if (valid() && state()?.phase != TemperaturePhase.HANDED_OVER) {
                val s = state()!!
                when {
                    s.ownsAc -> finish(TemperaturePhase.NEEDS_STOP, if (s.temperature != null)
                        "车温已更新 · 临时空调停止待确认" else "车温暂未更新 · 临时空调停止待确认")
                    s.temperature != null -> finish(TemperaturePhase.DONE, if (observedExisting)
                        "已读取运行中的车温 · 保留原空调" else "车温上报已更新 · 临时空调已结束")
                    s.startSent > 0 && s.stopSent > 0 -> finish(TemperaturePhase.FAILED, "未获得新车温 · 临时空调已结束")
                    else -> finish(TemperaturePhase.FAILED, failure ?: if (cancelled || s.cancelRequested)
                        "已取消车温更新 · 未启动临时空调" else "车辆暂无新车温 · 保留上次记录")
                }
            }
        }
    }
}
