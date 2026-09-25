package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ProbeCutPlanTest {
    @Test fun earlyArmingDoesNotCutTheFirstSampleOrAnEarlyKeyframe() {
        val p=ProbeCutPlan(); p.arm(1,100)
        assertNull(p.sample(0,true)); assertNull(p.sample(80,true))
        assertEquals(ProbeCutPlan.Boundary(1,100,100),p.sample(100,true))
        assertThrows(IllegalStateException::class.java) { p.arm(1,200) }
        p.sample(133,false); assertEquals(66L,p.end(166))
    }
    @Test fun cutWaitsForTargetAndKeyAndDeterminesOldDuration() {
        val p = ProbeCutPlan(); assertNull(p.sample(0, true)); p.arm(1, 100)
        assertNull(p.sample(50, true)); assertNull(p.sample(100, false))
        assertEquals(ProbeCutPlan.Boundary(1, 120, 120), p.sample(120, true))
        assertEquals(0, p.filePts(120)); assertNull(p.sample(160, false)); assertEquals(80, p.end(200))
        assertThrows(IllegalStateException::class.java) { p.arm(2, 300) }
    }
    @Test fun missingPrefixAndUnreachedCutCannotPass() {
        assertThrows(IllegalStateException::class.java) { ProbeCutPlan().sample(100, true) }
        val p = ProbeCutPlan(); p.sample(0, true); p.arm(1, 100)
        assertThrows(IllegalStateException::class.java) { p.arm(1, 100) }
        assertThrows(IllegalStateException::class.java) { p.end(200) }
    }
    @Test fun abortUsesTheActualTailAndDoesNotPretendFutureCutsWereReached() {
        val p=ProbeCutPlan(); p.sample(0,true); p.arm(1,1_000_000); p.sample(33_333,false)
        assertEquals(66_666L,p.end(66_666,requireAllCuts=false))
        assertThrows(IllegalStateException::class.java) { p.sample(66_666,false) }
    }
}
