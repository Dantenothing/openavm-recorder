package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CanaryBufferCapacityTest {
    @Test fun completedHistoryExcludesActiveGopAndCapacityEvictionsRemainVisible() {
        val pool = EncodedBufferPool(List(8) { 16 })
        val ring = EncodedRingStore(pool, 30)
        assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(1)), null))
        repeat(4) { frame -> ring.append(ByteBuffer.wrap(byteArrayOf(2)), frame.toLong(), frame % 2 == 0) }
        assertEquals(3L, ring.snapshot().historyUs)
        assertEquals(1L, ring.snapshot().completedHistoryUs)
        repeat(20) { n -> val frame = n + 4; ring.append(ByteBuffer.wrap(byteArrayOf(2)), frame.toLong(), frame % 2 == 0) }
        assertTrue(ring.snapshot().budgetEvictedGops > 0)
        assertEquals(0L, ring.snapshot().droppedGops)
        assertTrue(ring.snapshot().completedHistoryUs <= ring.snapshot().historyUs)
        ring.close()
        assertEquals(0L, pool.payloadLiveBytes)
    }

    @Test fun randomAllocationAndReleaseNeverOverlapLivePayloadOrLoseCapacity() {
        val pool = EncodedBufferPool.paged(64 * 1024, 256)
        val random = java.util.Random(19)
        val live = mutableListOf<Pair<EncodedBuffer, Byte>>()
        repeat(5000) { step ->
            if (live.isNotEmpty() && random.nextInt(3) == 0) live.removeAt(random.nextInt(live.size)).first.close()
            else pool.tryAcquire(1 + random.nextInt(8000))?.let { buffer ->
                val marker = step.toByte()
                buffer.copyFrom(ByteBuffer.wrap(ByteArray(buffer.size) { marker }))
                live += buffer to marker
            }
            if (step % 20 == 0) for ((buffer, marker) in live) {
                val view = buffer.view()
                while (view.hasRemaining()) assertEquals(marker, view.get())
            }
            assertEquals(live.sumOf { it.first.chargedBytes }, pool.liveBytes)
            assertEquals(live.sumOf { it.first.size.toLong() }, pool.payloadLiveBytes)
        }
        live.forEach { it.first.close() }
        pool.tryAcquire(64 * 1024)!!.close()
        assertEquals(0L, pool.liveBytes)
    }

    @Test fun arenaCoalescesOutOfOrderReleasesWithoutMovingPinnedSamples() {
        val pool = EncodedBufferPool.paged(128, 16)
        val first = pool.tryAcquire(17)!!
        val pinned = pool.tryAcquire(18)!!
        val third = pool.tryAcquire(20)!!
        val fourth = pool.tryAcquire(32)!!
        pinned.copyFrom(ByteBuffer.wrap(ByteArray(18) { 71 }))
        assertNull(pool.tryAcquire(1))
        third.close()
        fourth.close()
        val joined = pool.tryAcquire(63)!!
        joined.copyFrom(ByteBuffer.wrap(ByteArray(63) { 22 }))
        assertTrue(pinned.view().let { view -> (0 until view.remaining()).all { view.get(it) == 71.toByte() } })
        first.close()
        joined.close()
        pinned.close()
        assertEquals(0L, pool.liveBytes)
        assertEquals(0L, pool.payloadLiveBytes)
        pool.tryAcquire(128)!!.close()
        assertEquals(128L, pool.highWaterBytes)
    }

    @Test fun alignmentWasteAndDoubleReleaseAreAccountedWithoutReusingLiveLeases() {
        val pool = EncodedBufferPool.paged(128, 16)
        val lease = pool.tryAcquire(17)!!
        assertEquals(32L, lease.chargedBytes)
        assertEquals(17L, pool.payloadLiveBytes)
        assertNull(pool.tryAcquire(Int.MAX_VALUE))
        lease.close()
        val reused = pool.tryAcquire(17)!!
        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertEquals(32L, pool.liveBytes)
        reused.close()
        assertEquals(0L, pool.payloadLiveBytes)
    }

    @Test fun mixedFrameSizesDoNotExhaustAnArtificialLargeFrameSlotCount() {
        val pool = EncodedBufferPool.paged(64 * 1024 * 1024)
        val ring = EncodedRingStore(pool)
        val small = ByteBuffer.allocate(20_000)
        val large = ByteBuffer.allocate(80_000)
        try {
            assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(1)), null))
            repeat(900) { frame -> ring.append(if (frame % 8 == 0) large else small, frame * 1_000_000L / 15, frame % 30 == 0) }
            assertTrue(ring.snapshot().historyUs >= 30_000_000)
            assertEquals(0L, ring.snapshot().droppedGops)
            assertTrue(pool.highWaterBytes < 24L * 1024 * 1024)
        } finally { ring.close() }
    }

    @Test fun pinnedWriterAndSharedFormatRemainReadableAfterArenaWrapAndProducerClose() {
        val pool = EncodedBufferPool.paged(4096, 16)
        val queue = BoundedGopQueue(2)
        val ring = EncodedRingStore(pool, 100) { queue.offer(it) }
        assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(42)), null))
        repeat(500) { frame -> ring.append(ByteBuffer.wrap(ByteArray(71) { frame.toByte() }), frame.toLong(), frame % 4 == 0) }
        ring.close()
        val first = queue.poll()!!
        assertEquals(42.toByte(), first.epoch.csd0().get())
        assertTrue(first.samples.first().buffer.view().let { view -> (0 until 71).all { view.get(it) == 0.toByte() } })
        first.release()
        queue.close()
        assertEquals(0L, pool.liveBytes)
        assertEquals(0L, pool.payloadLiveBytes)
        assertTrue(pool.highWaterBytes <= pool.capacityBytes)
    }

    @Test fun canaryRetainsThirtySecondsAtFourMbpsAndThirtyFpsWithinSame64MiB() {
        val pool = EncodedBufferPool.paged(64 * 1024 * 1024)
        val ring = EncodedRingStore(pool)
        val small = ByteBuffer.allocate(16_000)
        val sync = ByteBuffer.allocate(100_000)
        try {
            assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), null))
            repeat(60 * 30) { frame ->
                assertEquals(RingAppendResult.ACCEPTED, ring.append(if (frame % 60 == 0) sync else small,
                    frame * 1_000_000L / 30, frame % 60 == 0))
            }
            val snapshot = ring.snapshot()
            assertEquals(64L * 1024 * 1024, snapshot.capacityBytes)
            assertEquals(0L, snapshot.droppedGops)
            assertEquals(0L, snapshot.budgetEvictedGops)
            assertTrue("4.176 Mbps should fit 30 s, observed ${snapshot.historyUs / 1_000_000.0} s", snapshot.historyUs >= 30_000_000)
        } finally { ring.close() }
        assertEquals(0L, pool.liveBytes)
    }
}
