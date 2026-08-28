package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseAccuracy
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseMeasurementPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeLapseMeasurementPolicyTest {
    @Test
    fun exactMeasuredMultiplierPasses() {
        val result = TimeLapseMeasurementPolicy.measure(60, 300_000, 5_000)

        assertEquals(TimeLapseAccuracy.PASS, result.accuracy)
        assertEquals(60.0, result.measuredMultiplier!!, 0.000_001)
        assertEquals(0.0, result.relativeError!!, 0.000_001)
    }

    @Test
    fun largeMultiplierErrorIsReported() {
        val result = TimeLapseMeasurementPolicy.measure(60, 300_000, 10_000)

        assertEquals(TimeLapseAccuracy.INCORRECT, result.accuracy)
        assertEquals(30.0, result.measuredMultiplier!!, 0.000_001)
    }

    @Test
    fun shortAndUnavailableSamplesAreNotMisclassified() {
        assertEquals(
            TimeLapseAccuracy.INSUFFICIENT_SAMPLE,
            TimeLapseMeasurementPolicy.measure(150, 60_000, 400).accuracy,
        )
        val unavailable = TimeLapseMeasurementPolicy.measure(30, 60_000, null)
        assertEquals(TimeLapseAccuracy.UNAVAILABLE, unavailable.accuracy)
        assertNull(unavailable.measuredMultiplier)
    }
}
