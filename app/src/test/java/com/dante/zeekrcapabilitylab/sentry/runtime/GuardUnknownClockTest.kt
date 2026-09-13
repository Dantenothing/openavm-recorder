package com.dante.zeekrcapabilitylab.sentry.runtime

import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.sentry.ai.*

class GuardUnknownClockTest {
    private val media = 5_000_000L
    private val elapsed = 900_000_000L
    private fun feed(clock: GuardCaptureClock, start: Int = 0, count: Int = 10, pts: Long = media, at: Long = elapsed) {
        repeat(count) { j ->
            val i = start + j
            clock.sensor(pts + i * 100_000, at + i * 100_000)
            clock.encoded(pts + i * 100_000 + 100, at + i * 100_000 + 20_000)
        }
    }

    @Test fun unknownSameCameraFramesUseMatchedVideoTimeWithoutAssumingElapsedEpoch() {
        val clock = GuardCaptureClock(false)
        feed(clock, count = 7)
        assertFalse(clock.calibrated(elapsed + 620_000))
        feed(clock, start = 7, count = 3)
        assertTrue(clock.calibrated(elapsed + 950_000))
        val frame = clock.frameTime(media + 800_000, elapsed + 805_000, elapsed + 950_000)!!
        assertEquals(media + 800_100, frame.framePtsUs)
        assertEquals(media + 900_100, frame.nowPtsUs)
        assertTrue(frame.arrivedUs <= elapsed + 805_000)
        assertTrue(frame.nowPtsUs < elapsed)
    }

    @Test fun imageMustMatchAnEncodedFrameAndHaveFreshCallbackEvidence() {
        val clock = GuardCaptureClock(false); feed(clock)
        assertNotNull(clock.frameTime(media + 800_000, elapsed + 805_000, elapsed + 950_000))
        assertNull(clock.frameTime(media + 850_000, elapsed + 805_000, elapsed + 950_000))
        assertNull(clock.frameTime(media + 800_000, elapsed - 2_000_000, elapsed + 950_000))
        assertNull(clock.frameTime(media + 800_000, elapsed + 1_000_000, elapsed + 950_000))
        assertNull(clock.frameTime(media + 800_000, elapsed + 805_000, elapsed + 3_000_000))
    }

    @Test fun longSleepGapRequiresNewMatchingSamplesEvenIfMediaTimeBarelyAdvanced() {
        val clock = GuardCaptureClock(false); feed(clock)
        assertTrue(clock.calibrated(elapsed + 950_000))
        feed(clock, count = 7, pts = media + 1_000_000, at = elapsed + 363_000_000)
        assertFalse(clock.calibrated(elapsed + 363_650_000))
        feed(clock, start = 7, count = 1, pts = media + 1_000_000, at = elapsed + 363_000_000)
        assertTrue(clock.calibrated(elapsed + 363_750_000))
    }

    @Test fun timestampRestartDoesNotReuseOldCalibration() {
        val clock = GuardCaptureClock(false); feed(clock)
        feed(clock, count = 7, pts = 1_000_000, at = elapsed + 1_000_000)
        assertFalse(clock.calibrated(elapsed + 1_650_000))
        feed(clock, start = 7, count = 1, pts = 1_000_000, at = elapsed + 1_000_000)
        assertTrue(clock.calibrated(elapsed + 1_750_000))
    }

    @Test fun unrelatedTimestampEpochsCannotBeEnabledByReceivingCallbacks() {
        val clock = GuardCaptureClock(false)
        repeat(30) { i ->
            clock.sensor(media + i * 100_000, elapsed + i * 100_000)
            clock.encoded(media + i * 100_000 + 50_000, elapsed + i * 100_000 + 20_000)
        }
        assertFalse(clock.calibrated(elapsed + 2_950_000))
        assertNull(clock.frameTime(media + 2_800_000, elapsed + 2_805_000, elapsed + 2_950_000))
    }

    @Test fun calibrationIsNeverSharedAcrossCaptureInstances() {
        val first = GuardCaptureClock(false); feed(first)
        val next = GuardCaptureClock(false)
        assertTrue(first.calibrated(elapsed + 950_000))
        assertFalse(next.calibrated(elapsed + 950_000))
    }

    @Test fun triggerAdmissionChecksBothMediaTimeAndCurrentFreshnessProof() {
        val clock = GuardCaptureClock(false); feed(clock)
        val frame = clock.frameTime(media + 800_000, elapsed + 805_000, elapsed + 950_000)!!
        assertTrue(clock.acceptsTrigger(frame.nowPtsUs, frame.arrivedUs, frame.generation, elapsed + 970_000))
        assertFalse(clock.acceptsTrigger(elapsed + 950_000, frame.arrivedUs, frame.generation, elapsed + 970_000))
        assertFalse(clock.acceptsTrigger(frame.nowPtsUs, null, frame.generation, elapsed + 970_000))
        assertFalse(clock.acceptsTrigger(frame.nowPtsUs, frame.arrivedUs, frame.generation, elapsed + 2_400_000))
        feed(clock, count = 10, pts = media, at = elapsed + 4_000_000)
        assertFalse(clock.acceptsTrigger(frame.nowPtsUs, elapsed + 4_900_000, frame.generation, elapsed + 4_950_000))
    }

    @Test fun unknownCameraCanProduceRiskEventsInMediaTimeWithFreshEvidence() {
        val clock = GuardCaptureClock(false)
        var engine: VisualRiskEngine? = null
        var triggers = 0
        for (i in 0..180) {
            feed(clock, start = i, count = 1)
            if (i < 10 || i % 5 != 0) continue
            val now = elapsed + i * 100_000 + 25_000
            val frame = clock.frameTime(media + (i - 1) * 100_000, elapsed + (i - 1) * 100_000 + 5_000, now)!!
            if (engine == null) engine = VisualRiskEngine(AiFixtures.stamp, frame.framePtsUs,
                AiFixtures.detector, AiFixtures.profile())
            val input = AiFixtures.frame(frame.framePtsUs,
                if (i < 30) emptyList() else listOf(AiFixtures.person()))
            val result = engine.process(input, frame.nowPtsUs)
            assertEquals(RiskFrameStatus.ACCEPTED, result.status)
            for (signal in result.triggers) {
                assertTrue(signal.monotonicUs < elapsed)
                assertTrue(clock.acceptsTrigger(signal.monotonicUs, frame.arrivedUs, frame.generation, now))
                triggers++
            }
        }
        assertTrue("Risk rules must be able to emit after verified UNKNOWN-clock frames", triggers > 0)
    }
}
