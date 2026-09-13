package com.dante.zeekrcapabilitylab.sentry

import com.dante.zeekrcapabilitylab.sentry.canary.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CanaryHistoryInputTest {
    @Test fun threeMinutePinnedHistoryDoesNotCompeteWithThirtyTwoGopLiveBacklog() {
        val pool = EncodedBufferPool.paged(64 * 1024, 16)
        var input: CanaryHistoryInput? = null
        val ring = EncodedRingStore(pool, CanaryRamPolicy.TARGET_US) { input?.offer(it) }
        assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(42)), null))
        fun append(frame: Int) = ring.append(ByteBuffer.wrap(byteArrayOf(frame.toByte())), frame * 1_000_000L, frame % 2 == 0)
        repeat(181) { append(it) }
        val pinned = ring.pinHistory()
        assertEquals(90, pinned.size)
        input = CanaryHistoryInput(pinned)
        for (frame in 181..192) append(frame)
        ring.close()
        var frames = 0
        while (true) {
            val gop = input.poll(0) ?: break
            assertEquals(42.toByte(), gop.epoch.csd0().get())
            for (sample in gop.samples) {
                assertEquals(frames * 1_000_000L, sample.ptsUs)
                assertEquals(frames.toByte(), sample.buffer.view().get())
                frames++
            }
            gop.release()
        }
        assertEquals(192, frames)
        input.close()
        assertEquals(0L, pool.liveBytes)
        pool.close()
    }

    @Test fun cancellingWriterReleasesUnconsumedHistoryBeforeUnmappingArena() {
        var unmapped = 0
        val pool = EncodedBufferPool.mapped(ByteBuffer.allocateDirect(64 * 1024)) { unmapped++ }
        val ring = EncodedRingStore(pool, CanaryRamPolicy.TARGET_US)
        assertTrue(ring.setFormat(1280, 5140, ByteBuffer.wrap(byteArrayOf(42)), null))
        repeat(10) { ring.append(ByteBuffer.wrap(byteArrayOf(1)), it.toLong(), it % 2 == 0) }
        val input = CanaryHistoryInput(ring.pinHistory())
        ring.close()
        assertThrows(IllegalStateException::class.java) { pool.close() }
        assertEquals(0, unmapped)
        input.close()
        input.close()
        assertEquals(0L, pool.liveBytes)
        pool.close()
        pool.close()
        assertEquals(1, unmapped)
        assertNull(pool.tryAcquire(1))
    }

    @Test fun reportUsesRunSpecificMemoryAndDurationAndRequiresNativeRelease() {
        val current = CanarySnapshot(phase = "STOPPED", outputFrames = 6000, historyTargetSeconds = 180,
            encodedBudgetBytes = EncodedBufferPool.CANARY_CAPACITY_BYTES.toLong(),
            encodedCapacityBytes = EncodedBufferPool.CANARY_CAPACITY_BYTES.toLong(), encodedHighWaterBytes = 720_000_000,
            bufferStorage = "ANONYMOUS_SHARED_MEMORY", telemetry = CanaryTelemetry(ramReadyObserved = true, maximumHistorySeconds = 181.0))
        fun status(value: CanarySnapshot, code: String) = CanaryEvidenceEvaluator.ram(value).checks.first { it.code == code }.status
        assertEquals(CanaryCheckStatus.PASS, status(current, "ENCODED_BUDGET"))
        assertEquals(CanaryCheckStatus.PASS, status(current, "RAM_HISTORY"))
        assertEquals(CanaryCheckStatus.FAIL, status(current, "SHUTDOWN_RELEASE"))
        assertEquals(CanaryCheckStatus.PASS, status(current.copy(bufferStorageReleased = true), "SHUTDOWN_RELEASE"))
        assertEquals(CanaryCheckStatus.PENDING, status(current.copy(telemetry = CanaryTelemetry(ramReadyObserved = true, maximumHistorySeconds = 31.0)), "RAM_HISTORY"))
        assertEquals(CanaryCheckStatus.FAIL, status(current.copy(encodedHighWaterBytes = current.encodedBudgetBytes + 1), "ENCODED_BUDGET"))
    }
}
