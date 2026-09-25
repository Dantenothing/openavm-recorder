package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorGlFramePoolTest {
    @Test fun hungDisplayKeepsItsTextureWhileInputCanStillDrainThousandsOfFrames() {
        val pool = MirrorGlFramePool()
        val first = pool.reserveWrite()!!; pool.publish(first, 10)
        val blocked = pool.acquireLatest(0)!!
        repeat(10_000) { frame ->
            val write = pool.reserveWrite()!!
            assertNotEquals(blocked.index, write.index)
            pool.publish(write, frame + 11L)
        }
        val latest = pool.acquireLatest(blocked.serial)!!
        assertEquals(10_010L, latest.timestamp)
        assertEquals(2, pool.inUse())
        pool.releaseRead(latest, true); pool.releaseRead(blocked, true)
        assertEquals(0, pool.inUse())
    }
    @Test fun unsignalledProducerOrReaderFenceCannotForceReuseOrUnboundedAllocation() {
        val pool = MirrorGlFramePool()
        val writes = (0..2).map { pool.reserveWrite()!! }
        assertNull(pool.reserveWrite())
        writes.forEachIndexed { index, write -> pool.publish(write, index + 1L) }
        val excluded = mutableSetOf<Int>()
        repeat(3) {
            val write = pool.reserveWrite(excluded)!!
            excluded += write.index; pool.cancelWrite(write)
        }
        assertNull(pool.reserveWrite(excluded))
        val newest = pool.acquireLatest(0)!!
        pool.releaseRead(newest, false) // GPU fence not ready; the same newest frame remains available.
        assertEquals(newest, pool.acquireLatest(0))
    }
    @Test fun twoRetiringDisplaysLeaveBoundedInputCapacity() {
        val pool = MirrorGlFramePool()
        pool.publish(pool.reserveWrite()!!, 1)
        val old = pool.acquireLatest(0)!!
        pool.publish(pool.reserveWrite()!!, 2)
        val replacement = pool.acquireLatest(old.serial)!!
        repeat(200) { pool.publish(pool.reserveWrite()!!, it + 3L) }
        assertEquals(2, pool.inUse())
        pool.releaseRead(old, true); pool.releaseRead(replacement, true)
    }
    @Test(expected = IllegalStateException::class) fun aLateWriterCannotPublishIntoANewerLease() {
        val pool = MirrorGlFramePool(); val old = pool.reserveWrite()!!
        pool.cancelWrite(old); pool.reserveWrite()
        pool.publish(old, 1)
    }
    @Test fun stalledInputNeverAuthorizesDisplayRetriesAndBudgetNeverResetsOnWindowMoves() {
        val budget = MirrorDisplayRecoveryBudget()
        assertFalse(budget.reserve(null)); assertFalse(budget.reserve(2_001)); assertFalse(budget.reserve(-1))
        assertEquals(0, budget.attempts)
        assertTrue(budget.reserve(20)); assertTrue(budget.reserve(0))
        assertFalse(budget.reserve(0)); assertEquals(2, budget.attempts)
    }
}
