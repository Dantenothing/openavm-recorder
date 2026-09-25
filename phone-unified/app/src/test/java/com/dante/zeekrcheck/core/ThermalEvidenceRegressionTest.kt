package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ThermalEvidenceRegressionTest {
    @Test fun aFreshTemperatureCannotAuthenticateAnUnstampedAirConditionerFlag() {
        val now = Instant.parse("2026-09-21T10:00:00Z")
        val oldCache = VehicleOverview(acOn = true, blowerActive = true, readings = mapOf(
            "cabin_temperature" to OverviewReading("29.0 °C", now.toEpochMilli(), now.toEpochMilli())))
        assertEquals("运行状态待核实", VehicleOverview.decode(oldCache.encode()).climateLabel(now))
    }
}
