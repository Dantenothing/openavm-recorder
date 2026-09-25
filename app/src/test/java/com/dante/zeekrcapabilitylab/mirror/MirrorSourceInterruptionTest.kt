package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.mirror.MirrorDisplayRecoveryBudget.Action

class MirrorSourceInterruptionTest {
    @Test fun liveInputThreadWithNoFramesWaitsWithoutDestroyingTheConsumer() {
        val recovery = MirrorDisplayRecoveryBudget()
        for (age in listOf(null, 8_001L, 30_000L, 180_000L)) {
            assertEquals(Action.WAIT_FOR_SOURCE, recovery.evaluate(20, age, true))
            assertEquals(0, recovery.attempts)
        }
    }

    @Test fun returningSourceGetsTimeToReachTheExistingDisplayBeforeAnyRebuild() {
        val recovery = MirrorDisplayRecoveryBudget()
        recovery.evaluate(20, 30_000, true)
        assertEquals(Action.SOURCE_RETURNED, recovery.evaluate(20, 10, true))
        val freshness = MirrorFrameFreshness()
        freshness.presentationChanged(30_000)
        assertEquals(Action.NONE, recovery.evaluate(20, 15, freshness.outputTimedOut(30_200)))
        freshness.frame(900, 30_300)
        assertTrue(freshness.isFresh(30_301))
        assertEquals(0, recovery.attempts)
    }

    @Test fun independentInputHeartbeatStillDetectsARealHungGlThread() {
        val recovery = MirrorDisplayRecoveryBudget()
        assertEquals(Action.INPUT_THREAD_FAILED, recovery.evaluate(8_001, 30_000, true))
        assertEquals(Action.INPUT_THREAD_FAILED, recovery.evaluate(8_001, 0, false))
        assertEquals(0, recovery.attempts)
    }

    @Test fun freshInputAndDeadDisplayRetainTheTwoAttemptLimit() {
        val recovery = MirrorDisplayRecoveryBudget()
        assertEquals(Action.REBUILD_DISPLAY, recovery.evaluate(10, 10, true))
        assertEquals(Action.REBUILD_DISPLAY, recovery.evaluate(10, 10, true))
        assertEquals(Action.DISPLAY_FAILED, recovery.evaluate(10, 10, true))
    }

    @Test fun sourcePauseDoesNotReplenishAnExhaustedDisplayBudget() {
        val recovery = MirrorDisplayRecoveryBudget()
        repeat(2) { recovery.evaluate(10, 10, true) }
        assertEquals(Action.WAIT_FOR_SOURCE, recovery.evaluate(10, 50_000, true))
        assertEquals(Action.SOURCE_RETURNED, recovery.evaluate(10, 10, false))
        assertEquals(Action.DISPLAY_FAILED, recovery.evaluate(10, 10, true))
        assertEquals(2, recovery.attempts)
    }
}
