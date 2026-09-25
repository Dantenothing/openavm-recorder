package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorRateGestureTest {
    @Test fun swipeStepsThroughSupportedRatesAndYieldsOnlyOneRelease() {
        val drag = MirrorRateGesture(15)
        assertEquals(30, drag.move(22f)); assertEquals(60, drag.move(42f))
        assertEquals(60, drag.finish()); assertNull(drag.finish())
    }
    @Test fun reverseDragUsesTheOriginalRate() {
        val drag = MirrorRateGesture(30)
        drag.move(60f); assertEquals(15, drag.move(-22f)); assertEquals(15, drag.finish())
    }
    @Test fun cancellationNeverCommitsTheDraft() {
        val drag = MirrorRateGesture(30); drag.move(100f); drag.cancel()
        assertEquals(30, drag.value); assertNull(drag.finish()); assertEquals(30, drag.move(200f))
    }
    @Test fun tapOrSmallMovementDoesNotRestartRecording() {
        val drag = MirrorRateGesture(30); assertEquals(30, drag.move(19f)); assertNull(drag.finish())
    }
    @Test fun boundsClampToSupportedRates() {
        assertEquals(150, MirrorRateGesture(30).move(1000f))
        assertEquals(2, MirrorRateGesture(30).move(-1000f))
    }
    @Test fun returningToStartingRateDoesNotIssueACommand() {
        val drag = MirrorRateGesture(30); drag.move(100f); drag.move(0f); assertNull(drag.finish())
    }
}
