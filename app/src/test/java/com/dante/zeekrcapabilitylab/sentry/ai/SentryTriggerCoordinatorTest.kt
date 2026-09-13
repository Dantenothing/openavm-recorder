package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.*
import org.junit.Assert.*
import org.junit.Test

class SentryTriggerCoordinatorTest {
    private fun coordinator(uncertaintyUs: Long = 20_000, trusted: Set<String> = emptySet()) = SentryTriggerCoordinator(
        AiFixtures.stamp, AiFixtures.detector, "rules", trustedImpactProviders = trusted).also {
        assertTrue(it.anchorClock(AiFixtures.stamp, 50_000_000, 5_000_000, uncertaintyUs))
    }
    private fun manual(time: Long) = SentryTriggerSignal(7, 3, "manual", SentryTriggerType.MANUAL, time, 123, 1.0, emptySet())

    @Test fun missingOrUncertainMappingCannotStartAnEvent() {
        val unanchored = SentryTriggerCoordinator(AiFixtures.stamp, AiFixtures.detector, "rules")
        assertEquals(TriggerDecisionCode.CLOCK_UNAVAILABLE, unanchored.receive(manual(50_000_000), 50_000_000).code)
        val uncertain = coordinator(2_000_000)
        assertEquals(TriggerDecisionCode.CLOCK_UNCERTAIN, uncertain.receive(manual(50_000_000), 50_000_000).code)
        assertNull(uncertain.event)
        assertFalse(uncertain.advance(AiFixtures.stamp, 60_000_000, false))
    }

    @Test fun mappedMediaEpochIsUsedInsteadOfRawMonotonicEpoch() {
        val coordinator = coordinator()
        val result = coordinator.receive(manual(51_000_000), 51_100_000)
        assertEquals(TriggerDecisionCode.ACCEPTED, result.code)
        assertEquals(6_000_000L, result.mediaPtsUs)
        assertEquals(6_000_000L, coordinator.event!!.firstTriggerPtsUs)
        assertEquals(66_000_000L, coordinator.event!!.postUntilPtsUs)
        assertEquals(20_000L, result.clockUncertaintyUs)
    }

    @Test fun duplicateIsIgnoredButLaterRiskExtendsSameLogicalEvent() {
        val coordinator = coordinator()
        coordinator.receive(manual(50_000_000), 50_000_000)
        val id = coordinator.event!!.id
        val post = coordinator.event!!.postUntilPtsUs
        assertEquals(TriggerDecisionCode.DUPLICATE, coordinator.receive(manual(50_500_000), 50_500_000).code)
        assertEquals(post, coordinator.event!!.postUntilPtsUs)
        assertEquals(TriggerDecisionCode.ACCEPTED, coordinator.receive(manual(60_000_000), 60_000_000).code)
        assertEquals(id, coordinator.event!!.id)
        assertEquals(post + 10_000_000, coordinator.event!!.postUntilPtsUs)
    }

    @Test fun visionCannotClaimTrustedPhysicalSeverity() {
        val coordinator = coordinator(trusted = setOf("impact-sensor"))
        val forged = manual(50_000_000).copy(providerId = "visual-risk", type = SentryTriggerType.TRUSTED_PHYSICAL)
        assertEquals(TriggerDecisionCode.INVALID_TRIGGER, coordinator.receive(forged, 50_000_000).code)
        assertNull(coordinator.event)
        val shake = forged.copy(providerId = "visual-shake", type = SentryTriggerType.SUSPECTED_IMPACT, lanes = setOf(1))
        assertEquals(SentrySeverity.SOFT, coordinator.receive(shake, 50_000_000).severity)
        val impact = forged.copy(providerId = "impact-sensor", monotonicUs = 51_000_000)
        assertEquals(SentrySeverity.HARD, coordinator.receive(impact, 51_000_000).severity)
        assertEquals(126_000_000L, coordinator.event!!.postUntilPtsUs)
        val noTrust = coordinator()
        assertEquals(TriggerDecisionCode.INVALID_TRIGGER, noTrust.receive(impact, 51_000_000).code)
    }

    @Test fun clockDiscontinuityAndStaleSessionsDoNotAdvanceEvents() {
        val coordinator = coordinator()
        assertEquals(TriggerDecisionCode.STALE_SESSION, coordinator.receive(manual(50_000_000).copy(runGeneration = 6), 50_000_000).code)
        assertEquals(TriggerDecisionCode.STALE_TIME, coordinator.receive(manual(50_000_000), 53_000_000).code)
        assertEquals(TriggerDecisionCode.STALE_TIME, coordinator.receive(manual(54_000_000), 53_000_000).code)
        assertTrue(coordinator.observeClock(AiFixtures.stamp, 51_000_000, 6_000_000))
        assertFalse(coordinator.observeClock(AiFixtures.stamp, 52_000_000, 20_000_000))
        assertEquals(TriggerDecisionCode.CLOCK_UNAVAILABLE, coordinator.receive(manual(53_000_000), 53_000_000).code)
        assertNull(coordinator.event)
        coordinator.disarm()
        assertEquals(TriggerDecisionCode.DISARMED, coordinator.receive(manual(54_000_000), 54_000_000).code)
    }

    @Test fun cappedEventRequiresFinalizationAndCannotSpawnConcurrentWriter() {
        val coordinator = coordinator()
        coordinator.receive(manual(50_000_000), 50_000_000)
        for (time in 60_000_000L..340_000_000L step 10_000_000L) coordinator.receive(manual(time), time)
        val id = coordinator.event!!.id
        val capped = coordinator.receive(manual(350_000_000), 350_000_000)
        assertEquals(TriggerDecisionCode.EVENT_FINALIZING, capped.code)
        assertEquals(SentryEventPhase.FINALIZING, coordinator.phase)
        assertTrue(coordinator.event!!.continuationRequired)
        assertFalse(coordinator.finalized(AiFixtures.stamp, id + 1, SentryPersistenceState.COMPLETE))
        assertTrue(coordinator.finalized(AiFixtures.stamp, id, SentryPersistenceState.PARTIAL))
        assertEquals(TriggerDecisionCode.ACCEPTED, coordinator.receive(manual(351_000_000), 351_000_000).code)
        assertNotEquals(id, coordinator.event!!.id)
    }

    @Test fun quietRiskFinalizesAfterPostRollAndMergeWindow() {
        val coordinator = coordinator()
        coordinator.receive(manual(50_000_000), 50_000_000)
        assertTrue(coordinator.advance(AiFixtures.stamp, 51_000_000, false))
        assertEquals(SentryEventPhase.HOLD, coordinator.phase)
        coordinator.advance(AiFixtures.stamp, 109_000_000, false)
        assertEquals(SentryEventPhase.HOLD, coordinator.phase)
        coordinator.advance(AiFixtures.stamp, 110_000_000, false)
        assertEquals(SentryEventPhase.FINALIZING, coordinator.phase)
    }

    @Test fun actualRiskDecisionMapsBothCaptureAndDecisionTimeAndKeepsVersions() {
        val engine = AiFixtures.engine()
        val coordinator = SentryTriggerCoordinator(AiFixtures.stamp, AiFixtures.detector, engine.ruleConfigVersion)
        coordinator.anchorClock(AiFixtures.stamp, 4_000_000, 100_000_000, 0)
        AiFixtures.feed(engine, 4_000_000, 8_500_000)
        val signal = engine.process(AiFixtures.frame(9_000_000), 9_200_000).triggers.single()
        assertTrue(coordinator.advance(AiFixtures.stamp, 9_100_000, true))
        val result = coordinator.receive(signal, 9_200_000)
        assertEquals(TriggerDecisionCode.ACCEPTED, result.code)
        assertEquals(105_200_000L, result.mediaPtsUs)
        assertEquals(105_000_000L, result.observationMediaPtsUs)
        assertEquals(setOf(1), coordinator.involvedLanes)
        assertEquals(setOf(SentryTriggerType.VISUAL_RISK), coordinator.triggerTypes)
        assertEquals(TriggerDecisionCode.INVALID_TRIGGER, coordinator.receive(signal.copy(ruleConfigVersion = "wrong"), 9_200_000).code)
    }
}
