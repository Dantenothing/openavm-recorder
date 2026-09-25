package com.dante.zeekrcapabilitylab.preflight

import com.dante.zeekrcapabilitylab.preflight.continuous.CameraWindowTiming
import org.junit.Assert.*
import org.junit.Test

class CameraWindowTimingTest {
    private val healthy=CameraWindowTiming(29.9,29.8,45,47,12,14)
    @Test fun goodHistoricalFpsCannotHideAFrozenFinalWindow() {
        assertTrue(healthy.healthy(true))
        assertFalse(healthy.copy(updateAgeMs=3_000).healthy(true))
        assertFalse(healthy.copy(drawAgeMs=3_000).healthy(true))
        assertFalse(healthy.copy(updateAgeMs=null).healthy(true))
    }
    @Test fun intentionallyRetiredWindowUsesItsActiveLifetimeEvidence() {
        assertTrue(healthy.copy(drawAgeMs=3_000,updateAgeMs=3_000).healthy(false))
        assertFalse(healthy.copy(maxUpdateGapMs=550).healthy(false))
    }
    @Test fun FreshButLowRateOrAnEarlierBoundaryPauseStillFails() {
        assertFalse(healthy.copy(updateFps=12.0).healthy(true))
        assertFalse(healthy.copy(maxDrawGapMs=300).healthy(true))
        assertFalse(healthy.copy(updateAgeMs=-1).healthy(true))
    }
}
