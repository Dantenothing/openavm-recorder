package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OutboundOfferStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun validWavBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(44, 0, 0, 0))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        val fmt = ByteArray(16)
        fmt[0] = 1; fmt[1] = 0
        fmt[2] = 1; fmt[3] = 0
        fmt[4] = 0x44; fmt[5] = 0xAC.toByte(); fmt[6] = 0; fmt[7] = 0 // 44100
        fmt[8] = 0x88.toByte(); fmt[9] = 0x58.toByte(); fmt[10] = 1; fmt[11] = 0 // byte rate 88200
        fmt[12] = 2; fmt[13] = 0
        fmt[14] = 16; fmt[15] = 0
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(16, 0, 0, 0))
        out.write(fmt)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(8, 0, 0, 0))
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    @Test
    fun importPersistsMetadataAndPayload() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())

        val offer = OutboundOfferStore.importStream("my tone.wav") { src.inputStream() }

        assertEquals("my tone.wav", offer.fileName)
        assertEquals(src.length(), offer.sizeBytes)
        assertEquals(StreamingSha256.hash(src), offer.sha256)
        assertEquals(44100, offer.wav.sampleRate)
        assertTrue(File(root, "${offer.offerId}/metadata.json").exists())
        assertTrue(File(root, "${offer.offerId}/sound.wav").exists())

        OutboundOfferStore.initForTests(root)
        assertEquals(offer, OutboundOfferStore.get(offer.offerId))
        assertEquals(listOf(offer), OutboundOfferStore.offers())
        assertNotNull(OutboundOfferStore.fileFor(offer.offerId))
    }

    @Test
    fun invalidWavIsRejectedAndCleanedUp() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("bad.txt")
        src.writeText("not a wav")
        try {
            OutboundOfferStore.importStream("bad.txt") { src.inputStream() }
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
        assertEquals(0, OutboundOfferStore.offers().size)
    }

    @Test
    fun oversizedFileIsRejected() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("big.wav")
        val payload = ByteArray(WavValidator.MAX_BYTES.toInt() + 1)
        val out = java.io.ByteArrayOutputStream()
        out.write(validWavBytes())
        out.write(payload)
        src.writeBytes(out.toByteArray())
        try {
            OutboundOfferStore.importStream("big.wav") { src.inputStream() }
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
        assertEquals(0, OutboundOfferStore.offers().size)
    }

    @Test
    fun deleteMovesOfferToTrash() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())
        val offer = OutboundOfferStore.importStream("tone.wav") { src.inputStream() }

        assertTrue(OutboundOfferStore.delete(offer.offerId))
        assertNull(OutboundOfferStore.get(offer.offerId))
        val trash = File(root.parentFile, "outbound-trash")
        assertTrue(trash.listFiles()!!.isNotEmpty())
    }

    @Test
    fun pathSafetyRejectsTraversalIds() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        assertNull(OutboundOfferStore.get("../escape"))
        assertNull(OutboundOfferStore.fileFor("../escape"))
        assertFalse(OutboundOfferStore.delete("../escape"))
    }

    @Test
    fun metadataMapContainsAllOfferFields() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())
        val offer = OutboundOfferStore.importStream("tone.wav") { src.inputStream() }
        val map = OutboundOfferStore.metadataMap(offer)
        assertEquals(offer.offerId, map["offerId"])
        assertEquals(offer.sha256, map["sha256"])
        assertEquals("44100", map["wavSampleRate"])
        assertEquals("1", map["wavChannels"])
    }

    @Test
    fun importForcesWavExtension() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())
        assertEquals("foo.wav", OutboundOfferStore.importStream("foo.txt") { src.inputStream() }.fileName)
        assertEquals("music.wav", OutboundOfferStore.importStream("music") { src.inputStream() }.fileName)
        assertEquals("a.b.wav", OutboundOfferStore.importStream("a.b.c") { src.inputStream() }.fileName)
        val fallback = OutboundOfferStore.importStream(null) { src.inputStream() }
        assertTrue(fallback.fileName.startsWith("tone-"))
        assertTrue(fallback.fileName.endsWith(".wav"))
    }

    @Test
    fun stalePayloadWithSameSizeIsQuarantined() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())
        val offer = OutboundOfferStore.importStream("tone.wav") { src.inputStream() }
        val payload = File(root, "${offer.offerId}/sound.wav")
        val tampered = validWavBytes()
        tampered[50] = (tampered[50].toInt() xor 0xff).toByte()
        payload.writeBytes(tampered)

        assertNull(OutboundOfferStore.get(offer.offerId))
        assertNull(OutboundOfferStore.fileFor(offer.offerId))
        assertEquals(0, OutboundOfferStore.offers().size)
        val corrupt = File(root.parentFile, "outbound-corrupt")
        assertTrue(corrupt.listFiles()!!.isNotEmpty())
    }

    @Test
    fun missingPayloadIsQuarantined() {
        val root = tmp.newFolder("outbound")
        OutboundOfferStore.initForTests(root)
        val src = tmp.newFile("tone.wav")
        src.writeBytes(validWavBytes())
        val offer = OutboundOfferStore.importStream("tone.wav") { src.inputStream() }
        File(root, "${offer.offerId}/sound.wav").delete()

        assertNull(OutboundOfferStore.get(offer.offerId))
        assertEquals(0, OutboundOfferStore.offers().size)
        val corrupt = File(root.parentFile, "outbound-corrupt")
        assertTrue(corrupt.listFiles()!!.isNotEmpty())
    }
}
