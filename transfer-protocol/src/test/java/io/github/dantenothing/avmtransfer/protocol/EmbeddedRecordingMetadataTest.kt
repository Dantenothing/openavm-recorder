package io.github.dantenothing.avmtransfer.protocol

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class EmbeddedRecordingMetadataTest {
    private val raster = StripRepackContract(inputWidth = 1280, inputHeight = 5140,
        stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)
    private val document = buildJsonObject { put("rasterLayout", Json.encodeToJsonElement(raster)) }.toString()
    private val prefix = box("ftyp", "isom0000".toByteArray()) + box("moov", ByteArray(0))

    @Test fun standaloneMp4RetainsExplicitLayoutAndReadersRestorePosition() {
        val bytes = prefix + box("mdat", byteArrayOf(1, 2, 3)) + EmbeddedRecordingMetadata.box(document)
        val channel = MemoryChannel(bytes.size.toLong(), mapOf(0L to bytes)).apply { position(3) }
        val result = EmbeddedRecordingMetadata.inspect(channel)
        assertNull(result.error)
        assertTrue(result.appendable)
        assertEquals(3L, channel.position())
        assertTrue(channel.isOpen)
        assertEquals(RecordingRasterMetadata.Repacked(raster), RecordingRasterMetadata.read(result.document))
    }

    @Test fun fourGigabyteMediaDataIsSkippedWithoutReadingOrAllocatingIt() {
        val mdatSize = 0x1_0000_0040L
        val mdat = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(mdatSize).array()
        val metadata = EmbeddedRecordingMetadata.box(document)
        val offset = prefix.size + mdatSize
        val channel = MemoryChannel(offset + metadata.size, mapOf(0L to (prefix + mdat), offset to metadata))
        val result = EmbeddedRecordingMetadata.inspect(channel)
        assertNull(result.error)
        assertNotNull(result.document)
        assertTrue(channel.bytesRead < 2_000)
        assertTrue(channel.maximumRead < 1_000)
    }

    @Test fun zeroSizedTailCannotHaveMetadataAppendedInsideItsPayload() {
        val tail = ByteBuffer.allocate(11).putInt(0).put("mdat".toByteArray()).put(byteArrayOf(1, 2, 3)).array()
        val result = inspect(prefix + tail)
        assertNull(result.error)
        assertFalse(result.appendable)
    }

    @Test fun truncatedOversizedOrNegativeBoxesAreRejected() {
        listOf(byteArrayOf(1), ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).put("mdat".toByteArray()).array(),
            ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(Long.MIN_VALUE).array()).forEach {
            val result = inspect(prefix + it)
            assertNotNull(result.error)
            assertFalse(result.appendable)
        }
    }

    @Test fun duplicateConflictingMetadataDoesNotSelectTheLastWriter() {
        val other = buildJsonObject { put("rasterLayout", Json.encodeToJsonElement(
            raster.copy(stripHeight = 1285, encodedWidth = 5120, encodedHeight = 1285))) }.toString()
        assertEquals("MP4_CONFLICTING_METADATA", inspect(prefix + EmbeddedRecordingMetadata.box(document) +
            EmbeddedRecordingMetadata.box(other)).error)
    }

    @Test fun unsupportedEnvelopeAndInvalidUtf8AreRejected() {
        val header = EmbeddedRecordingMetadata.box(document).copyOf(24)
        fun payload(bytes: ByteArray): ByteArray = header.copyOf().also { ByteBuffer.wrap(it).putInt(24 + bytes.size) } + bytes
        assertEquals("MP4_METADATA_VERSION_UNSUPPORTED", inspect(prefix + payload(document.toByteArray())).error)
        assertNotNull(inspect(prefix + payload(byteArrayOf(0xc3.toByte(), 0x28))).error)
    }

    @Test fun excessiveBoxCountAndMetadataCannotExhaustMemory() {
        val many = prefix + (0 until 512).fold(ByteArray(0)) { bytes, _ -> bytes + box("free", ByteArray(0)) }
        assertEquals("MP4_BOX_COUNT_LIMIT", inspect(many).error)
        assertThrows(IllegalArgumentException::class.java) {
            RecordingMetadataReader.readBounded(ByteArrayInputStream(ByteArray(TransferProtocol.MAX_SIDECAR_BYTES + 1)))
        }
    }

    @Test fun sidecarAndEmbeddedIdentityMustAgreeAndMalformedSidecarNeverFallsBack() {
        val embedded = inspect(prefix + EmbeddedRecordingMetadata.box(document))
        assertEquals(RecordingRasterMetadata.Repacked(raster), RecordingMetadataReader.resolve(null, embedded).raster)
        assertEquals(RecordingRasterMetadata.Repacked(raster), RecordingMetadataReader.resolve("{}", embedded).raster)
        assertEquals(RecordingRasterMetadata.Rejected("INVALID_RASTER_METADATA"),
            RecordingMetadataReader.resolve("{broken", embedded).raster)
        val different = document.replace("1728", "1285").replace("3840", "5120")
        assertEquals(RecordingRasterMetadata.Rejected("CONFLICTING_RECORDING_METADATA"),
            RecordingMetadataReader.resolve(different, embedded).raster)
    }

    private fun inspect(bytes: ByteArray) = EmbeddedRecordingMetadata.inspect(MemoryChannel(bytes.size.toLong(), mapOf(0L to bytes)))
    private fun box(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()

    private class MemoryChannel(private val length: Long, private val regions: Map<Long, ByteArray>) : SeekableByteChannel {
        var bytesRead = 0; var maximumRead = 0
        private var offset = 0L; private var open = true
        override fun read(dst: ByteBuffer): Int {
            if (offset >= length) return -1
            val size = minOf(dst.remaining().toLong(), length - offset).toInt()
            bytesRead += size; maximumRead = maxOf(maximumRead, size)
            repeat(size) {
                val region = regions.entries.firstOrNull { offset >= it.key && offset - it.key < it.value.size }
                dst.put(region?.let { it.value[(offset - it.key).toInt()] } ?: 0)
                offset++
            }
            return size
        }
        override fun write(src: ByteBuffer): Int = error("read only")
        override fun position(): Long = offset
        override fun position(newPosition: Long): SeekableByteChannel { offset = newPosition; return this }
        override fun size(): Long = length
        override fun truncate(size: Long): SeekableByteChannel = error("read only")
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
    }
}
