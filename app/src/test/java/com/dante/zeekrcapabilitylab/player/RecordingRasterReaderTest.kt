package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingRasterReaderTest {
    @Test fun staleMetadataCannotBindToAnotherVideoOrPlaylistOrder() {
        val batch = PlaybackRasterBatch(listOf("a.mp4", "b.mp4"),
            listOf(RecordingRasterMetadata.Original, RecordingRasterMetadata.Original))
        assertNotNull(batch.forPaths(listOf("a.mp4", "b.mp4")))
        assertNull(batch.forPaths(listOf("b.mp4", "a.mp4")))
        assertNull(batch.forPaths(listOf("c.mp4")))
    }
    private val layout = StripRepackContract(inputWidth = 1280, inputHeight = 5140,
        stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)
    private fun media() = File(Files.createTempDirectory("raster-reader").toFile().apply { deleteOnExit() }, "recording.mp4")
        .apply { writeBytes(java.nio.ByteBuffer.allocate(16).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray()).array()); deleteOnExit() }

    @Test fun readsUsbAndInternalMetadataWithoutGuessingFromFileName() {
        val media = media()
        assertEquals(RecordingRasterMetadata.Original, RecordingRasterReader.read(media))
        val usb = File(media.parentFile, "recording.sidecar.json").apply { deleteOnExit() }
        usb.writeText(buildJsonObject { put("rasterLayout", Json.encodeToJsonElement(layout)) }.toString())
        assertEquals(RecordingRasterMetadata.Repacked(layout), RecordingRasterReader.read(media))
        File(media.absolutePath + ".sidecar.json").apply { deleteOnExit(); writeText("{broken") }
        assertEquals(RecordingRasterMetadata.Rejected("INVALID_RASTER_METADATA"), RecordingRasterReader.read(media))
    }

    @Test fun sizeBoundStopsUnexpectedMetadataBeforeParsing() {
        val media = media()
        File(media.absolutePath + ".sidecar.json").apply { deleteOnExit(); writeText(" ".repeat(TransferProtocol.MAX_SIDECAR_BYTES + 1)) }
        assertEquals(RecordingRasterMetadata.Rejected("SIDECAR_TOO_LARGE"), RecordingRasterReader.read(media))
    }
}
