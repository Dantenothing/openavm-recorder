package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only normalized, allowlisted telemetry. No raw response, coordinates, VIN or credentials. */
data class OverviewReading(val value: String, val source: Long?, val fetched: Long, val readable: Boolean = true) {
    fun fresh(now: Instant): Boolean = readable && source != null && source >= 946684800000L &&
        source <= now.plusSeconds(120).toEpochMilli() && source >= now.minusSeconds(300).toEpochMilli()
    fun timeLabel(now: Instant): String = when {
        source == null -> "来源时间未知"
        source < 946684800000L || source > now.plusSeconds(120).toEpochMilli() -> "来源时间异常"
        else -> DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(source)) + " 记录"
    }
}

data class VehicleOverview(
    val vehicleKey: String? = null,
    val readings: Map<String, OverviewReading> = emptyMap(),
    val acOn: Boolean? = null,
    val nickname: String = "我的 7X",
    val target: Int = 22,
    val refreshedAt: Long? = null,
    val refreshingAt: Long? = null,
    val message: String? = null,
    val blowerActive: Boolean? = null,
    val pendingBody: PendingBody? = null,
    val actionInFlight: String? = null,
    val actionAt: Long? = null,
    val sync: SyncStatus = SyncStatus(),
    val motion: MotionReading? = null,
    val climateSource: Long? = null,
    val climateFetched: Long? = null,
) {
    // Cabin temperature and AC flags must each carry their own source evidence. Older caches
    // deliberately start unverified; a new fetch of another endpoint cannot revive these flags.
    fun climateFresh(now: Instant): Boolean = climateSource?.let { source ->
        source >= 946684800000L && now.toEpochMilli() - source in -30_000..300_000 &&
            climateFetched?.let { fetched -> now.toEpochMilli() - fetched in -30_000..300_000 && source <= fetched + 30_000 } == true
    } == true
    fun actionRunning(now: Instant) = actionInFlight!=null && actionAt?.let { now.toEpochMilli()-it in 0..100_000 }==true
    fun refreshing(now: Instant = Instant.now()) = refreshingAt?.let { now.toEpochMilli() - it in 0..100_000 } == true
    fun refreshMessage() = message?.takeIf { it in OnlineRefreshFlow.progressMessages } ?: "正在读取车辆状态…"
    fun queryLabel(now: Instant): String = when {
        refreshing(now) -> "查询中"
        sync.completed > sync.success && sync.completed >= (refreshedAt ?: 0) -> "查询失败"
        refreshedAt != null -> "查询 ${SyncPolicy.clockLabel(refreshedAt, now)}"
        else -> "刷新"
    }
    fun oldFields(now: Instant): String {
        val fields = listOf("lock" to "车锁", "sentry" to "哨兵", "battery" to "电量", "trunk" to "尾门", "port" to "充电口")
            .filter { readings[it.first]?.let { r -> !r.fresh(now) } == true }.map { it.second }
        return if (fields.isEmpty()) "各项状态保留各自采样时间" else fields.joinToString("、") + "为上次记录"
    }
    // Display the last observation prominently; freshness still gates automation and the thermal halo.
    @Suppress("UNUSED_PARAMETER")
    fun cabin(now: Instant) = readings["cabin_temperature"]?.value?.replace(" ", "") ?: "—°C"
    fun cabinCaption(now: Instant) = if (readings["cabin_temperature"]?.fresh(now) == true) "车内温度" else "上次车温"
    fun motionLabel(now: Instant) = motion?.label(now) ?: "— · 等待车况"
    fun temperatureTimeLabel(now: Instant): String = readings["cabin_temperature"]?.let {
        "车温上报 ${it.timeLabel(now).removeSuffix(" 记录")}" + if (it.fresh(now)) "" else " · 暂无新上报"
    } ?: "等待首次读取车温"
    fun refreshResult(previous: OverviewReading?, now: Instant): String {
        if (message != null) return message
        val reading = readings["cabin_temperature"] ?: return "已查询云端 · 车辆未返回温度"
        if (!reading.readable) return "读取未完成 · 保留上次温度"
        if (!reading.fresh(now)) return "已查询云端 · 车温暂无新上报"
        return if (reading.source == previous?.source && reading.value == previous?.value) "已查询云端 · 车温记录未变化" else "已查询云端 · 收到新车温"
    }
    fun climateLabel(now: Instant): String = when {
        !climateFresh(now) -> "运行状态待核实"
        blowerActive == true -> "空调送风中"
        acOn == true -> "空调已开启"
        acOn == false -> "远程空调未运行"
        else -> "空调状态待核实"
    }
    fun thermalState(now: Instant): String {
        val reading = readings["cabin_temperature"]?.takeIf { it.fresh(now) } ?: return "neutral"
        val temp = reading.value.substringBefore(' ').toDoubleOrNull() ?: return "neutral"
        return when { temp > target + 1 -> "hot"; temp < target - 1 -> "cold"; else -> "neutral" }
    }
    fun thermalLabel(now: Instant): String {
        val reading = readings["cabin_temperature"] ?: return "等待首次读取"
        if (!reading.fresh(now)) return if (reading.source == null) "采集时间未知 · 非实时" else "上次测量 · 非实时"
        val degrees = reading.value.substringBefore(' ').toDoubleOrNull() ?: return "车温待核实"
        return if (degrees > target + 1) "车内偏热" else if (degrees < target - 1) "车内偏冷" else "接近目标"
    }
    fun updated(report: Report, retainTemperature: Boolean = false): VehicleOverview {
        require(!report.demo) { "Synthetic reports cannot enter the real widget cache" }
        val next = readings.toMutableMap()
        report.capabilities.filter { it.id in IDS }.forEach { cap ->
            val probe = report.probes.lastOrNull { it.endpoint == cap.endpoint } ?: return@forEach
            if (cap.read == ReadEvidence.FOUND) next[cap.id] = OverviewReading(cap.value, cap.sourceTime?.toEpochMilli(), probe.fetchedAt.toEpochMilli())
            else if (probe.outcome == ProbeOutcome.SUCCESS) {
                if (retainTemperature && cap.id == "cabin_temperature") next[cap.id]?.let { next[cap.id] = it.copy(readable = false) }
                else next.remove(cap.id)
            }
            else next[cap.id]?.let { next[cap.id] = it.copy(readable = false) }
        }
        val status = report.probes.lastOrNull { it.endpoint == Endpoint.STATUS }
        val climate = if (status?.outcome == ProbeOutcome.SUCCESS) ClimateSnapshot.parse(status) else null
        val failures = report.probes.filter { it.outcome != ProbeOutcome.SUCCESS }
        val confirmed = pendingBody?.let { pending -> report.probes.any { pending.matches(it) } } == true
        return copy(readings = next, acOn = if (status == null) acOn else climate?.acOn, blowerActive = if (status == null) blowerActive else climate?.blowerActive,
            climateSource = if (status == null) climateSource else climate?.sourceTime?.toEpochMilli(),
            climateFetched = if (status == null) climateFetched else climate?.fetchedAt?.toEpochMilli(),
            motion = if (status?.outcome == ProbeOutcome.SUCCESS) MotionReading.from(status) else motion,
            pendingBody = if (confirmed) null else pendingBody,
            refreshedAt = report.probes.filter { it.outcome == ProbeOutcome.SUCCESS }.maxOfOrNull { it.fetchedAt.toEpochMilli() } ?: refreshedAt,
            message = failures.firstOrNull()?.let { "${it.endpoint.title}：${it.outcome.label}" }
                ?: pendingBody?.let { "${it.action.title} · ${if (confirmed) "车况已回传" else "等待车辆回传"}" })
    }
    fun encode(): String = buildJsonObject {
        put("schema", 1); put("vehicleKey", vehicleKey?.let(::JsonPrimitive) ?: JsonNull)
        put("nickname", nickname); put("target", target)
        put("acOn", acOn?.let(::JsonPrimitive) ?: JsonNull)
        put("blowerActive", blowerActive?.let(::JsonPrimitive) ?: JsonNull)
        put("climateSource", climateSource?.let(::JsonPrimitive) ?: JsonNull)
        put("climateFetched", climateFetched?.let(::JsonPrimitive) ?: JsonNull)
        put("refreshedAt", refreshedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("refreshingAt", refreshingAt?.let(::JsonPrimitive) ?: JsonNull)
        put("message", message?.let(::JsonPrimitive) ?: JsonNull)
        put("sync", sync.json())
        put("motion", motion?.json() ?: JsonNull)
        put("actionInFlight",actionInFlight?.let(::JsonPrimitive) ?: JsonNull); put("actionAt",actionAt?.let(::JsonPrimitive) ?: JsonNull)
        pendingBody?.let { pending -> putJsonObject("pendingBody") {
            put("action", pending.action.name); put("sentAt", pending.sentAt)
            put("before", pending.before?.let(::JsonPrimitive) ?: JsonNull)
        } }
        putJsonObject("readings") { readings.filterKeys { it in IDS }.forEach { (id, reading) ->
            putJsonObject(id) { put("value", reading.value); put("source", reading.source?.let(::JsonPrimitive) ?: JsonNull)
                put("fetched", reading.fetched); put("readable", reading.readable) }
        } }
    }.toString()
    companion object {
        val IDS = setOf("battery", "range", "cabin_temperature", "sentry", "lock", "trunk", "port", "vehicle_location", "odometer", "tyres", "parking_state", "vehicle_speed")
        fun key(vehicle: Vehicle): String = MessageDigest.getInstance("SHA-256").digest(vehicle.vin.toByteArray()).joinToString("") { "%02x".format(it) }
        fun decode(text: String): VehicleOverview {
            require(text.length <= 32768)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["schema"]?.jsonPrimitive?.intOrNull == 1)
            fun long(key: String) = root[key]?.jsonPrimitive?.longOrNull
            val name = root["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 20 } ?: "我的 7X"
            val readings = (root["readings"] as? JsonObject).orEmpty().filterKeys { it in IDS }.mapNotNull { (id, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val number = obj["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 64 } ?: return@mapNotNull null
                val fetched = obj["fetched"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                id to OverviewReading(number, obj["source"]?.jsonPrimitive?.longOrNull, fetched, obj["readable"]?.jsonPrimitive?.booleanOrNull == true)
            }.toMap()
            return VehicleOverview(root["vehicleKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }, readings,
                root["acOn"]?.jsonPrimitive?.booleanOrNull, name, root["target"]?.jsonPrimitive?.intOrNull?.takeIf { it in 18..28 } ?: 22,
                long("refreshedAt"), long("refreshingAt"), root["message"]?.jsonPrimitive?.contentOrNull?.take(120), root["blowerActive"]?.jsonPrimitive?.booleanOrNull,
                (root["pendingBody"] as? JsonObject)?.let { obj -> runCatching {
                    PendingBody(BodyAction.valueOf(obj.getValue("action").jsonPrimitive.content), obj.getValue("sentAt").jsonPrimitive.long,
                        obj["before"]?.jsonPrimitive?.contentOrNull?.take(12))
                }.getOrNull() },root["actionInFlight"]?.jsonPrimitive?.contentOrNull?.takeIf { it in CardControl.actions },long("actionAt"), SyncStatus.parse(root["sync"]), MotionReading.parse(root["motion"]),long("climateSource"),long("climateFetched"))
        }
    }
}
