package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.dante.zeekrcheck.BuildConfig

enum class Endpoint(val title: String, val path: String) {
    STATUS("基础车况", "ms-vehicle-status/api/v1.0/vehicle/status/latest"),
    SENTRY("车辆模式", "ms-app-bff/api/v1.0/remoteControl/getVehicleState"),
    CHARGING("充电遥测", "ms-vehicle-status/api/v1.0/vehicle/status/qrvs"),
    SOC_LIMIT("充电上限", "ms-charge-manage/api/v1.0/charge/getLatestSoc"),
    CHARGE_PLAN("充电预约", "ms-charge-manage/api/v1.0/charge/getChargingPlan"),
    TRAVEL_PLAN("出发预约", "ms-charge-manage/api/v1.0/charge/getLatestTravelPlan"),
}

enum class ProbeOutcome(val label: String) {
    SUCCESS("接口读取成功"), REJECTED("请求被拒绝，原因待核对"), AUTH_REQUIRED("会话需重新验证"),
    RATE_LIMITED("请求频率受限"), NETWORK("网络或服务不可达"), TIMEOUT("请求超时"),
    INVALID_RESPONSE("响应格式不符合预期"), CANCELLED("已停止"), NOT_RUN("未检查"),
}

/** Raw payload is session-only, never serialized or logged. */
class Probe(val endpoint: Endpoint, val outcome: ProbeOutcome, val fetchedAt: Instant, val data: JsonElement? = null, val attempted: Boolean = true, val httpStatus: Int? = null) {
    override fun toString() = "Probe(${endpoint.name}, ${outcome.name})"
}

class Vehicle(val vin: String, val roleFieldNames: List<String>) {
    val label: String get() = "车辆 · VIN 尾号 ${vin.takeLast(4)}"
    override fun toString() = "Vehicle(redacted)"
    companion object {
        fun parseList(data: JsonElement): List<Vehicle> {
            val list = data as? JsonArray ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
            return list.map { item ->
                val vin = item.at("vin").text()
                if (vin == null || !vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                // Presence is diagnostic only: proprietary role values are not mapped to permissions.
                val roleFields = listOf("role", "roleType", "userRole", "isShared", "shared", "permission", "permissions", "authType")
                    .filter { item.at(it) != null }
                Vehicle(vin, roleFields)
            }.distinctBy { it.vin }
        }
    }
}

enum class ReadEvidence(val label: String) {
    FOUND("读到字段"), EMPTY("读取成功，内容为空"), MISSING("未取得可用字段"), FAILED("本次未读到"), PENDING("待检查"),
}
enum class AgeLabel(val label: String) {
    RECENT("来源时间较近"), STALE("旧数据"), UNKNOWN("来源时间未确认"), INVALID("来源时间异常"),
}

data class Capability(
    val id: String, val title: String, val endpoint: Endpoint, val value: String,
    val read: ReadEvidence, val sourcePath: String, val sourceTime: Instant?,
    val detail: String,
) {
    fun age(now: Instant): AgeLabel {
        val time = sourceTime ?: return AgeLabel.UNKNOWN
        if (time.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || time.isAfter(now.plusSeconds(120))) return AgeLabel.INVALID
        return if (time.isBefore(now.minusSeconds(300))) AgeLabel.STALE else AgeLabel.RECENT
    }
}

data class ControlEvidence(val title: String, val officialApp: String, val thisApp: String = "尚未验证控制、结果确认及后台执行")

data class Report(
    val demo: Boolean, val startedAt: Instant, val completedAt: Instant?,
    val vehicleLabel: String, val roleFieldNames: List<String>, val probes: List<Probe>,
) {
    val capabilities: List<Capability> get() = Capabilities.parse(probes)
    val controls = listOf(
        ControlEvidence("原厂哨兵", "用户已确认：原厂 App 可查看、开关"),
        ControlEvidence("座椅通风", "用户已确认：原厂 App 的 1/2/3 档连续操作会冲突；不代表座舱风量接口已验证"),
        ControlEvidence("原生出发预约", "用户尚未实际测试"),
        ControlEvidence("离家停车自动守护", "属于拟开发规则，仍需位置、驻车和后台能力验证"),
    )

    /** Construct from allowlisted, normalized fields. Never serialize raw Probe/Vehicle/config objects. */
    fun export(now: Instant, climate: JsonObject? = null): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schema", "zeekr-capability-check/1")
        val climateAttempts = if (demo) 0 else (climate?.get("requestAttempts") as? JsonPrimitive)?.intOrNull ?: 0
        val vehicleAttempts = if (demo) 0 else (climate?.get("vehicleRequestAttempts") as? JsonPrimitive)?.intOrNull ?: 0
        val attempts = climateAttempts + vehicleAttempts
        put("toolVersion", BuildConfig.VERSION_NAME)
        put("mode", if (demo) "DEMO_SYNTHETIC_NOT_A_REAL_CAR_TEST" else if (vehicleAttempts > 0) "LIVE_WITH_VEHICLE_CONTROLS" else if (attempts > 0) "LIVE_WITH_CLIMATE_TEST" else "LIVE_READ_ONLY")
        put("startedAt", startedAt.toString())
        put("completedAt", completedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("exportedAt", now.toString())
        put("accountRegionDeclared", "AU")
        put("officialAppVersionDeclared", "1.6.6")
        put("accountRoleDeclared", "guest")
        put("serverRoleInterpretation", "UNVERIFIED")
        put("vehicle", "selected vehicle; VIN and location omitted")
        putJsonArray("roleFieldsPresent") { roleFieldNames.forEach { add(it) } }
        put("controlRequestsSent", attempts)
        put("controlCountScope", "Climate and body/comfort command attempts in this process login session, not proof of delivery or an all-time count. The six capability probes are read-only.")
        if (!demo && climate != null) put("climateTest", climate)
        put("sourceTimeMeaning", "Per-group updateTime reported by API; semantics need vehicle comparison. HTTP fetch time is not observation time.")
        putJsonArray("endpoints") { probes.forEach { probe -> add(buildJsonObject {
            put("id", probe.endpoint.name); put("outcome", probe.outcome.name); put("attempted", probe.attempted)
            probe.httpStatus?.takeIf { it in 100..599 }?.let { put("httpStatus", it) }
            put("fetchedAt", if (probe.attempted) JsonPrimitive(probe.fetchedAt.toString()) else JsonNull)
        }) } }
        putJsonArray("capabilities") { capabilities.forEach { cap -> add(buildJsonObject {
            put("id", cap.id); put("title", cap.title); put("readEvidence", cap.read.name)
            put("normalizedValue", cap.value); put("sourcePath", cap.sourcePath)
            put("sourceTime", cap.sourceTime?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("ageAtExport", cap.age(now).name)
            put("write", "UNTESTED"); put("physicalConfirmation", "UNTESTED"); put("background", "UNTESTED")
            put("detail", cap.detail)
        }) } }
        putJsonArray("controlEvidence") { controls.forEach { control -> add(buildJsonObject {
            put("title", control.title); put("officialAppUserReport", control.officialApp); put("thisApp", control.thisApp)
        }) } }
    })
}

object Capabilities {
    private const val EV = "additionalVehicleStatus.electricVehicleStatus"
    private const val CLIMATE = "additionalVehicleStatus.climateStatus"
    private const val SAFETY = "additionalVehicleStatus.drivingSafetyStatus"
    private const val MAINTENANCE = "additionalVehicleStatus.maintenanceStatus"
    private const val POSITION = "basicVehicleStatus.position"

    fun parse(probes: List<Probe>): List<Capability> = buildList {
        fun field(id: String, title: String, endpoint: Endpoint, path: String, timePath: String, parse: (JsonElement?) -> String?) {
            val probe = probes.lastOrNull { it.endpoint == endpoint }
            val raw = probe?.data.at(path)
            val value = if (probe?.outcome == ProbeOutcome.SUCCESS) parse(raw) else null
            val evidence = when {
                probe == null || probe.outcome == ProbeOutcome.NOT_RUN -> ReadEvidence.PENDING
                probe.outcome != ProbeOutcome.SUCCESS -> ReadEvidence.FAILED
                value == null -> ReadEvidence.MISSING
                else -> ReadEvidence.FOUND
            }
            val detail = when (evidence) {
                ReadEvidence.FOUND -> "字段可读；不代表具备控制权限，也不代表当前车辆状态。"
                ReadEvidence.MISSING -> "字段缺失、值未知或超出校验范围；不能据此认定无权限。"
                ReadEvidence.FAILED -> (if (probe!!.attempted) "" else "未执行：") + probe.outcome.label
                else -> "等待本车检查。"
            }
            add(Capability(id, title, endpoint, value ?: "—", evidence, path,
                if (value != null) sourceTime(probe?.data.at(timePath)) else null, detail))
        }
        field("battery", "电量", Endpoint.STATUS, "$EV.chargeLevel", "$EV.updateTime") { number(it, 0.0..100.0, "%") }
        field("range", "续航", Endpoint.STATUS, "$EV.distanceToEmptyOnBatteryOnly", "$EV.updateTime") { number(it, 0.0..2000.0, "km") }
        field("cabin_temperature", "车内温度", Endpoint.STATUS, "$CLIMATE.interiorTemp", "$CLIMATE.updateTime") { number(it, -50.0..100.0, "°C") }
        field("sentry", "原厂哨兵", Endpoint.SENTRY, "vstdModeState", "updateTime") {
            when (it.text()) { "1" -> "开启"; "0" -> "关闭"; else -> null }
        }
        field("lock", "门锁", Endpoint.STATUS, "$SAFETY.centralLockingStatus", "$SAFETY.updateTime") {
            when (it.text()) { "1" -> "已锁"; "0" -> "未锁"; else -> null }
        }
        field("parking_state", "原厂驻车状态", Endpoint.STATUS, "$SAFETY.electricParkBrakeStatus", "$SAFETY.updateTime") {
            when (it.text()) { "0" -> "未驻车"; "1" -> "已驻车"; "2" -> "正在驻车"; "3" -> "驻车异常"; else -> null }
        }
        field("vehicle_speed", "车速", Endpoint.STATUS, "basicVehicleStatus.speed", "basicVehicleStatus.updateTime") {
            number(it, 0.0..400.0, "km/h")
        }
        // AU 1.6.6 ModelTransformKt: opening state is distinct from the trunk latch lock.
        field("trunk", "尾门开合", Endpoint.STATUS, "$SAFETY.trunkOpenStatus", "$SAFETY.updateTime") {
            when (it.text()) { "1" -> "打开"; "0" -> "关闭"; else -> null }
        }
        field("port", "充电口", Endpoint.STATUS, "$EV.chargeLidDcAcStatus", "$EV.updateTime") {
            when (it.text()) { "1" -> "打开"; "0" -> "关闭"; else -> null }
        }
        field("vehicle_location", "车辆位置", Endpoint.STATUS, POSITION, "$POSITION.updateTime") {
            val lat = finiteNumber(it.at("latitude")); val lon = finiteNumber(it.at("longitude"))
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) && it.at("posCanBeTrusted").text() !in setOf("false", "0") && it.at("marsCoordinates").text() !in setOf("true", "1")) "存在有效坐标（不展示位置）" else null
        }
        field("odometer", "里程", Endpoint.STATUS, "$MAINTENANCE.odometer", "$MAINTENANCE.updateTime") { number(it, 0.0..2000000.0, "km") }
        field("tyres", "胎压字段", Endpoint.STATUS, MAINTENANCE, "$MAINTENANCE.updateTime") {
            val count = listOf("tyreStatusDriver", "tyreStatusPassenger", "tyreStatusDriverRear", "tyreStatusPassengerRear")
                .count { name -> finiteNumber(it.at(name))?.let { p -> p in 0.0..600.0 } == true }
            if (count > 0) "$count / 4 个可读（轮位待核对）" else null
        }
        field("charge_power", "充电功率", Endpoint.CHARGING, "chargePower", "updateTime") { number(it, 0.0..1000.0, "kW · 单位待本车核对") }
        field("charge_voltage", "充电电压", Endpoint.CHARGING, "chargeVoltage", "updateTime") { number(it, 0.0..1500.0, "V · 单位待本车核对") }
        field("charge_current", "充电电流", Endpoint.CHARGING, "chargeCurrent", "updateTime") { number(it, 0.0..1000.0, "A · 单位待本车核对") }
        // The shape of the SOC-limit endpoint varies; don't guess which percentage represents the configured limit.
        listOf(Endpoint.SOC_LIMIT, Endpoint.CHARGE_PLAN, Endpoint.TRAVEL_PLAN).forEach { endpoint ->
            val probe = probes.lastOrNull { it.endpoint == endpoint }
            val empty = when (val data = probe?.data) { null, JsonNull -> true; is JsonObject -> data.isEmpty(); is JsonArray -> data.isEmpty(); else -> false }
            val evidence = when {
                probe == null || probe.outcome == ProbeOutcome.NOT_RUN -> ReadEvidence.PENDING
                probe.outcome != ProbeOutcome.SUCCESS -> ReadEvidence.FAILED
                empty -> ReadEvidence.EMPTY
                else -> ReadEvidence.FOUND
            }
            add(Capability(endpoint.name.lowercase(), endpoint.title, endpoint,
                when (evidence) { ReadEvidence.EMPTY -> "无返回内容"; ReadEvidence.FOUND -> "返回了数据 · 内容待本车核对"; else -> "—" },
                evidence, "data", if (evidence == ReadEvidence.FOUND) sourceTime(probe?.data.at("updateTime")) else null,
                when (evidence) {
                    ReadEvidence.EMPTY -> "成功响应为空；可能尚未设置，不能据此认定不支持。"
                    ReadEvidence.FOUND -> "只证明读取端点可达；保存、取消、时区和手机离线执行尚未验证。"
                    ReadEvidence.FAILED -> (if (probe!!.attempted) "" else "未执行：") + probe.outcome.label
                    else -> "等待本车检查。"
                }))
        }
    }

    internal fun sourceTime(raw: JsonElement?): Instant? {
        val text = raw.text() ?: return null
        val numeric = text.toLongOrNull()
        return try {
            when {
                numeric != null && numeric in 946684800000L..7258118400000L -> Instant.ofEpochMilli(numeric)
                // Pinned field mappings use epoch milliseconds. Seconds/ISO are not assumed equivalent.
                else -> null
            }
        } catch (_: Exception) { null }
    }
    private fun finiteNumber(raw: JsonElement?): Double? = raw.text()?.toDoubleOrNull()?.takeIf(Double::isFinite)
    private fun number(raw: JsonElement?, range: ClosedFloatingPointRange<Double>, unit: String): String? =
        finiteNumber(raw)?.takeIf { it in range }?.let {
            val value = if (it % 1 == 0.0) it.toLong().toString() else String.format(Locale.ROOT, "%.1f", it)
            "$value $unit"
        }
}

fun displayTime(time: Instant?): String = time?.let {
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
} ?: "未提供"

object Demo {
    fun report(now: Instant): Report {
        val recent = now.minusSeconds(75).toEpochMilli()
        val stale = now.minusSeconds(24 * 3600).toEpochMilli()
        val status = Json.parseToJsonElement("""{
          "additionalVehicleStatus": {
            "electricVehicleStatus":{"chargeLevel":68,"distanceToEmptyOnBatteryOnly":312,"updateTime":$recent},
            "climateStatus":{"interiorTemp":31.5,"updateTime":$stale},
            "drivingSafetyStatus":{"centralLockingStatus":"1"}
          },
          "basicVehicleStatus":{"position":{"latitude":0,"longitude":0}}
        }""")
        return Report(true, now, now, "示例车辆 · 非你的车辆", emptyList(), listOf(
            Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, status),
            Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{"vstdModeState":"1"}""")),
            Probe(Endpoint.CHARGING, ProbeOutcome.NETWORK, now),
            Probe(Endpoint.SOC_LIMIT, ProbeOutcome.REJECTED, now),
            Probe(Endpoint.CHARGE_PLAN, ProbeOutcome.SUCCESS, now, JsonObject(emptyMap())),
            Probe(Endpoint.TRAVEL_PLAN, ProbeOutcome.SUCCESS, now, JsonObject(emptyMap())),
        ))
    }
}
