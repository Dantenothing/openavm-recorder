package io.github.dantenothing.drivemap

import org.junit.Assert.*
import org.junit.Test

class RelativePedalScaleTest {
    private fun raw(value: Double, status: String = "PROVISIONAL_RAW") = DriveHudPedal(value, status)
    @Test fun eachPedalLearnsItsOwnMaximumAcrossRecordings() {
        val a = RelativePedalScale().observe(DriveHudFrame(acceleratorDepth = raw(20.0), brakeDepth = raw(40.0)))
        val b = a.observe(DriveHudFrame(acceleratorDepth = raw(10.0), brakeDepth = raw(55.0)))
        assertEquals(20.0, b.throttlePeak, 0.0); assertEquals(55.0, b.brakePeak, 0.0)
        assertEquals(.5f, b.throttleFraction(raw(10.0))!!, .0001f)
    }
    @Test fun staleInvalidNegativeAndNonFiniteValuesNeverPoisonHistory() {
        for (p in listOf(raw(9_000.0, "STALE"), raw(9_000.0, "OK"), raw(-1.0), raw(Double.NaN), raw(Double.POSITIVE_INFINITY), raw(1_000_001.0))) {
            assertEquals(RelativePedalScale(10.0, 20.0), RelativePedalScale(10.0, 20.0).observe(DriveHudFrame(acceleratorDepth = p, brakeDepth = p)))
            assertNull(RelativePedalScale(10.0, 20.0).throttleFraction(p))
        }
    }
    @Test fun noPositiveObservationDoesNotInventAFullScale() {
        val scale = RelativePedalScale().observe(DriveHudFrame(acceleratorDepth = raw(0.0)))
        assertEquals(0f, scale.throttleFraction(raw(0.0))!!, 0f)
        assertNull(scale.throttleFraction(raw(5.0)))
    }
    @Test fun newPeakRescalesWithoutChangingSourceValues() {
        val pedal = raw(25.0)
        val initial = RelativePedalScale(25.0)
        assertEquals(1f, initial.throttleFraction(pedal)!!, 0f)
        val learned = initial.observe(DriveHudFrame(acceleratorDepth = raw(50.0)))
        assertEquals(.5f, learned.throttleFraction(pedal)!!, 0f)
        assertEquals("PROVISIONAL_RAW", pedal.status); assertEquals(25.0, pedal.raw!!, 0.0)
    }
}
