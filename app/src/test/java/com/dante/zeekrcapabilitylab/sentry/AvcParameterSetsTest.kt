package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AvcParameterSetsTest {
    private fun data(vararg bytes: Int) = ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray())
    @Test fun splitAndCombinedConfigNormalizeToSameEpochWithoutDroppingHistory() {
        val split = AvcParameterSets.read(data(0, 0, 0, 1, 0x67, 1, 2), data(0, 0, 0, 1, 0x68, 3))
        val combined = AvcParameterSets.read(data(0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3))
        val ring = EncodedRingStore(EncodedBufferPool(List(20) { 32 }))
        assertTrue(ring.setAvcFormat(1280, 5120, split))
        ring.append(data(4, 5), 0, true)
        ring.append(data(6, 7), 1, true)
        val epoch = ring.snapshot().formatEpochId
        assertTrue(ring.setAvcFormat(1280, 5120, combined))
        assertEquals(epoch, ring.snapshot().formatEpochId)
        assertEquals(1, ring.snapshot().completedGops)
        val changed = AvcParameterSets.read(data(0, 0, 1, 0x67, 2, 2, 0, 0, 1, 0x68, 3))
        assertTrue(ring.setAvcFormat(1280, 5120, changed))
        assertNotEquals(epoch, ring.snapshot().formatEpochId)
        assertEquals(0, ring.snapshot().completedGops)
        ring.close()
        assertEquals(0L, ring.pool.liveBytes)
    }

    @Test fun missingParameterSetOrPictureDataIsNeverTreatedAsCodecConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { AvcParameterSets.read(data(0, 0, 1, 0x67, 2)) }
        assertThrows(IllegalStateException::class.java) { AvcParameterSets.read(data(0, 0, 1, 0x65, 2)) }
    }
}
