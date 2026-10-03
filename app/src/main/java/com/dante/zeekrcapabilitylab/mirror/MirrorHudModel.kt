package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.drivemap.*
import java.util.Locale

/** Render only the current recorder-owned streams; this helper performs no acquisition. */
internal fun mirrorHudFrame(now: Long, vehicle: VehicleTelemetryCaptureState, gps: TelemetryCaptureState): DriveHudFrame =
    DriveHudModel.frame(now, vehicle.latestSample?.takeIf { vehicle.status == VehicleTelemetryStatus.RECORDING }
        ?.values?.mapValues { (_, field) -> DriveHudObservation(field.readElapsedMs, field.number, field.text, field.status) }.orEmpty(),
        gps.speedMps?.toDouble()?.takeIf { gps.status == TelemetryStatus.RECORDING },
        gps.lastElapsedMs.takeIf { gps.status == TelemetryStatus.RECORDING })

/** Redraw exactly when an already-observed field expires; never keep a sensor or periodic poll alive. */
internal fun mirrorHudExpiries(now: Long, vehicle: VehicleTelemetryCaptureState, gps: TelemetryCaptureState): List<Long> =
    (vehicle.latestSample?.takeIf { vehicle.status == VehicleTelemetryStatus.RECORDING }?.values?.values?.map { it.readElapsedMs }.orEmpty() +
        listOfNotNull(gps.lastElapsedMs?.takeIf { gps.status == TelemetryStatus.RECORDING }))
        .filter { it in 0..now && now - it <= DriveHudModel.MAX_AGE_MS }
        .map { it + DriveHudModel.MAX_AGE_MS + 1 }.distinct().sorted()

internal data class MirrorHudText(val primary: String, val controls: String, val pedals: String)

internal fun mirrorHudText(frame: DriveHudFrame, chinese: Boolean): MirrorHudText {
    fun tr(en: String, zh: String) = if (chinese) zh else en
    val source = when (frame.speedSource) {
        DriveHudSpeedSource.VEHICLE -> tr("Vehicle", "车辆")
        DriveHudSpeedSource.GPS -> "GPS"
        DriveHudSpeedSource.UNAVAILABLE -> tr("Speed", "速度")
    }
    val speed = frame.speedKmh?.let { String.format(Locale.ROOT, "%.0f km/h", it) } ?: "—"
    val turn = when (frame.turn) {
        "left" -> tr("← Left", "← 左转")
        "right" -> tr("Right →", "右转 →")
        "hazard" -> tr("↔ Hazard", "↔ 双闪")
        "off" -> tr("Turn off", "转向灯关")
        else -> tr("Turn —", "转向 —")
    }
    val brake = when (frame.brakePressed) {
        true -> tr("Brake pressed", "制动踩下")
        false -> tr("Brake released", "制动松开")
        null -> tr("Brake —", "制动 —")
    }
    fun pedal(value: DriveHudPedal): String = value.raw?.takeIf { value.status == "PROVISIONAL_RAW" }
        ?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"
    return MirrorHudText("$source $speed · ${frame.gear ?: "—"}", "$turn · $brake",
        tr("Raw · unverified", "原始值 · 未验证") + "\n" + tr("Brake depth", "制动深度") + " ${pedal(frame.brakeDepth)} · " +
            tr("Accelerator", "油门") + " ${pedal(frame.acceleratorDepth)}")
}
