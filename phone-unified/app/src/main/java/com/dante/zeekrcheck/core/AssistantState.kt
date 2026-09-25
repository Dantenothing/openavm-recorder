package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.time.*
import java.util.UUID
import kotlin.math.*

data class DeparturePlan(
    val id: String = UUID.randomUUID().toString(), val vehicleKey: String, val title: String = "出发计划",
    val departure: Long, val zone: String = ZoneId.systemDefault().id, val days: Set<Int> = emptySet(),
    val leadMinutes: Int = 15, val preferences: ComfortPreferences, val enabled: Boolean = true,
    val handled: Long = 0, val result: String = "等待安排",
) {
    init { require(leadMinutes in 5..60 && days.all { it in 1..7 }); ZoneId.of(zone) }
    fun next(now: Instant): Instant? {
        if (!enabled) return null
        if (days.isEmpty()) return Instant.ofEpochMilli(departure).takeIf { departure > handled && it.minusSeconds(leadMinutes * 60L).isAfter(now) }
        val z = ZoneId.of(zone)
        val clock = Instant.ofEpochMilli(departure).atZone(z).toLocalTime()
        val date = now.atZone(z).toLocalDate()
        return (0L..8L).map { date.plusDays(it) }.filter { it.dayOfWeek.value in days }
            .map { it.atTime(clock).atZone(z).toInstant() }
            .firstOrNull { it.toEpochMilli() > handled && it.minusSeconds(leadMinutes * 60L).isAfter(now) }
    }
    fun json() = buildJsonObject {
        put("id", id); put("vehicleKey", vehicleKey); put("title", title); put("departure", departure); put("zone", zone)
        putJsonArray("days") { days.sorted().forEach { add(it) } }; put("leadMinutes", leadMinutes)
        put("preferences", preferences.json()); put("enabled", enabled); put("handled", handled); put("result", result)
    }
    companion object {
        fun parse(e: JsonElement) = DeparturePlan(e.at("id").text()!!.also { UUID.fromString(it) }, e.at("vehicleKey").text()!!,
            e.at("title").text()?.take(30) ?: "出发计划", e.at("departure").text()!!.toLong(), e.at("zone").text()!!,
            (e.at("days") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.toSet(),
            e.at("leadMinutes").text()!!.toInt(), ComfortPreferences.parse(e.at("preferences")), e.at("enabled").text() == "true",
            e.at("handled").text()?.toLongOrNull() ?: 0, e.at("result").text()?.take(120) ?: "等待安排")
    }
}

/** Stored encrypted on the phone. Coordinates are never part of exported diagnostics. */
data class CarLocation(val latitude: Double, val longitude: Double, val source: Long?, val address: String = "", val verified: Boolean = false,
    val addressLatitude: Double? = null, val addressLongitude: Double? = null) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun fresh(now: Long) = source?.let { now - it in 0..300_000 } == true
    fun distance(other: CarLocation): Double {
        val dLat = Math.toRadians(other.latitude - latitude); val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(latitude))*cos(Math.toRadians(other.latitude))*sin(dLon/2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
    fun json() = buildJsonObject { put("lat", latitude); put("lon", longitude); put("source", source?.let(::JsonPrimitive) ?: JsonNull); put("address", address); put("verified", verified)
        put("addressLat", addressLatitude?.let(::JsonPrimitive) ?: JsonNull); put("addressLon", addressLongitude?.let(::JsonPrimitive) ?: JsonNull) }
    companion object {
        fun parse(e: JsonElement?) = runCatching { CarLocation(e.at("lat").text()!!.toDouble(), e.at("lon").text()!!.toDouble(), e.at("source").text()?.toLongOrNull(), e.at("address").text()?.take(160) ?: "", e.at("verified").text() == "true",
            e.at("addressLat").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 },
            e.at("addressLon").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }) }.getOrNull()
        fun from(probe: Probe): CarLocation? {
            if (probe.endpoint != Endpoint.STATUS || probe.outcome != ProbeOutcome.SUCCESS) return null
            val p = probe.data.at("basicVehicleStatus.position")
            val lat = p.at("latitude").text()?.toDoubleOrNull() ?: return null
            val lon = p.at("longitude").text()?.toDoubleOrNull() ?: return null
            // New SNC decimal coordinates only. Never guess a conversion for integer ECARX positions.
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
            // SNC PositionVo/ModelTransform uses string 1/0; some responses serialize booleans.
            if (p.at("marsCoordinates").text() in setOf("true", "1") || p.at("posCanBeTrusted").text() in setOf("false", "0")) return null
            return CarLocation(lat, lon, Capabilities.sourceTime(p.at("updateTime"))?.toEpochMilli())
        }
    }
}

data class OperationEntry(val at: Long, val title: String, val result: String) {
    fun json() = buildJsonObject { put("at", at); put("title", title); put("result", result) }
}

data class AssistantState(
    val preferences: ComfortPreferences = ComfortPreferences(), val plans: List<DeparturePlan> = emptyList(),
    val history: List<OperationEntry> = emptyList(), val paused: Boolean = false,
    val location: CarLocation? = null, val home: CarLocation? = null, val homeRadius: Int = 200,
    val guardEnabled: Boolean = false, val guardMessage: String = "规则未启用", val lastGuardSource: Long = 0,
    val parkingNote: String = "", val operationPending: Boolean = false, val operationMessage: String = "",
    val activePreparation: PreparationSession? = null,
    val parkingGuard: ParkingGuard = ParkingGuard(), val widgetSyncEnabled: Boolean = true,
    val carBluetoothAddress: String = "", val carBluetoothName: String = "",
    val homeGuardEnabled: Boolean = false, val homeGuard: HomeSentryGuard = HomeSentryGuard(),
    val pendingBodyAction: BodyAction? = null,
    val temperatureUpdate: TemperatureUpdate? = null,
    val temperatureReceipt: TemperatureReceipt? = null,
    val temperatureRetryAfter: Long = 0,
) {
    fun encode() = buildJsonObject {
        put("schema", 1); put("preferences", preferences.json()); putJsonArray("plans") { plans.take(20).forEach { add(it.json()) } }
        putJsonArray("history") { history.take(80).forEach { add(it.json()) } }; put("paused", paused)
        put("location", location?.json() ?: JsonNull); put("home", home?.json() ?: JsonNull); put("homeRadius", homeRadius)
        put("guardEnabled", guardEnabled); put("guardMessage", guardMessage); put("lastGuardSource", lastGuardSource)
        put("parkingNote", parkingNote); put("operationPending", operationPending); put("operationMessage", operationMessage)
        put("preparation", activePreparation?.json() ?: JsonNull)
        put("parkingGuard", parkingGuard.json()); put("widgetSyncEnabled", widgetSyncEnabled)
        put("carBluetoothAddress", carBluetoothAddress); put("carBluetoothName", carBluetoothName)
        put("homeGuardEnabled", homeGuardEnabled); put("homeGuard", homeGuard.json())
        put("pendingBodyAction", pendingBodyAction?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("temperatureUpdate", temperatureUpdate?.json() ?: JsonNull)
        put("temperatureReceipt", temperatureReceipt?.json() ?: JsonNull)
        put("temperatureRetryAfter", temperatureRetryAfter)
    }.toString()
    companion object {
        fun parse(text: String): AssistantState {
            require(text.length <= 65_536); val o = Json.parseToJsonElement(text)
            require(o.at("schema").text() == "1")
            return AssistantState(ComfortPreferences.parse(o.at("preferences")),
                (o.at("plans") as? JsonArray).orEmpty().take(20).mapNotNull { runCatching { DeparturePlan.parse(it) }.getOrNull() },
                (o.at("history") as? JsonArray).orEmpty().take(80).mapNotNull { e -> e.at("at").text()?.toLongOrNull()?.let { OperationEntry(it, e.at("title").text()?.take(160) ?: "操作", e.at("result").text()?.take(180) ?: "未知") } },
                o.at("paused").text() == "true", CarLocation.parse(o.at("location")), CarLocation.parse(o.at("home")),
                o.at("homeRadius").text()?.toIntOrNull()?.coerceIn(100, 1000) ?: 200, o.at("guardEnabled").text() == "true",
                o.at("guardMessage").text()?.take(160) ?: "规则未启用", o.at("lastGuardSource").text()?.toLongOrNull() ?: 0,
                o.at("parkingNote").text()?.take(80) ?: "", o.at("operationPending").text() == "true", o.at("operationMessage").text()?.take(180) ?: "",
                PreparationSession.parse(o.at("preparation")), ParkingGuard.parse(o.at("parkingGuard")),
                o.at("widgetSyncEnabled").text() != "false",
                o.at("carBluetoothAddress").text()?.takeIf { it.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) } ?: "",
                o.at("carBluetoothName").text()?.take(80) ?: "", o.at("homeGuardEnabled").text() == "true", HomeSentryGuard.parse(o.at("homeGuard")),
                runCatching { BodyAction.valueOf(o.at("pendingBodyAction").text() ?: "") }.getOrNull(),
                TemperatureUpdate.parse(o.at("temperatureUpdate")),
                TemperatureReceipt.parse(o.at("temperatureReceipt")),
                o.at("temperatureRetryAfter").text()?.toLongOrNull() ?: 0)
        }
    }
}

data class PreparationSession(val vehicleKey: String, val started: Long, val deadline: Long, val preferences: ComfortPreferences,
    val channels: List<ClimateChannel>, val status: String = "已受理，运行状态待核实", val finished: Boolean = false, val stopRequested: Boolean = false,
    val phase: PreparationPhase = PreparationPhase.ACCEPTED, val initialTemperature: Double? = null,
    val lastTemperature: Double? = null, val lastSource: Long? = null, val stableSince: Long? = null, val endedAt: Long = 0,
    val remoteRunningObserved: Boolean = false, val createdAt: Long = started, val stopSentAt: Long = 0,
    val thermal: ThermalSession? = null) {
    fun json() = buildJsonObject {
        put("vehicleKey", vehicleKey); put("started", started); put("deadline", deadline); put("preferences", preferences.json())
        putJsonArray("channels") { channels.forEach { add(it.name) } }; put("status", status); put("finished", finished); put("stopRequested", stopRequested)
        put("phase", phase.name); put("initialTemperature", initialTemperature?.let(::JsonPrimitive) ?: JsonNull)
        put("lastTemperature", lastTemperature?.let(::JsonPrimitive) ?: JsonNull)
        put("lastSource", lastSource?.let(::JsonPrimitive) ?: JsonNull); put("stableSince", stableSince?.let(::JsonPrimitive) ?: JsonNull)
        put("endedAt", endedAt)
        put("remoteRunningObserved", remoteRunningObserved)
        put("createdAt", createdAt); put("stopSentAt", stopSentAt)
        put("thermal",thermal?.json() ?: JsonNull)
    }
    companion object { fun parse(e: JsonElement?) = runCatching {
        PreparationSession(e.at("vehicleKey").text()!!, e.at("started").text()!!.toLong(), e.at("deadline").text()!!.toLong(), ComfortPreferences.parse(e.at("preferences")),
            (e.at("channels") as JsonArray).map { ClimateChannel.valueOf(it.jsonPrimitive.content) }, e.at("status").text()!!.take(150), e.at("finished").text() == "true", e.at("stopRequested").text() == "true",
            e.at("phase").text()?.let { PreparationPhase.valueOf(it) } ?: if (e.at("finished").text() == "true") PreparationPhase.UNKNOWN else PreparationPhase.ACCEPTED,
            e.at("initialTemperature").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..100.0 },
            e.at("lastTemperature").text()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..100.0 },
            e.at("lastSource").text()?.toLongOrNull(), e.at("stableSince").text()?.toLongOrNull(), e.at("endedAt").text()?.toLongOrNull() ?: 0,
            e.at("remoteRunningObserved").text() == "true",
            e.at("createdAt").text()?.toLongOrNull() ?: e.at("started").text()!!.toLong(),
            e.at("stopSentAt").text()?.toLongOrNull() ?: if(e.at("stopRequested").text()=="true") e.at("started").text()!!.toLong() else 0,
            ThermalSession.parse(e.at("thermal")))
    }.getOrNull() }
}
