package com.dante.zeekrcapabilitylab.service.recorder

import io.github.dantenothing.avmtransfer.protocol.EmbeddedRecordingMetadata
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Runs after native muxer release and before hashing/publication. Rejects an ambiguous MP4. */
internal object ContinuousFileMetadata {
    fun append(channel: SeekableByteChannel, document: String) {
        val expected = RecordingRasterMetadata.read(document)
        require(expected is RecordingRasterMetadata.Repacked)
        val inspection = EmbeddedRecordingMetadata.inspect(channel)
        check(inspection.error == null && inspection.appendable) { inspection.error ?: "PRODUCT_MP4_NOT_APPENDABLE" }
        if (inspection.document != null) {
            check(RecordingRasterMetadata.read(inspection.document) == expected) { "PRODUCT_EMBEDDED_LAYOUT_CONFLICT" }
            check(io.github.dantenothing.avmtransfer.protocol.RecordingMetadataReader.resolve(document, inspection).raster !is RecordingRasterMetadata.Rejected) {
                "PRODUCT_EMBEDDED_CALIBRATION_CONFLICT"
            }
            return
        }
        channel.position(channel.size())
        val bytes = ByteBuffer.wrap(EmbeddedRecordingMetadata.box(document))
        while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "PRODUCT_METADATA_WRITE_STALLED" }
    }
}
