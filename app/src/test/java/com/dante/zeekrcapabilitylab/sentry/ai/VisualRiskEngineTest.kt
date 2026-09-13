package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.SentryTriggerType
import org.junit.Assert.*
import org.junit.Test

class VisualRiskEngineTest {
    @Test fun distantPasserbyNeverCreatesAnEvent() {
        val engine = AiFixtures.engine()
        val results = (0..120).map { index ->
            val x = (index % 20) * 0.04
            val frame = AiFixtures.frame(index * 500_000L, listOf(AiFixtures.person(DetectionBox(x, 0.1, x + 0.1, 0.3))))
            engine.process(frame, frame.monotonicUs)
        }
        assertTrue(results.all { it.triggers.isEmpty() && !it.watch })
    }

    @Test fun stationaryVehicleDoesNotTriggerAfterBaselineExpires() {
        val results = AiFixtures.feed(AiFixtures.engine(), 0, 90_000_000, listOf(AiFixtures.person(kind = ObjectKind.VEHICLE)))
        assertTrue(results.all { it.triggers.isEmpty() })
        assertTrue(results.any { it.tracks.any { track -> track.baselineDiscounted } })
        assertFalse(results.last().tracks.single().baselineDiscounted)
    }

    @Test fun stationaryPersonAtArmIsDiscountedButNeverPermanentlyIgnored() {
        val results = AiFixtures.feed(AiFixtures.engine(), 0, 33_000_000)
        assertTrue(results.filterIndexed { index, _ -> index * 500_000L < 30_000_000 }.all { it.triggers.isEmpty() })
        assertEquals(1, results.sumOf { it.triggers.size })
        val trigger = results.flatMap { it.triggers }.single()
        assertEquals(31_000_000L, trigger.monotonicUs)
        assertEquals(SentryTriggerType.VISUAL_RISK, trigger.type)
        assertFalse(trigger.tracks.single().baselineDiscounted)
    }

    @Test fun initialTargetMovementRevokesBaselineImmediately() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 0, 6_000_000)
        val moved = listOf(AiFixtures.person(DetectionBox(0.4, 0.4, 0.6, 0.96)))
        val result = AiFixtures.feed(engine, 6_500_000, 8_000_000, moved)
        assertTrue(result.none { it.tracks.single().baselineDiscounted })
        assertEquals(1, result.sumOf { it.triggers.size })
    }

    @Test fun arrivalDuringLearningDoesNotJoinArmBaseline() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 0, 1_000_000, emptyList())
        val results = AiFixtures.feed(engine, 1_500_000, 8_000_000)
        assertTrue(results.all { result -> result.tracks.none { it.baselineDiscounted } })
        assertEquals(1, results.sumOf { it.triggers.size })
    }

    @Test fun personApproachProducesExplainableSoftCandidateAfterConfirmation() {
        val engine = AiFixtures.engine()
        val boxes = listOf(
            DetectionBox(0.46, 0.42, 0.54, 0.6), DetectionBox(0.45, 0.42, 0.55, 0.65),
            DetectionBox(0.44, 0.42, 0.56, 0.72), DetectionBox(0.43, 0.42, 0.57, 0.8),
            DetectionBox(0.42, 0.42, 0.58, 0.88), DetectionBox(0.41, 0.40, 0.59, 0.92),
        )
        val results = boxes.mapIndexed { index, box ->
            val time = 4_000_000 + index * 500_000L
            engine.process(AiFixtures.frame(time, listOf(AiFixtures.person(box))), time)
        }
        assertTrue(results.dropLast(1).all { it.triggers.isEmpty() })
        val signal = results.last().triggers.single()
        assertEquals(SentryTriggerType.VISUAL_RISK, signal.type)
        assertTrue(signal.tracks.single().reasons.containsAll(listOf("APPROACHING", "CONTINUOUS_CONFIRMATION")))
        assertEquals(1, results.flatMap { it.tracks }.map { it.trackId }.distinct().size)
    }

    @Test fun twoWheelerRequiresDwellOrApproachAndTemporalConfirmation() {
        val objects = listOf(AiFixtures.person(kind = ObjectKind.TWO_WHEELER))
        val result = AiFixtures.feed(AiFixtures.engine(), 4_000_000, 16_000_000, objects)
        val signal = result.flatMap { it.triggers }.single()
        assertEquals(15_000_000L, signal.monotonicUs)
        assertTrue("LONG_DWELL" in signal.tracks.single().reasons)
    }

    @Test fun closingVehicleCanTriggerButConstantSizeTrafficCannot() {
        val engine = AiFixtures.engine()
        val boxes = listOf(
            DetectionBox(0.43, 0.40, 0.57, 0.70), DetectionBox(0.42, 0.39, 0.58, 0.76),
            DetectionBox(0.41, 0.37, 0.59, 0.82), DetectionBox(0.40, 0.35, 0.60, 0.88),
            DetectionBox(0.38, 0.32, 0.62, 0.93), DetectionBox(0.36, 0.29, 0.64, 0.98),
        )
        val result = boxes.mapIndexed { index, box ->
            val time = 4_000_000 + index * 500_000L
            engine.process(AiFixtures.frame(time, listOf(AiFixtures.person(box, ObjectKind.VEHICLE))), time)
        }
        val signal = result.flatMap { it.triggers }.single()
        assertEquals(ObjectKind.VEHICLE, signal.tracks.single().kind)
        assertEquals(SentryTriggerType.VISUAL_RISK, signal.type)
        val constant = AiFixtures.feed(AiFixtures.engine(), 4_000_000, 20_000_000,
            listOf(AiFixtures.person(kind = ObjectKind.VEHICLE)))
        assertTrue(constant.all { it.triggers.isEmpty() })
    }

    @Test fun currentInvalidOutputBreaksConfirmationWithoutChangingTheActiveProfile() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 4_000_000, 8_500_000)
        val invalid = AiFixtures.frame(9_000_000).copy(layoutVersion = "stale-layout")
        assertEquals(RiskFrameStatus.VERSION_MISMATCH, engine.process(invalid, 9_000_000).status)
        assertTrue(AiFixtures.feed(engine, 9_500_000, 13_500_000).all { it.triggers.isEmpty() })
    }

    @Test fun sceneChangesSuppressTriggersAndRestartEvidenceAfterRecovery() {
        val engine = AiFixtures.engine(RiskRules(dwellUs = 100_000))
        AiFixtures.feed(engine, 4_000_000, 4_500_000)
        for (quality in listOf(SceneQuality.EXPOSURE_CHANGE, SceneQuality.CAMERA_SHAKE, SceneQuality.LOW_VISIBILITY, SceneQuality.UNKNOWN)) {
            val time = 5_000_000L + quality.ordinal * 500_000
            val result = engine.process(AiFixtures.frame(time, quality = quality), time)
            assertEquals(setOf(1), result.suppressedLanes)
            assertTrue(result.watch && result.triggers.isEmpty())
        }
        val clear = AiFixtures.feed(engine, 7_500_000, 10_000_000)
        assertTrue(clear.filterIndexed { index, _ -> index < 3 }.all { it.triggers.isEmpty() })
        assertEquals(1, clear.sumOf { it.triggers.size })
    }

    @Test fun missingObservationCannotAccumulateDwellAcrossAnOcclusion() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 4_000_000, 7_500_000)
        engine.process(AiFixtures.frame(8_000_000, emptyList()), 8_000_000)
        val result = AiFixtures.feed(engine, 8_500_000, 12_500_000)
        assertTrue(result.all { it.triggers.isEmpty() })
        assertEquals(1, AiFixtures.feed(engine, 13_000_000, 13_500_000).sumOf { it.triggers.size })
    }

    @Test fun longFrameGapRestartsConfirmationAndDwell() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 4_000_000, 8_000_000)
        val afterGap = AiFixtures.feed(engine, 9_500_000, 13_500_000)
        assertTrue(afterGap.all { it.triggers.isEmpty() })
    }

    @Test fun repeatedRiskKeepsOneBoundedCadenceForEventExtension() {
        val signals = AiFixtures.feed(AiFixtures.engine(), 4_000_000, 41_000_000).flatMap { it.triggers }
        assertEquals(listOf(9_000_000L, 19_000_000L, 29_000_000L, 39_000_000L), signals.map { it.monotonicUs })
        assertEquals(1, signals.flatMap { it.tracks }.map { it.trackId }.distinct().size)
    }

    @Test fun confidenceFilteringAndNmsDoNotCountDuplicatesAsConfirmation() {
        val engine = AiFixtures.engine()
        val duplicate = List(30) { AiFixtures.person() } + AiFixtures.person().copy(confidence = 0.1)
        val result = engine.process(AiFixtures.frame(4_000_000, duplicate), 4_000_000)
        assertEquals(1, result.tracks.size)
        assertEquals(30, result.droppedObjects)
        assertTrue(result.triggers.isEmpty())
    }

    @Test fun tracksAreIndependentAcrossLanesAndRetainedStateIsBounded() {
        val profile = AiFixtures.profile(4)
        val engine = AiFixtures.engine(profile = profile)
        val objects = (0..63).map { index ->
            val x = (index % 8) / 8.0
            val y = (index / 8) / 8.0
            AiFixtures.person(DetectionBox(x, y, x + 0.05, y + 0.05))
        }
        var last: RiskFrameResult? = null
        repeat(150) { index ->
            val time = index * 500_000L
            last = engine.process(AiFixtures.frame(time, objects, laneCount = 4), time)
            assertTrue(last!!.retainedTrackCount <= 4 * 24)
        }
        assertEquals(96, last!!.tracks.size)
        assertEquals(96, last!!.tracks.map { it.trackId }.distinct().size)
        assertEquals(setOf(1, 2, 3, 4), last!!.tracks.map { it.lane }.toSet())
    }

    @Test fun staleOrInvalidResultsCannotTriggerOrSurviveDisarm() {
        val engine = AiFixtures.engine()
        val valid = AiFixtures.frame(4_000_000)
        assertEquals(RiskFrameStatus.ACCEPTED, engine.process(valid, 4_000_000).status)
        assertEquals(RiskFrameStatus.STALE_FRAME, engine.process(valid, 4_500_000).status)
        val next = AiFixtures.frame(5_000_000)
        assertEquals(RiskFrameStatus.STALE_SESSION, engine.process(next.copy(transitionGeneration = 2), 5_000_000).status)
        assertEquals(RiskFrameStatus.STALE_RESULT, engine.process(next, 7_000_000).status)
        assertEquals(RiskFrameStatus.STALE_RESULT, engine.process(next, 4_000_000).status)
        assertEquals(RiskFrameStatus.VERSION_MISMATCH, engine.process(next.copy(detectorVersion = "other"), 5_000_000).status)
        assertEquals(RiskFrameStatus.VERSION_MISMATCH, engine.process(next.copy(layoutVersion = "other"), 5_000_000).status)
        assertEquals(RiskFrameStatus.INVALID_INPUT, engine.process(next.copy(lanes = emptyList()), 5_000_000).status)
        engine.disarm()
        val stopped = engine.process(next, 5_000_000)
        assertEquals(RiskFrameStatus.DISARMED, stopped.status)
        assertEquals(0, stopped.retainedTrackCount)
    }

    @Test fun decisionClockRetainsSeparateImageObservationTime() {
        val engine = AiFixtures.engine()
        AiFixtures.feed(engine, 4_000_000, 8_500_000)
        val result = engine.process(AiFixtures.frame(9_000_000), 9_200_000)
        assertEquals(9_200_000L, result.triggers.single().monotonicUs)
        assertEquals(9_000_000L, result.triggers.single().observedAtMonotonicUs)
    }

    @Test fun replayingTheSameFramesIsDeterministicEvenWithReorderedDetections() {
        val objects = listOf(AiFixtures.person(), AiFixtures.person(DetectionBox(0.05, 0.1, 0.15, 0.3)))
        val first = AiFixtures.feed(AiFixtures.engine(), 4_000_000, 40_000_000, objects)
        val second = AiFixtures.feed(AiFixtures.engine(), 4_000_000, 40_000_000, objects.reversed())
        assertEquals(first, second)
    }
}
