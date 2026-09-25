package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFileMetadata
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files

class ContinuousFileMetadataTest {
    private val layout = StripRepackContract(inputWidth = 1280, inputHeight = 5140, stripHeight = 1728,
        encodedWidth = 3840, encodedHeight = 1728)
    private val document = buildJsonObject { put("rasterLayout", Json.encodeToJsonElement(layout)) }.toString()
    private fun box(kind: String, payload: ByteArray = byteArrayOf()) = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size).put(kind.toByteArray()).put(payload).array()

    @Test fun appendPreservesAllExistingMp4BytesAndIsIdempotent() {
        val file = Files.createTempFile("product-mp4", ".mp4").toFile()
        try {
            val original = box("ftyp") + box("mdat", ByteArray(8192) { it.toByte() }) + box("moov")
            file.writeBytes(original)
            RandomAccessFile(file, "rw").use { ContinuousFileMetadata.append(it.channel, document) }
            val once = file.readBytes()
            assertArrayEquals(original, once.copyOf(original.size))
            assertEquals(RecordingRasterMetadata.Repacked(layout), RecordingRasterMetadata.read(EmbeddedRecordingMetadata.inspect(file).document))
            RandomAccessFile(file, "rw").use { ContinuousFileMetadata.append(it.channel, document) }
            assertArrayEquals(once, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun unfinishedMp4CannotBecomeARecordedVideoByAppendingMetadata() {
        val file = Files.createTempFile("unfinished-mp4", ".mp4").toFile()
        try {
            val bytes = box("ftyp") + box("mdat", byteArrayOf(1, 2))
            file.writeBytes(bytes)
            RandomAccessFile(file, "rw").use { channel ->
                assertThrows(IllegalStateException::class.java) { ContinuousFileMetadata.append(channel.channel, document) }
            }
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun eofSizedMdatIsNotSilentlyExtendedWithMetadataInsideVideoPayload() {
        val file = Files.createTempFile("eof-mp4", ".mp4").toFile()
        try {
            val bytes = box("ftyp") + box("moov") + ByteBuffer.allocate(8).putInt(0).put("mdat".toByteArray()).array()
            file.writeBytes(bytes)
            RandomAccessFile(file, "rw").use { channel ->
                assertThrows(IllegalStateException::class.java) { ContinuousFileMetadata.append(channel.channel, document) }
            }
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
}
