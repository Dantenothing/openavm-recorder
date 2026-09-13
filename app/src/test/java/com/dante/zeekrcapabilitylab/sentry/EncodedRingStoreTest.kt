package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class EncodedRingStoreTest {
    private fun bytes(value: Int = 1, size: Int = 8) = ByteBuffer.wrap(ByteArray(size) { value.toByte() })
    private fun ring(slots: Int = 32, targetUs: Long = 30, onGop: (EncodedGop) -> Unit = {}): EncodedRingStore =
        EncodedRingStore(EncodedBufferPool(List(slots) { 16 }), targetUs, onGop).also {
            assertTrue(it.setFormat(1280, 5120, bytes(7), null))
        }

    @Test fun poolIncludesCapacityPaddingAndRejectsOversizeWithoutAllocation() {
        val pool = EncodedBufferPool(listOf(16, 16, 64))
        val small = pool.tryAcquire(1)!!
        val large = pool.tryAcquire(33)!!
        assertEquals(80L, pool.liveBytes)
        assertNull(pool.tryAcquire(100))
        val third = pool.tryAcquire(16)!!
        assertEquals(96L, pool.liveBytes)
        assertNull(pool.tryAcquire(1))
        small.close(); large.close(); third.close()
        assertEquals(0L, pool.liveBytes)
        assertEquals(96L, pool.highWaterBytes)
    }

    @Test fun gopsStartWithSyncAndPtsRemainStrictlyOrdered() {
        val ring = ring()
        assertEquals(RingAppendResult.WAITING_FOR_SYNC, ring.append(bytes(), 1, false))
        ring.append(bytes(), 2, true)
        assertEquals(RingAppendResult.INVALID_PTS, ring.append(bytes(), 2, false))
        ring.append(bytes(), 3, false)
        ring.append(bytes(), 4, true)
        val pinned = ring.pinHistory()
        assertEquals(listOf(2L, 3L), pinned.single().samples.map { it.ptsUs })
        assertTrue(pinned.single().samples.first().sync)
        pinned.forEach { it.release() }
        ring.close()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun exhaustedPoolInvalidatesWholeCurrentGopUntilNextSync() {
        val ring = ring(slots = 4)
        ring.append(bytes(), 0, true)
        ring.append(bytes(), 1, false)
        ring.append(bytes(), 2, false)
        assertEquals(RingAppendResult.GOP_DROPPED, ring.append(bytes(), 3, false))
        assertEquals(1L, ring.snapshot().droppedGops)
        assertEquals(16L, ring.pool.liveBytes) // Format only; no broken P-frame chain retained.
        assertEquals(RingAppendResult.WAITING_FOR_SYNC, ring.append(bytes(), 4, false))
        assertEquals(RingAppendResult.ACCEPTED, ring.append(bytes(), 5, true))
        ring.finishProducer()
        val pinned = ring.pinHistory()
        assertEquals(5L, pinned.single().firstPtsUs)
        pinned.forEach { it.release() }
        ring.close()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun writerPinsSurviveEvictionAndAreStillInsideHardCap() {
        val ring = ring(slots = 10, targetUs = 3)
        ring.append(bytes(10), 0, true)
        ring.append(bytes(11), 1, false)
        ring.append(bytes(12), 2, true)
        val pinned = ring.pinHistory()
        repeat(100) { i -> ring.append(bytes(i), (i + 3).toLong(), i % 2 == 0) }
        assertTrue(ring.pool.highWaterBytes <= ring.pool.capacityBytes)
        assertEquals(10.toByte(), pinned.single().samples.first().buffer.view().get())
        ring.close()
        assertTrue(ring.pool.liveBytes > 0) // Pinned event and its immutable CSD remain alive.
        pinned.forEach { it.release() }
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun formatEpochDeepCopyAndTransitionKeepOldWriterFormatAlive() {
        val ring = ring()
        ring.append(bytes(), 0, true)
        ring.append(bytes(), 1, true)
        val old = ring.pinHistory().single()
        val newCsd = bytes(9)
        ring.setFormat(640, 2560, newCsd, bytes(8))
        newCsd.put(0, 0)
        assertEquals(RingAppendResult.WAITING_FOR_SYNC, ring.append(bytes(), 2, false))
        ring.append(bytes(), 3, true)
        ring.finishProducer()
        val new = ring.pinHistory().single()
        assertNotEquals(old.epoch.id, new.epoch.id)
        assertEquals(7.toByte(), old.epoch.csd0().get())
        assertEquals(9.toByte(), new.epoch.csd0().get())
        assertEquals(8.toByte(), new.epoch.csd1()!!.get())
        ring.close(); old.release(); new.release()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun stalledWriterHasBoundedBacklogAndProducerContinues() {
        val queue = BoundedGopQueue(2)
        var rejected = 0
        val ring = ring(slots = 20, targetUs = 3) { if (!queue.offer(it)) rejected++ }
        repeat(100) { i -> ring.append(bytes(), i.toLong(), true) }
        assertEquals(2, queue.size)
        assertTrue(rejected > 0)
        assertTrue(ring.pool.highWaterBytes <= ring.pool.capacityBytes)
        queue.close()
        ring.close()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun simulatedEightHourProducerRetainsOnlyBoundedHistoryAndReleasesEverything() {
        val ring = ring(slots = 600, targetUs = 30_000_000)
        repeat(8 * 60 * 60 * 15) { frame ->
            ring.append(bytes(frame), frame * 1_000_000L / 15, frame % 30 == 0)
        }
        val snapshot = ring.snapshot()
        assertTrue(snapshot.historyUs in 30_000_000..32_000_000)
        assertEquals(0L, snapshot.droppedGops)
        assertTrue(snapshot.highWaterBytes <= snapshot.capacityBytes)
        ring.close()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun mediaClockMapsDifferentEpochsAndRejectsDiscontinuity() {
        val mapper = MediaClockMapper()
        assertNull(mapper.toMediaPtsUs(1_000_000))
        mapper.anchor(100_000_000, 8_000_000, 200_000)
        assertEquals(9_000_000L, mapper.toMediaPtsUs(101_000_000))
        assertTrue(mapper.observe(102_000_000, 10_100_000))
        assertFalse(mapper.observe(110_000_000, 50_000_000))
        assertNull(mapper.toMediaPtsUs(111_000_000))
    }
}
