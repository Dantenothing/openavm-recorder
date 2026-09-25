package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*

enum class ParkingPhase { UNKNOWN, MOVING, CANDIDATE, PARKED }
enum class GuardPauseOrigin { UNKNOWN, MANUAL, OBSERVED_OFF }

/** Saved in the encrypted assistant record. Only fresh vehicle motion starts another parking session. */
data class ParkingGuard(
    val vehicleKey: String = "", val phase: ParkingPhase = ParkingPhase.UNKNOWN,
    val trip: Long = 0, val anchor: CarLocation? = null, val lastParkSource: Long = 0,
    val pausedAt: Long = 0, val attemptedAt: Long = 0, val observedOn: Boolean = false,
    val lastSentryFetch: Long = 0, val revision: Long = 0,
    val lastStatusSource: Long = 0,
    val pauseOrigin: GuardPauseOrigin = GuardPauseOrigin.UNKNOWN, val lastOnFetch: Long = 0,
    val journey: ParkingJourney = ParkingJourney(),
) {
    val paused get() = pausedAt > 0
    val manualPaused get() = paused && pauseOrigin == GuardPauseOrigin.MANUAL
    fun pause(now: Long, origin: GuardPauseOrigin = GuardPauseOrigin.MANUAL) = copy(pausedAt = now, pauseOrigin = origin, revision = revision + 1,
        journey = if (origin == GuardPauseOrigin.MANUAL) journey.manualChoice(now) else journey)
    fun resume() = copy(pausedAt = 0, attemptedAt = 0, observedOn = false, lastOnFetch = 0, pauseOrigin = GuardPauseOrigin.UNKNOWN, revision = revision + 1)
    fun observe(probe: Probe, now: Long, automaticOffAt: Long = 0): ParkingGuard {
        val nextJourney = journey.observe(probe, now)
        var next = if (nextJourney == journey) this else copy(journey = nextJourney)
        if (nextJourney.confirmedAt > journey.confirmedAt) {
            val keepPause = paused && when (pauseOrigin) {
                GuardPauseOrigin.OBSERVED_OFF -> lastOnFetch >= nextJourney.boundary
                else -> pausedAt >= nextJourney.boundary
            }
            next = next.copy(phase = ParkingPhase.UNKNOWN, trip = nextJourney.tripSource, anchor = null,
                lastStatusSource = nextJourney.tripSource, lastParkSource = 0, revision = revision + 1,
                pausedAt = if (keepPause) pausedAt else 0, pauseOrigin = if (keepPause) pauseOrigin else GuardPauseOrigin.UNKNOWN,
                attemptedAt = attemptedAt.takeIf { it >= nextJourney.boundary } ?: 0,
                observedOn = observedOn && lastOnFetch >= nextJourney.boundary,
                lastOnFetch = lastOnFetch.takeIf { it >= nextJourney.boundary } ?: 0)
        }
        return next.observeVehicle(probe, now, automaticOffAt)
    }
    /** Upgrade only a pause caused by our own recent home-off attempt; explicit choices always survive. */
    fun afterAutomaticOff(at: Long): ParkingGuard = if (at > trip && pauseOrigin == GuardPauseOrigin.OBSERVED_OFF &&
        pausedAt - at in 0..600_000) copy(pausedAt = 0, pauseOrigin = GuardPauseOrigin.UNKNOWN, observedOn = false,
            lastOnFetch = 0, revision = revision + 1) else this

    private fun observeVehicle(probe: Probe, now: Long, automaticOffAt: Long): ParkingGuard {
        if (probe.outcome != ProbeOutcome.SUCCESS) return this
        if (probe.endpoint == Endpoint.SENTRY) {
            if (!ParkingEvidence.recentSentry(probe, now) || probe.fetchedAt.toEpochMilli() <= maxOf(lastSentryFetch, trip) || phase == ParkingPhase.MOVING) return this
            return when (probe.data.at("vstdModeState").text()) {
                "1" -> copy(observedOn = true, lastOnFetch = probe.fetchedAt.toEpochMilli(), lastSentryFetch = probe.fetchedAt.toEpochMilli())
                "0" -> copy(lastSentryFetch = probe.fetchedAt.toEpochMilli()).let {
                    if (automaticOffAt > trip && probe.fetchedAt.toEpochMilli() >= automaticOffAt && now - automaticOffAt in 0..600_000)
                        it.afterAutomaticOff(automaticOffAt).copy(observedOn = false, lastOnFetch = 0)
                    else if (observedOn && !paused) it.pause(now, GuardPauseOrigin.OBSERVED_OFF) else it
                }
                else -> this
            }
        }
        val evidence = ParkingEvidence.from(probe) ?: return this
        if (!evidence.recent(now)) return this
        val source = evidence.motionTime ?: return this
        if (source <= maxOf(lastStatusSource, trip, lastParkSource)) return this
        if (evidence.moving && source > pausedAt) {
            return if (phase == ParkingPhase.MOVING) copy(trip = source, lastStatusSource = source) else
                ParkingGuard(vehicleKey = vehicleKey, phase = ParkingPhase.MOVING, trip = source,
                    lastStatusSource = source, lastSentryFetch = lastSentryFetch, revision = revision + 1, journey = journey)
        }
        // EPB determines parking directly. Unlock/relock, GPS drift and absent motion never erase a pause.
        return if (evidence.parked && source > trip)
            copy(phase = ParkingPhase.PARKED, lastParkSource = source, lastStatusSource = source, anchor = null)
        else copy(phase = ParkingPhase.UNKNOWN, lastStatusSource = source)
    }
    fun decision(status: Probe?, sentry: Probe?, home: CarLocation?, radius: Int, locationVerified: Boolean, now: Long): GuardDecision {
        if (journey.pending(now)) return GuardDecision(false, "里程变化待复查 · 暂不改变本次哨兵选择", journeyFollowUp = journey.needsFollowUp(now))
        if (paused) return GuardDecision(false, if (manualPaused) "本次已暂停 · 确认下一次行程后恢复" else "哨兵已关闭 · 本次不自动重开")
        if (attemptedAt > 0) return GuardDecision(false, "本次已发送开启请求 · 不重复发送")
        if (home?.verified != true) return GuardDecision(false, "请先设置并核对家的位置")
        val evidence = ParkingEvidence.from(status) ?: return GuardDecision(false, "车况读取未完成 · 本次未执行")
        if (!evidence.recent(now)) return GuardDecision(false, "车况时间缺失或超过 3 分钟 · 等待车辆新上报")
        if (evidence.drivingMode) return GuardDecision(false, "车辆处于行驶模式 · 本次未执行")
        if (!evidence.parked) return GuardDecision(false, "等待原厂已驻车和零车速记录 · ${evidence.brake.label}")
        val point = evidence.position
        if (!evidence.recentPosition(now) || !locationVerified) return GuardDecision(false, "车辆位置未核对或缺少近期可信记录 · 本次未执行")
        if (HomeZone.area(point, home, radius) != HomeZone.Area.AWAY) return GuardDecision(false, "家中或家附近 · 无需自动开启")
        if (evidence.locked != true) return GuardDecision(false, "车辆尚未确认锁好 · 等待锁车")
        if (phase != ParkingPhase.PARKED || lastParkSource != evidence.motionTime || lastParkSource <= trip)
            return GuardDecision(false, "等待本次驻车记录 · 不采用较早的停车状态")
        if (!ParkingEvidence.recentSentry(sentry, now) || sentry!!.fetchedAt.isBefore(status!!.fetchedAt))
            return GuardDecision(false, "等待本次哨兵读取 · 不采用旧查询")
        return when (sentry.data.at("vstdModeState").text()) {
            "1" -> GuardDecision(false, "哨兵接口返回开启 · 无需重复发送")
            "0" -> GuardDecision(true, "家外停车已确认 · 满足自动开启条件")
            else -> GuardDecision(false, "哨兵状态未知 · 本次未执行")
        }
    }
    fun json() = buildJsonObject {
        put("vehicleKey", vehicleKey); put("phase", phase.name); put("trip", trip); put("anchor", anchor?.json() ?: JsonNull)
        put("lastParkSource", lastParkSource); put("pausedAt", pausedAt); put("attemptedAt", attemptedAt)
        put("observedOn", observedOn); put("lastSentryFetch", lastSentryFetch); put("revision", revision)
        put("lastStatusSource", lastStatusSource)
        put("pauseOrigin", pauseOrigin.name); put("lastOnFetch", lastOnFetch); put("journey", journey.json())
    }
    companion object {
        fun parse(e: JsonElement?) = runCatching {
            ParkingGuard(e.at("vehicleKey").text() ?: "", ParkingPhase.valueOf(e.at("phase").text() ?: "UNKNOWN"),
                e.at("trip").text()?.toLongOrNull() ?: 0, CarLocation.parse(e.at("anchor")), e.at("lastParkSource").text()?.toLongOrNull() ?: 0,
                e.at("pausedAt").text()?.toLongOrNull() ?: 0, e.at("attemptedAt").text()?.toLongOrNull() ?: 0,
                e.at("observedOn").text() == "true", e.at("lastSentryFetch").text()?.toLongOrNull() ?: 0, e.at("revision").text()?.toLongOrNull() ?: 0,
                e.at("lastStatusSource").text()?.toLongOrNull() ?: 0,
                runCatching { GuardPauseOrigin.valueOf(e.at("pauseOrigin").text() ?: "UNKNOWN") }.getOrDefault(GuardPauseOrigin.UNKNOWN),
                e.at("lastOnFetch").text()?.toLongOrNull() ?: 0, ParkingJourney.parse(e.at("journey")))
        }.getOrDefault(ParkingGuard())
    }
}

data class GuardDecision(val enable: Boolean, val reason: String, val disable: Boolean = false, val followUp: Boolean = false, val journeyFollowUp: Boolean = false)
