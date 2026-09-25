package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorInputPumpTest {
    private class Fixture {
        var now = 1_000L
        var textureTimestamp = 0L
        var reads = 0
        var published = 0
        var readFailure = false
        val pump = MirrorInputPump({ now }, {
            reads++
            check(!readFailure) { "INJECTED_INPUT_FAILURE" }
            textureTimestamp
        }, { published++ })
        fun notified(timestamp: Long) { textureTimestamp = timestamp; pump.notification() }
        fun advance(ms: Long) { now += ms; pump.heartbeat() }
    }

    @Test fun aQueuedFrameAfterTheSegmentGapDoesNotNeedAnotherNotification() {
        val f = Fixture()
        f.notified(100)
        f.advance(4_000) // old segment stopped; empty reads cannot invent a frame
        assertEquals(1L, f.pump.frames)
        f.textureTimestamp = 200 // second segment has a buffer, but the notification is lost
        f.advance(33)
        assertEquals(2L, f.pump.frames)
        assertEquals(1L, f.pump.notifications)
        assertEquals(1L, f.pump.polledFrames)
        assertEquals(2, f.published)
    }

    @Test fun unchangedAndInvalidTexturesCannotKeepADeadPreviewAlive() {
        val f = Fixture()
        f.notified(100)
        val at = f.pump.lastFrameAt
        repeat(400) { f.advance(33) }
        f.textureTimestamp = 0
        f.advance(33)
        assertTrue(f.pump.pollAttempts > 0)
        assertEquals(1L, f.pump.frames)
        assertEquals(at, f.pump.lastFrameAt)
        assertEquals(0L, f.pump.polledFrames)
    }

    @Test fun healthyThirtyFpsCallbacksNeedNoFallbackReads() {
        val f = Fixture()
        repeat(90) { f.now += 33; f.notified(it + 1L); f.pump.heartbeat() }
        assertEquals(90L, f.pump.frames)
        assertEquals(90, f.reads)
        assertEquals(0L, f.pump.pollAttempts)
    }

    @Test fun fallbackIsRateLimitedAndReturnsToNormalWhenNotificationsResume() {
        val f = Fixture()
        f.notified(100)
        f.advance(249)
        assertEquals(0L, f.pump.pollAttempts)
        f.advance(1)
        assertEquals(1L, f.pump.pollAttempts)
        repeat(1_000) { f.pump.heartbeat() }
        assertEquals(1L, f.pump.pollAttempts)
        assertEquals(33L, f.pump.heartbeatDelayMs())
        f.advance(32)
        assertEquals(1L, f.pump.pollAttempts)
        f.advance(1)
        assertEquals(2L, f.pump.pollAttempts)
        f.notified(300)
        assertEquals(250L, f.pump.heartbeatDelayMs())
        f.advance(33)
        assertEquals(2L, f.pump.pollAttempts)
    }

    @Test fun screenOffSuspendsPollingAndStopRejectsLateWork() {
        val f = Fixture()
        f.notified(100)
        f.pump.pollingEnabled = false
        f.advance(4_000)
        assertEquals(1, f.reads)
        f.pump.pollingEnabled = true
        f.textureTimestamp = 200
        f.pump.heartbeat()
        assertEquals(2L, f.pump.frames)
        f.pump.stop()
        f.notified(300)
        f.advance(4_000)
        assertEquals(2L, f.pump.frames)
        assertEquals(2, f.reads)
    }

    @Test fun inputFailurePropagatesInsteadOfBeingReportedAsRecovery() {
        val f = Fixture()
        f.notified(100)
        f.readFailure = true
        assertThrows(IllegalStateException::class.java) { f.advance(300) }
        assertEquals(1L, f.pump.frames)
        assertEquals(0L, f.pump.polledFrames)
    }

    @Test fun clockRollbackCannotScheduleAnInputRead() {
        val f = Fixture()
        f.notified(100)
        f.now = 900
        f.pump.heartbeat()
        assertEquals(1, f.reads)
        f.now = 1_250
        f.pump.heartbeat()
        assertEquals(2, f.reads)
    }
}
