package io.github.dantenothing.avmtransfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolValidationTest {
    @Test fun chunksUseFourMiBAndExactTail() {
        val size = TransferProtocol.CHUNK_SIZE.toLong() * 2 + 7
        val total = ProtocolValidation.totalChunks(size, TransferProtocol.CHUNK_SIZE)
        assertEquals(3, total)
        assertEquals(7, ProtocolValidation.expectedChunkSize(size, TransferProtocol.CHUNK_SIZE, total!!, 2))
    }

    @Test fun namesCannotEscapeReceivedDirectory() {
        assertEquals("drive.mp4", ProtocolValidation.cleanFileName("../../drive.mp4"))
        assertNull(ProtocolValidation.cleanFileName(".."))
        assertNull(ProtocolValidation.cleanFileName("bad\u0000.mp4"))
    }

    @Test fun identitiesAndHashesAreStrict() {
        assertTrue(ProtocolValidation.validIdentity("car_7X-01"))
        assertNull(ProtocolValidation.normalizedSha("not-a-hash"))
    }
}
