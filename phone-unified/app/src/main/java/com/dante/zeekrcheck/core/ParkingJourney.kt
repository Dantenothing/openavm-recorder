package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import kotlin.math.abs

/** Only a bounded odometer summary is persisted, never a route or additional coordinates. */
data class ParkedMileage(val km: Double, val motion: Long, val mileage: Long, val fetched: Long) {
    val source get() = minOf(motion, mileage)
    fun recent(now: Long) = ParkingEvidence.fresh(motion, now) && ParkingEvidence.fresh(mileage, now) && ParkingEvidence.fresh(fetched, now)
    fun json() = buildJsonObject { put("km", km); put("motion", motion); put("mileage", mileage); put("fetched", fetched) }
    companion object {
        fun from(e: ParkingEvidence?, now: Long): ParkedMileage? {
            return historical(e, now)?.takeIf { it.recent(now) }
        }
        fun historical(e: ParkingEvidence?, now: Long): ParkedMileage? {
            if (e == null || !e.parked || !ParkingEvidence.fresh(e.fetched, now)) return null
            val km = e.odometer?.takeIf { it > 0 && it.isFinite() && it <= 2_000_000 } ?: return null
            val motion = e.motionTime ?: return null
            val mileage = e.odometerTime ?: return null
            if (listOf(e.basicTime, e.safetyTime, mileage).any { it == null || now - it !in 0..604_800_000 }) return null
            if (abs(motion - mileage) > 60_000) return null
            return ParkedMileage(km, motion, mileage, e.fetched)
        }
        fun parse(e: JsonElement?): ParkedMileage? = runCatching {
            ParkedMileage(e.at("km").text()!!.toDouble(), e.at("motion").text()!!.toLong(),
                e.at("mileage").text()!!.toLong(), e.at("fetched").text()!!.toLong()).takeIf {
                it.km.isFinite() && it.km > 0 && it.km <= 2_000_000 && it.source > 0 && it.fetched > 0
            }
        }.getOrNull()
    }
}

/** Recovers a missed drive using a changed total odometer, not Sentry OFF or GPS drift.
 * The second GET may return the same still-fresh vehicle snapshot: the journey evidence is
 * the mileage increase since the previous parking, not elapsed fetching time alone.
 */
data class ParkingJourney(
    val baseline: ParkedMileage? = null, val latest: ParkedMileage? = null, val candidate: ParkedMileage? = null,
    val lastFetch: Long = 0, val sourceFloor: Long = 0, val manualFence: Long = 0,
    val confirmedAt: Long = 0, val boundary: Long = 0, val tripSource: Long = 0,
    val followUps: Int = 0, val message: String = "等待有效停车里程，补充识别漏读的行程",
) {
    fun pending(now: Long) = candidate?.let { it.recent(now) && now - it.fetched in 0..180_000 } == true
    fun needsFollowUp(now: Long) = pending(now) && followUps < 3
    fun manualChoice(now: Long): ParkingJourney {
        // A new explicit choice belongs to the current stop. Never finish an older candidate over it.
        val current = latest?.takeIf { now - it.source in 0..60_000 && now - it.fetched in 0..60_000 }
        return copy(baseline = current, candidate = null, manualFence = now, followUps = 0,
            message = if (current == null) "本次手动选择已保留 · 等待新的停车里程基准" else "本次手动选择已保留 · 等待下一次行程证据")
    }
    fun observe(probe: Probe, now: Long): ParkingJourney {
        if (probe.endpoint != Endpoint.STATUS || probe.fetchedAt.toEpochMilli() <= lastFetch) return this
        val fetched = probe.fetchedAt.toEpochMilli()
        if (!ParkingEvidence.fresh(fetched, now)) return this
        val e = ParkingEvidence.from(probe)
        val motion = e?.motionTime
        if (e?.recent(now) == true && e.moving && motion != null && motion > maxOf(sourceFloor, manualFence))
            return copy(baseline = null, latest = null, candidate = null, lastFetch = fetched, sourceFloor = motion,
                manualFence = 0, followUps = 0, message = "已读到真实行驶 · 下次停车重新判断守护")
        val sample = ParkedMileage.from(e, now)
        if (sample == null) {
            // A timestamped old parked snapshot may seed history during upgrade. It cannot
            // confirm a trip, release a manual choice, or satisfy any current control gate.
            val old = if (baseline == null) ParkedMileage.historical(e, now)?.takeIf { it.source > maxOf(sourceFloor, manualFence) } else null
            return copy(baseline = baseline ?: old, latest = null, candidate = null, lastFetch = fetched, followUps = 0,
                sourceFloor = old?.source ?: sourceFloor,
                message = if (old != null) "已保存历史停车里程 · 等待新车况进行比较" else "里程或驻车数据不足 · 等待有效行程证据")
        }
        if (sample.source < sourceFloor) return this
        val previous = latest
        if (previous != null && sample.mileage == previous.mileage && sample.km != previous.km)
            return copy(candidate = null, lastFetch = fetched, followUps = 0, message = "同一时间的里程不一致 · 暂不判断新行程")
        val next = copy(latest = sample, lastFetch = fetched, sourceFloor = sample.source)
        val base = baseline
        if (base == null) return if (sample.source > manualFence)
            next.copy(baseline = sample, candidate = null, followUps = 0, message = "已记录停车里程 · 下次里程增长可补充识别行程") else next
        val delta = sample.km - base.km
        if (delta < 0) return next.copy(candidate = null, followUps = 0, message = "里程回退 · 暂不判断新行程")
        if (delta < MIN_KM) return next.copy(baseline = if (delta == 0.0) sample else base, candidate = null, followUps = 0,
            message = "尚无明确里程增长 · 保留本次停车选择")
        val elapsed = sample.source - base.source
        if (sample.source <= maxOf(base.source, manualFence) || elapsed <= 0 || delta > elapsed / 3_600_000.0 * 300 + 1)
            return next.copy(candidate = null, followUps = 0, message = "里程变化与时间不一致 · 暂不判断新行程")
        val first = candidate
        if (first == null || !first.recent(now) || sample.km != first.km || sample.motion < first.motion || sample.mileage < first.mileage)
            return next.copy(candidate = sample, followUps = 0, message = "发现里程增长 · 等待复查本次停车")
        if (sample.fetched - first.fetched < CONFIRM_DELAY_MS) return next
        return next.copy(baseline = sample, candidate = null, confirmedAt = sample.fetched, boundary = first.fetched,
            tripSource = first.source - 1, followUps = 0, manualFence = 0,
            message = "里程增长与驻车复查一致 · 已识别新一次停车")
    }
    fun json() = buildJsonObject {
        put("baseline", baseline?.json() ?: JsonNull); put("latest", latest?.json() ?: JsonNull); put("candidate", candidate?.json() ?: JsonNull)
        put("lastFetch", lastFetch); put("sourceFloor", sourceFloor); put("manualFence", manualFence)
        put("confirmedAt", confirmedAt); put("boundary", boundary); put("tripSource", tripSource); put("followUps", followUps); put("message", message)
    }
    companion object {
        const val MIN_KM = 1.0 // AU 1.6.6 maintenanceStatus.odometer is an integer total, in km.
        const val CONFIRM_DELAY_MS = 60_000L
        const val FOLLOW_UP_DELAY_MS = 70_000L
        fun parse(e: JsonElement?): ParkingJourney {
            fun number(key: String) = e.at(key).text()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            return ParkingJourney(ParkedMileage.parse(e.at("baseline")), ParkedMileage.parse(e.at("latest")), ParkedMileage.parse(e.at("candidate")),
                number("lastFetch"), number("sourceFloor"), number("manualFence"), number("confirmedAt"), number("boundary"), number("tripSource"),
                number("followUps").coerceAtMost(3).toInt(), e.at("message").text()?.take(160) ?: "等待有效停车里程，补充识别漏读的行程")
        }
    }
}
