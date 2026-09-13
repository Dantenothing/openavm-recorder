package com.dante.zeekrcapabilitylab.sentry.ai

import org.junit.Assert.*
import org.junit.Test

class LiveDetectorGateTest {
    @Test fun offlineCandidateCannotInstantiateLiveDetectorByDefault() {
        var creations = 0
        val blocked = LiveDetectorGate.createIfReady(LiveDetectorProof(AiFixtures.stamp), AiFixtures.profile(), AiFixtures.detector) { ++creations }
        assertNull(blocked)
        assertEquals(0, creations)
        assertEquals(LiveDetectorBlock.entries.toSet(), LiveDetectorGate.blocks(LiveDetectorProof(AiFixtures.stamp), AiFixtures.profile(), AiFixtures.detector))
    }
    @Test fun allQualificationsAreRequiredAndOldTransitionOrModelCannotBeReused() {
        val ready = LiveDetectorProof(AiFixtures.stamp, true, true, AiFixtures.stamp, AiFixtures.stamp, AiFixtures.detector, true)
        val profile = AiFixtures.profile().copy(vehicleCalibrated = true)
        assertEquals("created", LiveDetectorGate.createIfReady(ready, profile, AiFixtures.detector) { "created" })
        val missing = listOf(ready.copy(autoFeatureEnabled = false), ready.copy(armed = false),
            ready.copy(qualifiedEncoderStamp = AiFixtures.stamp.copy(transition = 2)),
            ready.copy(qualifiedDetectorOutputStamp = null), ready.copy(acceptedModelVersion = "old-model"), ready.copy(clockCalibrated = false))
        missing.forEach { assertNull(LiveDetectorGate.createIfReady(it, profile, AiFixtures.detector) { error("Must stay cold") }) }
        assertNull(LiveDetectorGate.createIfReady(ready, AiFixtures.profile(), AiFixtures.detector) { error("Uncalibrated profile") })
    }
}
