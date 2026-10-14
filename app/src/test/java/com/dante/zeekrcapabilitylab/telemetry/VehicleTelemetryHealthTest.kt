package com.dante.zeekrcapabilitylab.telemetry

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryHealthTest {
    @Test fun reportPreservesTimeoutAndRecoveryWithoutSensorValues() {
        val owner = Any()
        VehicleTelemetryHealth.claim(owner, false)
        VehicleTelemetryHealth.observe(owner, false, VehicleTelemetryCaptureState(VehicleTelemetryStatus.SDK_TIMEOUT))
        VehicleTelemetryHealth.observe(owner, false, VehicleTelemetryCaptureState(VehicleTelemetryStatus.SDK_BUSY))
        VehicleTelemetryHealth.observe(owner, false, VehicleTelemetryCaptureState(VehicleTelemetryStatus.RECORDING,
            lastElapsedMs = 100, speedKmh = 43.123456, gear = "D"))
        val result = VehicleTelemetryHealth.report(150).getValue("recording")
        assertEquals("1", result["timeouts"]); assertEquals("1", result["recoveries"])
        assertEquals("50", result["lastReadAgeMs"])
        assertFalse(result.toString().contains("43.123456"))
    }
    @Test fun oldViewTeardownCannotReplaceNewHudEvidence() {
        val old = Any(); val next = Any()
        VehicleTelemetryHealth.claim(old, true); VehicleTelemetryHealth.claim(next, true)
        VehicleTelemetryHealth.observe(next, true, VehicleTelemetryCaptureState(VehicleTelemetryStatus.RECORDING))
        VehicleTelemetryHealth.observe(old, true, VehicleTelemetryCaptureState(VehicleTelemetryStatus.IDLE))
        assertEquals("RECORDING", VehicleTelemetryHealth.report(0).getValue("preview")["status"])
    }
}
