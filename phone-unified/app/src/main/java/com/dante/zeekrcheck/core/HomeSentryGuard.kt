package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*

/** Persisted arrival confirmation. Fetching the same cloud snapshot twice is not a second observation. */
data class HomeSentryGuard(
    val vehicleKey: String = "", val lastMotion: Long = 0, val trip: Long = 0,
    val manualHoldAt: Long = 0, val attemptedAt: Long = 0,
    val firstFetch: Long = 0, val firstMotion: Long = 0, val firstPosition: Long = 0,
    val latestFetch: Long = 0, val latestMotion: Long = 0, val latestPosition: Long = 0,
    val followUps: Int = 0, val lastSentry: String = "", val lastSentryFetch: Long = 0,
    val lastSentryAtHome: Boolean = false,
) {
    val held get() = manualHoldAt > 0
    fun resetConfirmation() = copy(firstFetch = 0, firstMotion = 0, firstPosition = 0,
        latestFetch = 0, latestMotion = 0, latestPosition = 0, followUps = 0, lastSentryAtHome = false)
    fun hold(now: Long) = if (held) this else resetConfirmation().copy(manualHoldAt = now)
    fun resume() = resetConfirmation().copy(manualHoldAt = 0, attemptedAt = 0)
    fun afterMissedJourney(journey: ParkingJourney) = HomeSentryGuard(vehicleKey = vehicleKey, trip = journey.tripSource,
        manualHoldAt = manualHoldAt.takeIf { it >= journey.boundary } ?: 0,
        attemptedAt = attemptedAt.takeIf { it >= journey.boundary } ?: 0, lastSentryFetch = lastSentryFetch)
    fun needsFollowUp(now: Long) = !held && attemptedAt == 0L && firstFetch > 0 &&
        now - firstFetch in 0..CONFIRM_WINDOW_MS && now - latestFetch in 0..ParkingEvidence.MAX_AGE_MS && followUps < MAX_FOLLOW_UPS

    fun observe(probe: Probe, home: CarLocation?, radius: Int, verified: Boolean, now: Long): HomeSentryGuard {
        if (probe.endpoint == Endpoint.SENTRY) {
            if (!ParkingEvidence.recentSentry(probe, now)) return this
            val fetch = probe.fetchedAt.toEpochMilli()
            val value = probe.data.at("vstdModeState").text()
            if (fetch <= maxOf(lastSentryFetch, trip) || value !in setOf("0", "1")) return this
            val atHome = latestFetch > 0 && now - latestFetch in 0..ParkingEvidence.MAX_AGE_MS && fetch >= latestFetch
            // A newly observed ON while already home may be a choice made in the official app/car.
            val next = if (lastSentry == "0" && value == "1" && lastSentryAtHome && atHome) hold(now) else this
            return next.copy(lastSentry = value!!, lastSentryFetch = fetch, lastSentryAtHome = atHome)
        }
        if (probe.endpoint != Endpoint.STATUS) return this
        val e = ParkingEvidence.from(probe) ?: return resetConfirmation()
        if (!e.recent(now)) return resetConfirmation()
        val source = e.motionTime ?: return resetConfirmation()
        if (source < lastMotion || source <= trip) return this
        if (e.moving && source > maxOf(lastMotion, manualHoldAt, attemptedAt))
            return HomeSentryGuard(vehicleKey = vehicleKey, lastMotion = source, trip = source)
        val next = copy(lastMotion = maxOf(lastMotion, source))
        if (!e.parked || !e.recentPosition(now) || !verified || home?.verified != true ||
            HomeZone.area(e.position, home, radius) !in setOf(HomeZone.Area.HOME, HomeZone.Area.NEAR_HOME))
            return next.resetConfirmation()
        if (held || attemptedAt > 0) return next
        val position = e.positionTime!!
        if (firstFetch == 0L || now - firstFetch !in 0..CONFIRM_WINDOW_MS || now - latestFetch !in 0..ParkingEvidence.MAX_AGE_MS)
            return next.copy(firstFetch = e.fetched, firstMotion = source, firstPosition = position,
                latestFetch = e.fetched, latestMotion = source, latestPosition = position, followUps = 0)
        if (e.fetched <= latestFetch || source <= latestMotion || position <= latestPosition) return next
        return next.copy(latestFetch = e.fetched, latestMotion = source, latestPosition = position)
    }

    fun decision(status: Probe?, sentry: Probe?, home: CarLocation?, radius: Int, verified: Boolean, now: Long): GuardDecision {
        if (held) return GuardDecision(false, "本次手动保持开启 · 到家不会自动关闭")
        if (attemptedAt > 0) return GuardDecision(false,
            if (ParkingEvidence.recentSentry(sentry, now) && sentry!!.fetchedAt.toEpochMilli() >= attemptedAt && sentry.data.at("vstdModeState").text() == "0")
                "已确认到家 · 哨兵已自动关闭" else "本次到家已发送关闭请求 · 结果待核实，不重复发送")
        if (home?.verified != true) return GuardDecision(false, "请先设置并核对家的位置")
        val e = ParkingEvidence.from(status) ?: return GuardDecision(false, "到家关闭：等待有效车况")
        if (!e.recent(now)) return GuardDecision(false, "到家关闭：车况时间缺失或超过 3 分钟")
        if (!e.parked) return GuardDecision(false, "到家关闭：等待已驻车、零车速和非行驶模式")
        if (!e.recentPosition(now) || !verified) return GuardDecision(false, "到家关闭：等待近期可信车辆位置")
        if (HomeZone.area(e.position, home, radius) !in setOf(HomeZone.Area.HOME, HomeZone.Area.NEAR_HOME))
            return GuardDecision(false, "车辆在家外 · 保持哨兵当前状态")
        if (!ParkingEvidence.recentSentry(sentry, now) || sentry!!.fetchedAt.isBefore(status!!.fetchedAt))
            return GuardDecision(false, "到家关闭：等待本次哨兵状态", followUp = needsFollowUp(now))
        if (sentry.data.at("vstdModeState").text() == "0") return GuardDecision(false, "车辆在家附近 · 哨兵已关闭")
        if (sentry.data.at("vstdModeState").text() != "1") return GuardDecision(false, "到家关闭：哨兵状态未知")
        if (firstFetch == 0L || now - firstFetch !in 0..CONFIRM_WINDOW_MS || latestMotion != e.motionTime ||
            latestPosition != e.positionTime || latestFetch != e.fetched || latestFetch - firstFetch < CONFIRM_DELAY_MS ||
            latestMotion <= firstMotion || latestPosition <= firstPosition)
            return GuardDecision(false, "到家待确认 · 至少间隔 1 分钟再次核对位置与驻车", followUp = needsFollowUp(now))
        return GuardDecision(false, "已两次确认到家并驻车 · 自动关闭哨兵", disable = true)
    }

    fun json() = buildJsonObject {
        put("vehicleKey", vehicleKey); put("lastMotion", lastMotion); put("trip", trip)
        put("manualHoldAt", manualHoldAt); put("attemptedAt", attemptedAt)
        put("firstFetch", firstFetch); put("firstMotion", firstMotion); put("firstPosition", firstPosition)
        put("latestFetch", latestFetch); put("latestMotion", latestMotion); put("latestPosition", latestPosition)
        put("followUps", followUps); put("lastSentry", lastSentry); put("lastSentryFetch", lastSentryFetch); put("lastSentryAtHome", lastSentryAtHome)
    }
    companion object {
        const val CONFIRM_DELAY_MS = 60_000L
        const val FOLLOW_UP_DELAY_MS = 70_000L // Respect the shared 60-second read throttle.
        const val CONFIRM_WINDOW_MS = 300_000L
        const val MAX_FOLLOW_UPS = 3
        fun parse(e: JsonElement?): HomeSentryGuard {
            fun number(key: String) = e.at(key).text()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            return HomeSentryGuard(e.at("vehicleKey").text() ?: "", number("lastMotion"), number("trip"),
                number("manualHoldAt"), number("attemptedAt"), number("firstFetch"), number("firstMotion"), number("firstPosition"),
                number("latestFetch"), number("latestMotion"), number("latestPosition"), number("followUps").coerceAtMost(MAX_FOLLOW_UPS.toLong()).toInt(),
                e.at("lastSentry").text()?.takeIf { it in setOf("0", "1") } ?: "", number("lastSentryFetch"), e.at("lastSentryAtHome").text() == "true")
        }
    }
}
