package io.github.dantenothing.avmtransfer.protocol

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.channels.SeekableByteChannel
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * A bounded, app-owned ISO BMFF uuid box preserves layout when a composite is exported alone.
 * It is appended only to finalized, size-delimited MP4s. No sample offsets or video bytes change.
 * Readers seek over mdat; neither file size nor the number of frames determines memory use.
 */
object EmbeddedRecordingMetadata {
    private val id = UUID.fromString("dfe2e9b0-9f6b-4ac3-920d-2ff006ebea73")
    private val uuid = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
    private const val UUID_BOX = 0x75756964
    private const val FTYP = 0x66747970
    private const val MOOV = 0x6d6f6f76
    private const val VERSION_KEY = "openAvmMetadataVersion"
    private val json = Json { ignoreUnknownKeys = true }

    data class Inspection(val document: String?, val appendable: Boolean, val error: String? = null)

    fun inspect(file: File): Inspection = runCatching {
        file.inputStream().channel.use(::inspect)
    }.getOrElse { Inspection(null, false, "MP4_METADATA_READ_FAILED") }

    /** Does not close the caller's channel and restores its position. */
    fun inspect(channel: SeekableByteChannel): Inspection {
        val original = channel.position()
        return try {
            val length = channel.size()
            var position = 0L
            var count = 0
            var ftyp = false
            var moov = false
            var delimited = true
            var document: String? = null
            while (position < length) {
                check(++count <= 512) { "MP4_BOX_COUNT_LIMIT" }
                check(length - position >= 8) { "MP4_TRUNCATED_BOX_HEADER" }
                channel.position(position)
                val header = read(channel, 8)
                val smallSize = header.int.toLong() and 0xffffffffL
                val type = header.int
                val headerSize = if (smallSize == 1L) 16L else 8L
                val size = when (smallSize) {
                    0L -> { delimited = false; length - position }
                    1L -> read(channel, 8).long
                    else -> smallSize
                }
                check(size >= headerSize && size <= length - position) { "MP4_INVALID_BOX_SIZE" }
                if (type == FTYP) ftyp = true
                if (type == MOOV) moov = true
                if (type == UUID_BOX) {
                    check(size >= headerSize + 16) { "MP4_INVALID_UUID_BOX" }
                    val boxId = read(channel, 16).array()
                    if (boxId.contentEquals(uuid)) {
                        val bytes = size - headerSize - 16
                        check(bytes in 1..TransferProtocol.MAX_SIDECAR_BYTES.toLong()) { "MP4_METADATA_SIZE_LIMIT" }
                        val candidate = decodeUtf8(read(channel, bytes.toInt()).array())
                        check(json.parseToJsonElement(candidate).jsonObject[VERSION_KEY]?.jsonPrimitive?.intOrNull == 1) {
                            "MP4_METADATA_VERSION_UNSUPPORTED"
                        }
                        check(RecordingRasterMetadata.read(candidate) is RecordingRasterMetadata.Repacked) {
                            "MP4_INVALID_RASTER_METADATA"
                        }
                        check(document == null || document == candidate) { "MP4_CONFLICTING_METADATA" }
                        document = candidate
                    }
                }
                position += size
            }
            Inspection(document, ftyp && moov && delimited)
        } catch (error: Exception) {
            Inspection(null, false, error.message?.takeIf { it.startsWith("MP4_") } ?: "MP4_METADATA_READ_FAILED")
        } finally {
            channel.position(original)
        }
    }

    fun box(document: String): ByteArray {
        require(RecordingRasterMetadata.read(document) is RecordingRasterMetadata.Repacked) { "INVALID_RASTER_METADATA" }
        val root = json.parseToJsonElement(document).jsonObject
        val payload = JsonObject(root + (VERSION_KEY to JsonPrimitive(1))).toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= TransferProtocol.MAX_SIDECAR_BYTES)
        return ByteBuffer.allocate(24 + payload.size)
            .putInt(24 + payload.size).putInt(UUID_BOX).put(uuid).put(payload).array()
    }

    private fun read(channel: SeekableByteChannel, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(size)
        var emptyReads = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            check(read >= 0) { "MP4_TRUNCATED_BOX" }
            if (read == 0) check(++emptyReads < 3) { "MP4_METADATA_READ_STALLED" }
            else emptyReads = 0
        }
        buffer.flip()
        return buffer
    }

    internal fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

data class RecordingMetadataDocument(val text: String?, val raster: RecordingRasterMetadata)

/** Both files and exports use the same fail-closed metadata path. No geometry is guessed. */
object RecordingMetadataReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(media: File, sidecars: List<File>): RecordingMetadataDocument {
        val sidecar = sidecars.firstOrNull { it.isFile }
        val sidecarText = try {
            sidecar?.inputStream()?.use(::readBounded)
        } catch (error: Exception) {
            return rejected(if (error.message == "SIDECAR_TOO_LARGE") "SIDECAR_TOO_LARGE" else "SIDECAR_READ_FAILED")
        }
        val embedded = EmbeddedRecordingMetadata.inspect(media)
        return resolve(sidecarText, embedded)
    }

    fun resolve(sidecar: String?, embedded: EmbeddedRecordingMetadata.Inspection): RecordingMetadataDocument {
        val sidecarRaster = RecordingRasterMetadata.read(sidecar)
        if (sidecarRaster is RecordingRasterMetadata.Rejected) return RecordingMetadataDocument(sidecar, sidecarRaster)
        if (embedded.error != null) return rejected(embedded.error)
        val inFile = embedded.document ?: return RecordingMetadataDocument(sidecar, sidecarRaster)
        if (sidecar == null) return RecordingMetadataDocument(inFile, RecordingRasterMetadata.read(inFile))
        return runCatching {
            val first = json.parseToJsonElement(inFile).jsonObject
            val second = json.parseToJsonElement(sidecar).jsonObject
            for (key in listOf("rasterLayout", "sourceRole", "layoutKind", "laneLayout")) {
                val a = first[key]?.takeUnless { it == JsonNull }
                val b = second[key]?.takeUnless { it == JsonNull }
                if (a != null && b != null && a != b) return rejected("CONFLICTING_RECORDING_METADATA")
            }
            // Null legacy fields must not erase an explicit embedded descriptor.
            val merged = JsonObject(first + second.filterValues { it != JsonNull }).toString()
            RecordingMetadataDocument(merged, RecordingRasterMetadata.read(merged))
        }.getOrElse { rejected("INVALID_RECORDING_METADATA") }
    }

    fun readBounded(input: InputStream): String {
        val buffer = ByteArray(TransferProtocol.MAX_SIDECAR_BYTES + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            check(read > 0) { "SIDECAR_READ_STALLED" }
            count += read
        }
        require(count <= TransferProtocol.MAX_SIDECAR_BYTES) { "SIDECAR_TOO_LARGE" }
        return EmbeddedRecordingMetadata.decodeUtf8(buffer.copyOf(count))
    }

    private fun rejected(reason: String) = RecordingMetadataDocument(null, RecordingRasterMetadata.Rejected(reason))
}
