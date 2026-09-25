package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import java.time.Instant

/** Commands transcribed from the AU 1.6.6 SNC Cmd/NetCmd implementation. */
enum class BodyAction(val title: String, val service: String, val command: String, val key: String, val value: String) {
    LOCK("锁定车辆", "RDL", "start", "door", "all"),
    UNLOCK("解锁车辆", "RDU", "stop", "door", "all"),
    SENTRY_ON("开启原厂哨兵", "RSM", "start", "rsm", "6"),
    SENTRY_OFF("关闭原厂哨兵", "RSM", "stop", "rsm", "6"),
    HORN("找车鸣笛一次", "RHL", "start", "rhl", "horn"),
    WINDOWS_OPEN("打开车窗", "RWS", "start", "target", "window"),
    WINDOWS_CLOSE("关闭车窗", "RWS", "stop", "target", "window"),
    // HFVehicleControlImpl maps both BACK_TRUNK_OPEN and BACK_TRUNK_UNLOCK to this latch request.
    // Do not send a whole-car unlock first or infer physical opening from its receipt.
    TRUNK_UNLOCK("解锁尾门", "RDU", "stop", "target", "trunk"),
    TRUNK_LOCK("锁定尾门", "RDL", "start", "target", "trunk"),
    // AU 1.6.6's common_lid_key chooses FRONT_CHARGE_LID, independent of the hatch's physical location.
    PORT_OPEN("打开充电口", "RDO", "start", "target", "front-charge-lid"),
    PORT_CLOSE("关闭充电口", "RDC", "stop", "target", "front-charge-lid");

    val observation: Pair<String, String>? get() = when (this) {
        LOCK -> "lock" to "已锁"; UNLOCK -> "lock" to "未锁"
        SENTRY_ON -> "sentry" to "开启"; SENTRY_OFF -> "sentry" to "关闭"
        PORT_OPEN -> "port" to "打开"; PORT_CLOSE -> "port" to "关闭"
        else -> null
    }
}

sealed interface VehicleCommand {
    val title: String
    fun body(): String
    data class Body(val action: BodyAction) : VehicleCommand {
        override val title get() = action.title
        override fun body() = payload(action.service, action.command, listOf(action.key to action.value))
    }
    data class Comfort(val targets: List<ClimateTarget>) : VehicleCommand {
        init {
            require(targets.isNotEmpty() && targets.size <= ClimateChannel.entries.size)
            require(targets.map { it.channel }.distinct().size == targets.size)
            for ((heat, vent) in listOf(ClimateChannel.HEAT_LEFT to ClimateChannel.FRONT_LEFT, ClimateChannel.HEAT_RIGHT to ClimateChannel.FRONT_RIGHT)) {
                require(!(targets.any { it.channel == heat && it.value > 0 } && targets.any { it.channel == vent && it.value > 0 }))
            }
        }
        override val title get() = targets.joinToString("、") { it.label }
        override fun body(): String = payload("ZAF", "start", buildList {
            targets.forEach { addAll(it.parameters()) }
            add("operation" to "4")
        })
    }
    companion object {
        private fun payload(service: String, command: String, parameters: List<Pair<String, String>>) = buildJsonObject {
            put("serviceId", service); put("command", command)
            putJsonObject("setting") { putJsonArray("serviceParameters") {
                parameters.forEach { (key, value) -> add(buildJsonObject { put("key", key); put("value", value) }) }
            } }
        }.toString()
    }
}

object VehicleCommandPolicy {
    fun allowed(request: Request, command: VehicleCommand): Boolean {
        if (request.method != "POST" || request.url.toString() != ClimateRequestPolicy.URL ||
            request.header("X-VIN").isNullOrBlank() || request.header("authorization").isNullOrBlank()) return false
        val body = request.body ?: return false
        if (body.isDuplex() || body.isOneShot() || body.contentLength() !in 1..4096) return false
        val buffer = Buffer(); body.writeTo(buffer)
        return buffer.readUtf8() == command.body()
    }
}

enum class CommandResult(val label: String) {
    MATCHED("车况已回传"), ACCEPTED("已受理 · 实际结果待核实"), REJECTED("未受理"), UNKNOWN("结果待核实 · 未自动重试"),
}

data class ComfortPreferences(
    val target: Int = 22, val steeringHeat: Boolean = false, val seatHeat: Boolean = false,
    val seatVentilation: Boolean = false, val bothSeats: Boolean = false,
    val minutes: Int = 30, val stopAtTarget: Boolean = true, val finishWhenComfortable: Boolean = true,
) {
    init { require(target in 18..28 && minutes in listOf(5, 10, 15, 20, 30)) }
    fun json() = buildJsonObject {
        put("target", target); put("steeringHeat", steeringHeat); put("seatHeat", seatHeat)
        put("seatVentilation", seatVentilation); put("bothSeats", bothSeats); put("minutes", minutes); put("stopAtTarget", stopAtTarget)
        put("finishWhenComfortable",finishWhenComfortable)
    }
    companion object {
        fun parse(o: JsonElement?) = ComfortPreferences(o.at("target").text()?.toIntOrNull()?.takeIf { it in 18..28 } ?: 22,
            o.at("steeringHeat").text() == "true", o.at("seatHeat").text() == "true", o.at("seatVentilation").text() == "true",
            o.at("bothSeats").text() == "true", o.at("minutes").text()?.toIntOrNull()?.takeIf { it in listOf(5,10,15,20,30) } ?: 30,
            o.at("stopAtTarget").text() != "false", o.at("finishWhenComfortable").text() == "true")
    }
}

/** A preference is permission to choose an adjunct, not an instruction to turn everything on. */
object PreparationPlanner {
    fun plan(preferences: ComfortPreferences, snapshot: ClimateSnapshot, now: Instant): VehicleCommand.Comfort? {
        // The thermostat can honor an explicit target without a fresh cabin observation.
        // An old/missing temperature must never select heating or ventilation adjuncts.
        val temperature = snapshot.cabinTemperature?.takeIf {
            it.isFinite() && snapshot.sourceTime?.let { source ->
                !source.isBefore(now.minusSeconds(300)) && !source.isAfter(now.plusSeconds(30))
            } == true
        } ?: return VehicleCommand.Comfort(listOf(ClimateTarget(ClimateChannel.AC, preferences.target, preferences.minutes)))
        val cold = temperature < preferences.target - 1
        val hot = temperature > preferences.target + 1
        if (!cold && !hot && preferences.stopAtTarget) return null
        return VehicleCommand.Comfort(buildList {
            add(ClimateTarget(ClimateChannel.AC, preferences.target, preferences.minutes))
            if (hot && preferences.seatVentilation) {
                add(ClimateTarget(ClimateChannel.FRONT_LEFT, 2, preferences.minutes))
                if (preferences.bothSeats) add(ClimateTarget(ClimateChannel.FRONT_RIGHT, 2, preferences.minutes))
            }
            if (cold && preferences.seatHeat) {
                add(ClimateTarget(ClimateChannel.HEAT_LEFT, 2, preferences.minutes))
                if (preferences.bothSeats) add(ClimateTarget(ClimateChannel.HEAT_RIGHT, 2, preferences.minutes))
            }
            if (cold && preferences.steeringHeat) add(ClimateTarget(ClimateChannel.STEERING, 1, if (preferences.minutes < 8) 5 else 8))
        })
    }
}
