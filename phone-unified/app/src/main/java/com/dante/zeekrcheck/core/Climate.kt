package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import java.time.Instant

enum class ClimateChannel(val title: String, val key: String) {
    FRONT_LEFT("左前座椅", "SV.11"), FRONT_RIGHT("右前座椅", "SV.19"), AC("空调", "AC"),
    HEAT_LEFT("左前座椅加热", "SH.11"), HEAT_RIGHT("右前座椅加热", "SH.19"), STEERING("方向盘加热", "SW"),
    ;
    val alternateSeatMode: ClimateChannel? get() = when (this) {
        FRONT_LEFT -> HEAT_LEFT; HEAT_LEFT -> FRONT_LEFT
        FRONT_RIGHT -> HEAT_RIGHT; HEAT_RIGHT -> FRONT_RIGHT
        else -> null
    }
}

/** AU 1.6.6 config.a.L and ZAF ACParameters: these are modes, not measured temperatures. */
enum class AcMode(val wireTemperature: String?) { TARGET(null), LO("15.5"), HI("28.5") }

/** value retains the comfort target even when a temporary AC mode is requested. */
data class ClimateTarget(val channel: ClimateChannel, val value: Int,
    val minutes: Int = if (channel == ClimateChannel.STEERING) 8 else 15,
    val acMode: AcMode = AcMode.TARGET,
) {
    init {
        require(if (channel == ClimateChannel.STEERING) minutes in listOf(5, 8) else minutes in listOf(5, 10, 15, 20, 30))
        require(when (channel) { ClimateChannel.AC -> value == 0 || value in 18..28; ClimateChannel.STEERING -> value in 0..1; else -> value in 0..3 })
        // Only explicit short requests are exposed until native expiry/reversion has been road-tested.
        require(acMode == AcMode.TARGET || (channel == ClimateChannel.AC && value > 0 && minutes == 5))
    }
    val label: String get() = when {
        value == 0 -> "${channel.title} · 关闭"
        acMode != AcMode.TARGET -> "空调 · ${acMode.name} · 请求 ${minutes} 分钟"
        channel == ClimateChannel.AC -> "空调 · ${value}°C · ${minutes} 分钟"
        channel == ClimateChannel.STEERING -> "方向盘加热 · 开启 · ${minutes} 分钟"
        else -> "${channel.title} · ${value} 档 · ${minutes} 分钟"
    }
    internal fun parameters(): List<Pair<String, String>> = buildList {
        add(channel.key to (value > 0).toString())
        if (value > 0) {
            val setting = when (channel) {
                ClimateChannel.AC -> acMode.wireTemperature ?: value.toString()
                // Official EnvParams.setSWParameters(on, "3", duration). Logical UI stays on/off.
                ClimateChannel.STEERING -> "3"
                else -> value.toString()
            }
            add((channel.key + if (channel == ClimateChannel.AC) ".temp" else ".level") to setting)
            add(channel.key + ".duration" to minutes.toString())
        }
    }
    fun body(): String = buildJsonObject {
        put("command", "start"); put("serviceId", "ZAF")
        putJsonObject("setting") {
            putJsonArray("serviceParameters") {
                fun parameter(key: String, value: String) { add(buildJsonObject { put("key", key); put("value", value) }) }
                parameters().forEach { (key, value) -> parameter(key, value) }
                // Original 1.6.6 EnvRegulationCommandCreator adds this parameter.
                parameter("operation", "4")
            }
        }
    }.toString()
}

object ClimateRequestPolicy {
    const val URL = "https://sea-snc-tsp-api-gw.zeekrlife.com/ms-remote-control/v1.0/remoteControl/control"
    fun allowed(request: Request, target: ClimateTarget): Boolean {
        if (request.method != "POST" || request.url.toString() != URL || request.header("X-VIN").isNullOrBlank() ||
            request.header("authorization").isNullOrBlank()) return false
        val body = request.body ?: return false
        if (body.isDuplex() || body.isOneShot() || body.contentLength() !in 1..4096) return false
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8() == target.body()
    }
}

data class ClimateSnapshot(
    val frontLeft: Int? = null, val frontRight: Int? = null, val acOn: Boolean? = null,
    val cabinTemperature: Double? = null, val sourceTime: Instant? = null, val fetchedAt: Instant,
    val blowerActive: Boolean? = null,
    val heatLeft: Int? = null, val heatRight: Int? = null,
    val steeringHeat: Boolean? = null,
) {
    fun seat(channel: ClimateChannel): Int? = when (channel) {
        ClimateChannel.FRONT_LEFT -> frontLeft
        ClimateChannel.FRONT_RIGHT -> frontRight
        ClimateChannel.HEAT_LEFT -> heatLeft
        ClimateChannel.HEAT_RIGHT -> heatRight
        else -> null
    }
    fun level(channel: ClimateChannel): Int? = if (channel == ClimateChannel.STEERING)
        steeringHeat?.let { if (it) 1 else 0 } else seat(channel)
    fun confirms(target: ClimateTarget, sentAt: Instant, now: Instant): Boolean {
        val time = sourceTime ?: return false
        if (time.isBefore(sentAt) || time.isAfter(now.plusSeconds(30))) return false
        // interiorTemp is the measured cabin temperature, never the AC setpoint.
        return if (target.channel == ClimateChannel.AC) target.value == 0 && acOn == false
            else level(target.channel) == target.value
    }
    fun export(): JsonObject = buildJsonObject {
        put("frontLeftLevel", frontLeft?.let(::JsonPrimitive) ?: JsonNull)
        put("frontRightLevel", frontRight?.let(::JsonPrimitive) ?: JsonNull)
        put("heatLeftLevel", heatLeft?.let(::JsonPrimitive) ?: JsonNull)
        put("heatRightLevel", heatRight?.let(::JsonPrimitive) ?: JsonNull)
        put("acOn", acOn?.let(::JsonPrimitive) ?: JsonNull)
        put("blowerActive", blowerActive?.let(::JsonPrimitive) ?: JsonNull)
        put("steeringHeat", steeringHeat?.let(::JsonPrimitive) ?: JsonNull)
        put("cabinTemperature", cabinTemperature?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceTime", sourceTime?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("fetchedAt", fetchedAt.toString()); put("targetTemperatureReadback", "UNAVAILABLE")
    }
    companion object {
        fun parse(probe: Probe): ClimateSnapshot {
            require(probe.endpoint == Endpoint.STATUS && probe.outcome == ProbeOutcome.SUCCESS)
            val data = probe.data.at("additionalVehicleStatus.climateStatus")
            fun level(status: String, detail: String): Int? {
                val s = data.at(status).text()?.toIntOrNull()
                val d = data.at(detail).text()?.toIntOrNull()
                return when {
                    s == 2 && (d == null || d == 0 || d == 7) -> 0
                    s == 1 && d in 1..3 -> d
                    else -> null // Missing, contradictory or unfamiliar values stay unknown.
                }
            }
            val on = when (data.at("preClimateActive").text()?.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            // Official 1.6.6 ModelTransformKt reads heating Sts as a LEVEL, unlike ventilation.
            // 0/7 are off; 1..3 are levels. Do not reuse the ventilation 1=on, 2=off decoder.
            fun heatLevel(field: String): Int? = when (val value = data.at(field).text()?.toIntOrNull()) {
                0, 7 -> 0; 1, 2, 3 -> value; else -> null
            }
            return ClimateSnapshot(level("drvVentSts", "drvVentDetail"), level("passVentSts", "passVentDetail"), on,
                data.at("interiorTemp").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..100.0 },
                Capabilities.sourceTime(data.at("updateTime")), probe.fetchedAt,
                when (data.at("airBlowerActive").text()?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null },
                heatLevel("drvHeatSts"), heatLevel("passHeatingSts"),
                // SNC steerWhlHeatingSts: 1=on; observed 2=off. Unknown enums remain unknown.
                when (data.at("steerWhlHeatingSts").text()) { "1" -> true; "2" -> false; else -> null })
        }
    }
}
