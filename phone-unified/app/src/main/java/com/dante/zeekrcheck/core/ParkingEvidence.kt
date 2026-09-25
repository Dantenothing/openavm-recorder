package com.dante.zeekrcheck.core

import kotlinx.serialization.json.JsonNull
import java.time.Instant

enum class ParkingBrake(val label: String) {
    RELEASED("未驻车"), PARKED("已驻车 · Parked"), APPLYING("正在驻车"), FAILURE("驻车异常"), UNKNOWN("驻车状态未知")
}

/** AU 1.6.6 CarMileageStatusView mapping. Root time is a snapshot reference, not a per-field sample time.
 * This transient evidence never replaces the individual source times in the shared readings/location cache.
 */
data class ParkingEvidence(
    val brake: ParkingBrake, val speed: Double?, val mode: Int?, val locked: Boolean?,
    val basicTime: Long?, val safetyTime: Long?, val aggregateMotionTime: Boolean, val fetched: Long,
    val position: CarLocation?, val positionTime: Long?, val positionTrusted: Boolean,
    val odometer: Double? = null, val odometerTime: Long? = null,
) {
    val drivingMode get() = mode == 13 || mode == 33
    val parked get() = brake == ParkingBrake.PARKED && speed == 0.0 && mode != null && !drivingMode
    // Releasing the brake or entering Driving at zero speed alone does not clear a manual parking pause.
    val moving get() = brake == ParkingBrake.RELEASED && drivingMode && speed != null && speed > 0
    val motionTime get() = basicTime?.let { basic -> safetyTime?.let { minOf(basic, it) } }
    fun recent(now: Long) = fresh(basicTime, now) && fresh(safetyTime, now) && fresh(fetched, now)
    fun recentPosition(now: Long) = position != null && positionTrusted && fresh(positionTime, now) && fresh(fetched, now)
    fun label(now: Long): String {
        val label = when {
            drivingMode -> if (moving) "行驶中 · Driving" else "行驶模式 · 驻车待确认"
            brake == ParkingBrake.PARKED && speed != null && speed > 0 -> "驻车与车速不一致"
            else -> brake.label
        }
        return if (recent(now)) label else "上次记录：$label"
    }
    fun speedLabel() = speed?.let { "车速 ${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()} km/h" } ?: "车速未返回"
    fun timeLabel() = "${if (aggregateMotionTime) "整体车况" else "驻车数据"}：${displayTime(motionTime?.let(Instant::ofEpochMilli))}"
    companion object {
        const val MAX_AGE_MS = 180_000L
        fun fresh(at: Long?, now: Long) = at != null && now - at in 0..MAX_AGE_MS
        fun from(probe: Probe?): ParkingEvidence? {
            if (probe?.endpoint != Endpoint.STATUS || probe.outcome != ProbeOutcome.SUCCESS) return null
            val data = probe.data
            fun missing(path: String) = data.at(path).let { it == null || it == JsonNull }
            // Explicit malformed or stale timestamps must not be hidden by a newer root timestamp.
            fun time(path: String) = Capabilities.sourceTime(data.at(if (missing(path)) "updateTime" else path))?.toEpochMilli()
            val basic = "basicVehicleStatus"
            val safety = "additionalVehicleStatus.drivingSafetyStatus"
            return ParkingEvidence(
                when (data.at("$safety.electricParkBrakeStatus").text()) {
                    "0" -> ParkingBrake.RELEASED; "1" -> ParkingBrake.PARKED; "2" -> ParkingBrake.APPLYING
                    "3" -> ParkingBrake.FAILURE; else -> ParkingBrake.UNKNOWN
                },
                data.at("$basic.speed").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..400.0 },
                data.at("$basic.usageMode").text()?.toIntOrNull()?.takeIf { it in 0..255 },
                when (data.at("$safety.centralLockingStatus").text()) { "1" -> true; "0" -> false; else -> null },
                time("$basic.updateTime"), time("$safety.updateTime"),
                missing("$basic.updateTime") || missing("$safety.updateTime"), probe.fetchedAt.toEpochMilli(),
                CarLocation.from(probe), time("$basic.position.updateTime"),
                data.at("$basic.position.posCanBeTrusted").text() in setOf("true", "1"),
                data.at("additionalVehicleStatus.maintenanceStatus.odometer").text()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..2_000_000.0 },
                time("additionalVehicleStatus.maintenanceStatus.updateTime"))
        }
        fun recentSentry(probe: Probe?, now: Long): Boolean {
            if (probe?.endpoint != Endpoint.SENTRY || probe.outcome != ProbeOutcome.SUCCESS || now - probe.fetchedAt.toEpochMilli() !in 0..60_000) return false
            val raw = probe.data.at("updateTime")
            return raw == null || raw == JsonNull || fresh(Capabilities.sourceTime(raw)?.toEpochMilli(), now)
        }
    }
}
