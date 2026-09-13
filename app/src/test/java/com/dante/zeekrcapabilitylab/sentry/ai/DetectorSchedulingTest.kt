package com.dante.zeekrcapabilitylab.sentry.ai

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DetectorSchedulingTest {
    private class Frame : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { check(closes.incrementAndGet() == 1) }
    }

    @Test fun replacementAndDisabledQueueReleaseEveryFrameExactlyOnce() {
        val queue = LatestDetectorFrame<Frame>()
        val frames = List(100) { Frame() }
        frames.forEach(queue::offer)
        assertEquals(1, queue.snapshot().waiting)
        assertEquals(99, frames.sumOf { it.closes.get() })
        queue.close()
        queue.close()
        val afterClose = Frame()
        queue.offer(afterClose)
        assertTrue(frames.all { it.closes.get() == 1 })
        assertEquals(1, afterClose.closes.get())
        assertEquals(0, queue.snapshot().waiting)
        assertEquals(0L, queue.snapshot().releaseFailures)
    }

    @Test fun consumerFailureStillReleasesInFlightFrameAndCloseDoesNotDoubleReleaseIt() {
        val queue = LatestDetectorFrame<Frame>()
        val frame = Frame()
        queue.offer(frame)
        assertThrows(IllegalStateException::class.java) { queue.consumeLatest { queue.close(); error("Inference failed") } }
        assertEquals(1, frame.closes.get())
        assertFalse(queue.consumeLatest { error("No frame") })
    }

    @Test fun concurrentOffersAndCloseHaveNoLeakedPendingOwnership() {
        val queue = LatestDetectorFrame<Frame>()
        val frames = List(400) { Frame() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            frames.forEach { frame -> executor.submit { queue.offer(frame) } }
            executor.submit { queue.close() }
        } finally { executor.shutdown() }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        queue.close()
        assertTrue(frames.all { it.closes.get() == 1 })
        assertEquals(400L, queue.snapshot().offered)
        assertEquals(0L, queue.snapshot().releaseFailures)
    }

    @Test fun cleanupFailureIsVisibleWithoutBlockingLaterFrames() {
        val queue = LatestDetectorFrame<AutoCloseable>()
        queue.offer(AutoCloseable { error("Driver close failure") })
        val next = Frame()
        queue.offer(next)
        assertEquals(1L, queue.snapshot().releaseFailures)
        queue.close()
        assertEquals(1, next.closes.get())
    }

    @Test fun stalledInferenceCannotStartASecondWorkerOrAccumulateWaitingFrames() {
        val queue = LatestDetectorFrame<Frame>()
        val first = Frame()
        val replaced = Frame()
        val latest = Frame()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        queue.offer(first)
        val worker = executor.submit<Boolean> { queue.consumeLatest { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.offer(replaced)
            queue.offer(latest)
            assertFalse(queue.consumeLatest { error("Only one inference may run") })
            assertEquals(1, queue.snapshot().inFlight)
            assertEquals(1, queue.snapshot().waiting)
            assertEquals(1, replaced.closes.get())
        } finally { unblock.countDown(); executor.shutdown() }
        assertTrue(worker.get(5, TimeUnit.SECONDS))
        assertTrue(queue.consumeLatest { assertSame(latest, it) })
        assertEquals(0, queue.snapshot().inFlight)
        assertEquals(1, first.closes.get())
        assertEquals(1, latest.closes.get())
        queue.close()
    }

    @Test fun cadenceChangesInferenceRateOnlyAndRejectsStaleTime() {
        val cadence = DetectorCadence()
        assertEquals(2, cadence.targetFps(DetectorActivity.IDLE, DetectorPressure.NORMAL))
        assertEquals(8, cadence.targetFps(DetectorActivity.WATCH, DetectorPressure.NORMAL))
        assertEquals(12, cadence.targetFps(DetectorActivity.EVENT, DetectorPressure.NORMAL))
        assertTrue(cadence.admit(0, DetectorActivity.IDLE, DetectorPressure.NORMAL, true))
        assertFalse(cadence.admit(100_000, DetectorActivity.IDLE, DetectorPressure.NORMAL, true))
        assertTrue(cadence.admit(200_000, DetectorActivity.WATCH, DetectorPressure.NORMAL, true))
        assertFalse(cadence.admit(1_000_000, DetectorActivity.EVENT, DetectorPressure.CRITICAL, true))
        assertFalse(cadence.admit(2_000_000, DetectorActivity.EVENT, DetectorPressure.NORMAL, false))
        assertTrue(cadence.admit(3_000_000, DetectorActivity.EVENT, DetectorPressure.HOT, true))
        assertFalse(cadence.admit(3_500_000, DetectorActivity.EVENT, DetectorPressure.HOT, true))
        assertFalse(cadence.admit(3_000_000, DetectorActivity.EVENT, DetectorPressure.NORMAL, true))
        assertFalse(cadence.admit(4_000_000, DetectorActivity.EVENT, DetectorPressure.WARM, false))
    }
}
