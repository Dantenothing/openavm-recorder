package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.drivemap.*
import org.junit.Assert.*
import org.junit.Test

class MirrorHudModelTest {
    private fun vehicle(status: VehicleTelemetryStatus = VehicleTelemetryStatus.RECORDING) = VehicleTelemetryCaptureState(
        status = status, latestSample = VehicleTelemetrySample(1_000, 1_800_000_001_000, mapOf(
            "speed" to VehicleTelemetryValue(1_000, 42.0), "gear" to VehicleTelemetryValue(900, text = "D"),
            "brake_pedal" to VehicleTelemetryValue(800, 0.0), "indicator" to VehicleTelemetryValue(700, text = "left"),
            "brake_depth" to VehicleTelemetryValue(1_000, .35, status = "PROVISIONAL_RAW"))))
    private fun gps() = TelemetryCaptureState(TelemetryStatus.RECORDING, lastElapsedMs = 1_100, speedMps = 10f)

    @Test fun freshVehicleSpeedWinsAndRawPedalRemainsUnverified() {
        val frame = mirrorHudFrame(1_200, vehicle(), gps())
        assertEquals(DriveHudSpeedSource.VEHICLE, frame.speedSource)
        assertEquals(42.0, frame.speedKmh!!, 0.0)
        assertEquals(false, frame.brakePressed)
        val text = mirrorHudText(frame, false)
        assertTrue(text.primary.contains("Vehicle 42"))
        assertTrue(text.pedals.contains("unverified"))
        assertFalse(text.pedals.contains("%"))
    }

    @Test fun expiredVehicleFieldsBecomeUnknownWhileFreshGpsIsLabelledFallback() {
        val frame = mirrorHudFrame(3_001, vehicle(), gps())
        assertEquals(DriveHudSpeedSource.GPS, frame.speedSource)
        assertNull(frame.gear); assertNull(frame.turn); assertNull(frame.brakePressed)
        assertNull(frame.brakeDepth.raw)
        assertTrue(mirrorHudText(frame, false).primary.startsWith("GPS "))
    }

    @Test fun stoppedOrFailedRecorderCannotReuseARecentReading() {
        val stopped = mirrorHudFrame(1_200, vehicle(VehicleTelemetryStatus.SDK_TIMEOUT), gps().copy(status = TelemetryStatus.IDLE))
        assertEquals(DriveHudSpeedSource.UNAVAILABLE, stopped.speedSource)
        assertNull(stopped.brakePressed)
        val text = mirrorHudText(stopped, false)
        assertTrue(text.primary.contains("—")); assertTrue(text.controls.contains("Brake —"))
        assertFalse(text.controls.contains("released"))
    }

    @Test fun expiryRedrawsAreBoundedToObservedFieldsAndStopWhenIdle() {
        assertEquals(listOf(2_701L, 2_801L, 2_901L, 3_001L, 3_101L), mirrorHudExpiries(1_200, vehicle(), gps()))
        assertTrue(mirrorHudExpiries(3_200, vehicle(), gps()).isEmpty())
        assertTrue(mirrorHudExpiries(1_200, vehicle(VehicleTelemetryStatus.IDLE), gps().copy(status = TelemetryStatus.IDLE)).isEmpty())
        assertTrue(mirrorHudExpiries(600, vehicle(), gps()).isEmpty())
    }

    @Test fun chineseUnknownAndKnownOffAreDifferent() {
        val unknown = mirrorHudText(DriveHudFrame(), true)
        val off = mirrorHudText(DriveHudFrame(turn = "off", brakePressed = false), true)
        assertTrue(unknown.controls.contains("转向 —")); assertTrue(unknown.controls.contains("制动 —"))
        assertTrue(off.controls.contains("转向灯关")); assertTrue(off.controls.contains("制动松开"))
    }
}
