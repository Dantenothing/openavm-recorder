package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.time.Instant

/** Display evidence only; automation continues to use ParkingEvidence's stricter freshness rules. */
data class MotionReading(val state: String, val source: Long?, val fetched: Long, val aggregate: Boolean) {
    fun label(now: Instant): String = "$state · ${source?.let { OverviewReading("", it, fetched).timeLabel(now).removeSuffix(" 记录") } ?: "时间未知"}"
    fun json() = buildJsonObject {
        put("state", state); put("source", source?.let(::JsonPrimitive) ?: JsonNull)
        put("fetched", fetched); put("aggregate", aggregate)
    }
    companion object {
        fun from(probe: Probe): MotionReading? {
            val e = ParkingEvidence.from(probe) ?: return null
            val state = when {
                e.parked -> "Parked"
                e.drivingMode && e.brake == ParkingBrake.RELEASED && e.speed != null -> "Driving"
                else -> "—"
            }
            return MotionReading(state, e.motionTime, e.fetched, e.aggregateMotionTime)
        }
        fun parse(e: JsonElement?): MotionReading? {
            val state = e.at("state").text()?.takeIf { it in setOf("Parked", "Driving", "—") } ?: return null
            return MotionReading(state, e.at("source").text()?.toLongOrNull(), e.at("fetched").text()?.toLongOrNull() ?: return null,
                e.at("aggregate").text() == "true")
        }
    }
}
