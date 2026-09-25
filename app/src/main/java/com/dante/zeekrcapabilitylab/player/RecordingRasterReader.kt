package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.io.File

/** Async metadata from a previous selection cannot start a decoder for the newly selected files. */
data class PlaybackRasterBatch(val paths: List<String>, val metadata: List<RecordingRasterMetadata>) {
    init { require(paths.size == metadata.size) }
    fun forPaths(requested: List<String>): List<RecordingRasterMetadata>? = metadata.takeIf { paths == requested }
}

/** Unlike the legacy nullable sidecar reader, a malformed layout must not become a legacy video. */
object RecordingRasterReader {
    fun read(media: File): RecordingRasterMetadata {
        return io.github.dantenothing.avmtransfer.protocol.RecordingMetadataReader.read(media,
            listOf(SegmentSidecarIO.sidecarFileFor(media), File(media.parentFile, media.nameWithoutExtension + ".sidecar.json"))).raster
    }
}
