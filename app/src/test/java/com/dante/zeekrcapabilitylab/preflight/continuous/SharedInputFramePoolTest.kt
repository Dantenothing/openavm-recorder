package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.preflight.continuous.SharedInputFramePool.Reader

class SharedInputFramePoolTest {
    private fun publish(p: SharedInputFramePool, frame: Int) {
        p.publish(checkNotNull(p.reserve()), frame, (frame + 1) * 1000L)
    }
    @Test fun slowDisplaysHoldOneSlotEachWithoutStarvingOrderedEncoder() {
        val p = SharedInputFramePool()
        publish(p, 0)
        val a = checkNotNull(p.acquire(Reader.DISPLAY_A))
        p.release(checkNotNull(p.acquire(Reader.ENCODER)), true)
        publish(p, 1)
        val b = checkNotNull(p.acquire(Reader.DISPLAY_B))
        p.release(checkNotNull(p.acquire(Reader.ENCODER)), true)
        for (id in 2..100) {
            publish(p, id)
            assertNull(p.acquire(Reader.DISPLAY_A)); assertNull(p.acquire(Reader.DISPLAY_B))
            val encode = checkNotNull(p.acquire(Reader.ENCODER)); assertEquals(id, encode.frame)
            assertNotEquals(a.slot, encode.slot); assertNotEquals(b.slot, encode.slot)
            p.release(encode, true)
        }
        assertEquals(2, p.outstanding())
        p.release(a, true); p.release(b, true); assertEquals(0, p.outstanding())
    }
    @Test fun encoderBackpressureCannotOverwritePendingFrames() {
        val p = SharedInputFramePool(); repeat(4) { publish(p, it) }
        assertNull(p.reserve())
        repeat(4) { expected ->
            val r = checkNotNull(p.acquire(Reader.ENCODER)); assertEquals(expected, r.frame)
            assertNull(p.acquire(Reader.ENCODER))
            p.release(r, true)
        }
        assertNotNull(p.reserve())
    }
    @Test fun detachCannotReclaimUnsignalledGpuWorkOrAcceptStaleRelease() {
        val p = SharedInputFramePool(); publish(p, 0)
        val read = checkNotNull(p.acquire(Reader.DISPLAY_A)); p.detach(Reader.DISPLAY_A)
        assertThrows(IllegalStateException::class.java) { p.release(read, false) }
        assertEquals(1, p.outstanding())
        assertThrows(IllegalStateException::class.java) { p.attach(Reader.DISPLAY_A) }
        p.release(read, true)
        assertThrows(IllegalStateException::class.java) { p.release(read, true) }
        p.attach(Reader.DISPLAY_A)
        p.stopPublishing(); assertNull(p.reserve())
        assertThrows(IllegalStateException::class.java) { p.attach(Reader.DISPLAY_B) }
    }
    @Test fun displayUsesLatestButEncoderNeverSkipsPendingSourceFrames() {
        val p = SharedInputFramePool(); repeat(3) { publish(p, it) }
        val display = checkNotNull(p.acquire(Reader.DISPLAY_A)); assertEquals(2, display.frame)
        val encode = checkNotNull(p.acquire(Reader.ENCODER)); assertEquals(0, encode.frame)
        p.release(encode, true); p.release(display, true)
        assertNull(p.acquire(Reader.DISPLAY_A, 2))
    }
}
