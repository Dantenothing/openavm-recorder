package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import org.junit.Assert.*
import org.junit.Test

class LabRateControlTest {
    @Test fun explicitSupportedModeTrialCanRoundTripWithoutChangingLegacyProfiles() {
        val trial=obj("profile" to "RATE_CONTROL_SHARED","bitrateBps" to 28_000_000,
            "iFrameIntervalSeconds" to 1,"bitrateMode" to "VBR","avcProfile" to "HIGH")
        val experiment=LabExperiment.parse(trial)
        assertEquals(trial,experiment.json())
        assertFalse(experiment.reference)
        assertFalse(experiment.health)
        assertTrue(experiment.shared)
        assertFalse(experiment.high)
    }
    @Test fun modeAndProfileAreExplicitAndParticipateInIdentity() {
        val baseline=obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "VBR","avcProfile" to "BASELINE")
        val high=obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "VBR","avcProfile" to "HIGH")
        assertNotEquals(payloadHash(LabExperiment.parse(baseline).json()),payloadHash(LabExperiment.parse(high).json()))
        for(p in listOf(obj("profile" to "RATE_CONTROL_SHARED"),
            obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "CQ","avcProfile" to "BASELINE"),
            obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "CBR_FD","avcProfile" to "BASELINE"),
            obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "VBR","avcProfile" to "AUTO"),
            obj("profile" to "TARGET_SHARED","bitrateMode" to "VBR","avcProfile" to "BASELINE"),
            obj("profile" to "REFERENCE_INPUT","bitrateMode" to "VBR"))) {
            assertThrows(IllegalArgumentException::class.java) { LabExperiment.parse(p) }
        }
    }
}
