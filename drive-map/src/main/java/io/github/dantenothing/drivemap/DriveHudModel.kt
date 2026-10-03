package io.github.dantenothing.drivemap

/** Getter-return observation clock; it is not a verified ECU measurement timestamp. */
data class DriveHudObservation(
    val readElapsedMs: Long,
    val number: Double? = null,
    val text: String? = null,
    val status: String = "OK",
)

enum class DriveHudSpeedSource { VEHICLE, GPS, UNAVAILABLE }
data class DriveHudPedal(val raw: Double? = null, val status: String = "UNAVAILABLE")
data class DriveHudFrame(
    val vehicleSpeedKmh: Double? = null,
    val gpsSpeedKmh: Double? = null,
    val gear: String? = null,
    val turn: String? = null,
    val brakePressed: Boolean? = null,
    val brakeDepth: DriveHudPedal = DriveHudPedal(),
    val acceleratorDepth: DriveHudPedal = DriveHudPedal(),
) {
    val speedKmh: Double? get() = vehicleSpeedKmh ?: gpsSpeedKmh
    val speedSource: DriveHudSpeedSource get() = when {
        vehicleSpeedKmh != null -> DriveHudSpeedSource.VEHICLE
        gpsSpeedKmh != null -> DriveHudSpeedSource.GPS
        else -> DriveHudSpeedSource.UNAVAILABLE
    }
}

/** Pure mapping shared by live recorder and recorded playback. No polling, Android clock or IO. */
object DriveHudModel {
    const val MAX_AGE_MS = 2_000L
    val rawPedalKeys: Set<String> = setOf("brake_depth", "throttle_depth")
    private val statuses = setOf("OK", "PROVISIONAL_RAW", "NO_VALUE", "SENTINEL", "TINY_SENTINEL", "NON_FINITE",
        "OUT_OF_RANGE", "UNKNOWN_ENUM", "NON_INTEGER_ENUM", "UNAVAILABLE", "READ_FAILED", "INVALID_VALUE", "STALE")

    fun fresh(atElapsedMs: Long?, readElapsedMs: Long?): Boolean = atElapsedMs != null && readElapsedMs != null &&
        atElapsedMs >= 0 && readElapsedMs >= 0 && readElapsedMs <= atElapsedMs && atElapsedMs - readElapsedMs <= MAX_AGE_MS

    fun frame(
        atElapsedMs: Long?,
        vehicle: Map<String, DriveHudObservation> = emptyMap(),
        gpsSpeedMps: Double? = null,
        gpsElapsedMs: Long? = null,
    ): DriveHudFrame {
        fun usable(key: String) = vehicle[key]?.takeIf { it.status == "OK" && fresh(atElapsedMs, it.readElapsedMs) }
        fun binary(key: String) = usable(key)?.takeIf { it.text == null }?.number?.let { when (it) { 0.0 -> false; 1.0 -> true; else -> null } }
        val left = binary("turn_left")
        val right = binary("turn_right")
        val turn = usable("indicator")?.takeIf { it.number == null }?.text?.takeIf { it in setOf("off", "left", "right", "hazard") }
            ?: if (left != null && right != null) when { left && right -> "hazard"; left -> "left"; right -> "right"; else -> "off" } else null
        return DriveHudFrame(
            vehicleSpeedKmh = usable("speed")?.takeIf { it.text == null }?.number?.takeIf { it.isFinite() && it in 0.0..360.0 },
            gpsSpeedKmh = gpsSpeedMps?.takeIf { fresh(atElapsedMs, gpsElapsedMs) && it.isFinite() && it in 0.0..100.0 }?.times(3.6),
            gear = usable("gear")?.takeIf { it.number == null }?.text?.takeIf { it in setOf("P", "R", "N", "D") },
            turn = turn,
            brakePressed = binary("brake_pedal"),
            brakeDepth = pedal(atElapsedMs, vehicle["brake_depth"]),
            acceleratorDepth = pedal(atElapsedMs, vehicle["throttle_depth"]),
        )
    }

    private fun pedal(at: Long?, observation: DriveHudObservation?): DriveHudPedal {
        val value = observation ?: return DriveHudPedal()
        if (!fresh(at, value.readElapsedMs)) return DriveHudPedal(status = "STALE")
        if (value.status != "PROVISIONAL_RAW") return DriveHudPedal(status =
            value.status.takeIf { it in statuses && it != "OK" } ?: "INVALID_VALUE")
        val number = value.number?.takeIf { it.isFinite() && it in -1_000_000.0..1_000_000.0 && value.text == null }
            ?: return DriveHudPedal(status = "INVALID_VALUE")
        return DriveHudPedal(number, "PROVISIONAL_RAW")
    }

    /** Count-only availability evidence; arbitrary errors, raw values and clocks are excluded. */
    fun pedalEvidence(observations: List<Map<String, DriveHudObservation>>): Map<String, String> {
        val selected = observations.take(120_000)
        return buildMap {
            put("scale", "UNVERIFIED_RAW_NO_PERCENTAGE")
            put("sampleCount", selected.size.toString())
            put("inputLimited", (observations.size > selected.size).toString())
            rawPedalKeys.forEach { key ->
                val fields = selected.mapNotNull { it[key] }
                put("${key}_observed", fields.size.toString())
                put("${key}_rawAvailable", fields.count { it.status == "PROVISIONAL_RAW" && it.text == null &&
                    it.number?.let { number -> number.isFinite() && number in -1_000_000.0..1_000_000.0 } == true }.toString())
                put("${key}_unavailable", fields.count { it.status != "PROVISIONAL_RAW" }.toString())
            }
        }
    }
}
