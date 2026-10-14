package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class ContinuousSampleAdmissionTest {
    @Test fun lastItemCannotAdmitOnlyTheCutWithoutItsKeyframe() {
        val queue = BoundedEncodedQueue<String>(100, 2)
        assertTrue(queue.offer(4) { "old" })
        assertFalse(offerContinuousSample(queue, 6, { "cut" }) { "keyframe" })
        assertEquals(4L to 1, queue.snapshot())
        val old = queue.poll()!!; queue.release(old)
        assertNull(queue.peek())
    }

    @Test fun insufficientByteCreditCannotAdmitAnEmptyNextFile() {
        val queue = BoundedEncodedQueue<String>(10, 9)
        assertTrue(queue.offer(8) { "old" })
        assertFalse(offerContinuousSample(queue, 3, { "cut" }) { error("No allocation before admission") })
        assertEquals(8L to 1, queue.snapshot())
    }

    @Test fun allocationFailureLeavesNeitherItemInTheQueue() {
        val queue = BoundedEncodedQueue<String>(10, 9)
        assertThrows(IllegalStateException::class.java) {
            offerContinuousSample(queue, 3, { "cut" }) { error("copy failed") }
        }
        assertEquals(0L to 0, queue.snapshot())
    }

    @Test fun admittedPairPreservesOrderAndAllCreditsUntilBothReleased() {
        val queue = BoundedEncodedQueue<String>(6, 2)
        assertTrue(offerContinuousSample(queue, 6, { "cut" }) { "keyframe" })
        assertEquals(6L to 2, queue.snapshot())
        val cut = queue.poll()!!; val frame = queue.poll()!!
        assertEquals("cut", cut.value); assertEquals("keyframe", frame.value)
        assertFalse(queue.offer(1) { "overflow" })
        queue.release(cut); queue.release(frame)
        assertEquals(0L to 0, queue.snapshot())
    }
}
