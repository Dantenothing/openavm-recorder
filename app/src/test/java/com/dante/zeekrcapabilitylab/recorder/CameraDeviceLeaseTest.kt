package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CameraDeviceLease
import org.junit.Assert.*
import org.junit.Test

class CameraDeviceLeaseTest {
    private val camera = Any()
    private fun source() = CameraDeviceLease<Any, String>("2", Any(), "preview").apply { opened(camera) }
    @Test fun repeatedPreviewRecordStopTransfersRetainOneNativeDevice() {
        val source = source()
        repeat(200) { cycle ->
            val now = cycle * 1_000L
            val ticket = "intent-$cycle"
            assertTrue(source.expect("2", ticket, now, now + 900))
            assertFalse(source.canClaim(ticket, "2", now))
            val draining = source.prepare(camera, now)!!
            assertNull(source.claim(ticket, "2", now, "early"))
            assertTrue(source.producerReleased(camera, draining, now + 100, true))
            assertSame(camera, source.claim(ticket, "2", now + 101, if (cycle % 2 == 0) "recorder" else "preview"))
            assertNull(source.claim(ticket, "2", now + 102, "replay"))
        }
        assertEquals(CameraDeviceLease.Phase.OWNED, source.phase)
    }
    @Test fun cancellationDuringDrainNeverHandsTheConnectionToNextOwner() {
        val source = source()
        source.expect("2", "record", 0, 1_000)
        val transfer = source.prepare(camera, 1)!!
        assertFalse(source.cancel()) // Active producer still has to finish; do not race its close.
        assertEquals("preview", source.owner)
        assertFalse(source.producerReleased(camera, transfer, 2, true))
        assertNull(source.owner)
        assertEquals(CameraDeviceLease.Phase.CLOSING, source.phase)
        assertNotNull(source.device) // Closing is not a real onClosed acknowledgement.
        source.closeAcknowledged(camera)
        assertEquals(CameraDeviceLease.Phase.CLOSED, source.phase)
    }
    @Test fun oldCallbacksAndTicketsCannotReplaceOrRetireNewOwner() {
        val source = source(); source.expect("2", "record", 0, 1_000)
        val transfer = source.prepare(camera, 1)!!
        source.producerReleased(camera, transfer, 2, true)
        assertNull(source.claim("wrong", "2", 3, "unrelated"))
        assertNull(source.claim("record", "0", 3, "cabin"))
        assertSame(camera, source.claim("record", "2", 3, "recorder"))
        assertNull(source.closeAcknowledged(Any()))
        assertEquals("recorder", source.owner)
        assertFalse(source.cancel("record"))
        assertEquals("recorder", source.closeAcknowledged(camera))
        assertNull(source.closeAcknowledged(camera))
    }
    @Test fun sleepClosesParkedConnectionButDoesNotManufactureAcknowledgement() {
        val source = source(); source.expect("2", "preview", 0, 1_000)
        val transfer = source.prepare(camera, 1)!!
        source.producerReleased(camera, transfer, 2, true)
        assertTrue(source.cancel()); assertFalse(source.cancel())
        assertNull(source.claim("preview", "2", 3, "new"))
        assertNotNull(source.device)
        source.closeAcknowledged(camera); assertNull(source.device)
    }
    @Test fun expiredOrFailedDrainMustCloseEvenWhenAllResourcesEventuallyReturn() {
        for (fault in listOf(false, true)) {
            val source = source(); source.expect("2", "preview", 0, 1_000)
            val transfer = source.prepare(camera, 1)!!
            if (fault) source.fault(camera)
            assertFalse(source.producerReleased(camera, transfer, if (fault) 2 else 1_000, true))
            assertEquals(CameraDeviceLease.Phase.CLOSING, source.phase)
        }
        val source = source(); source.expect("2", "preview", 0, 1_000)
        val transfer = source.prepare(camera, 1)!!
        assertFalse(source.producerReleased(camera, transfer, 2, false))
    }
    @Test fun noAutomaticOrUnboundedLeaseIsAccepted() {
        val source = source()
        assertNull(source.prepare(camera, 0))
        assertFalse(source.expect("2", "x", 0, CameraDeviceLease.MAX_HANDOFF_MS + 1))
        assertFalse(source.expect("1", "x", 0, 1_000))
        source.fault(camera)
        assertFalse(source.expect("2", "x", 0, 1_000))
    }
}
