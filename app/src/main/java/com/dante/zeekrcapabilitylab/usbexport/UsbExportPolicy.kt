package com.dante.zeekrcapabilitylab.usbexport

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object UsbExportPolicy {
    const val RELATIVE_PATH = "Download/OpenAVM/"
    const val FREE_SPACE_MARGIN_BYTES = 1024L * 1024L * 1024L
    const val OPENAVM_QUOTA_BYTES = 15L * 1024L * 1024L * 1024L

    fun exportKey(logicalId: String, sources: List<UsbExportSourceSnapshot>): String {
        val canonical = buildString {
            append(logicalId).append('\n')
            sources.sortedBy { it.segmentNumber }.forEach {
                append(it.segmentNumber).append('|')
                append(it.fileName).append('|').append(it.sizeBytes).append('|')
                append(it.sourceSha256).append('|')
                append(it.sidecarFileName).append('|').append(it.sidecarSha256).append('\n')
            }
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun prefix(startedAtEpochMs: Long, exportKey: String): String =
        "OpenAVM_${startedAtEpochMs}_${exportKey.take(12).lowercase(Locale.ROOT)}"

    fun videoName(prefix: String, ordinal: Int): String =
        "${prefix}_S${ordinal.toString().padStart(3, '0')}.mp4"

    fun sidecarName(prefix: String, ordinal: Int): String =
        "${prefix}_S${ordinal.toString().padStart(3, '0')}.sidecar.json"

    fun manifestName(prefix: String): String = "${prefix}_manifest.json"

    fun segmentManifestName(prefix: String, segmentNumber: Int): String =
        "${prefix}_S${segmentNumber.toString().padStart(3, '0')}.segment.json"

    fun bundleId(recordingSessionId: String, segmentNumber: Int, startedAtEpochMs: Long): String =
        sha256("$recordingSessionId|$segmentNumber|$startedAtEpochMs".toByteArray(Charsets.UTF_8))

    fun operationId(): String = UUID.randomUUID().toString()

    fun contentKey(
        logicalId: String,
        segmentNumber: Int,
        videoSha256: String,
        sidecarSha256: String,
    ): String = sha256(
        "$logicalId|$segmentNumber|$videoSha256|$sidecarSha256".toByteArray(Charsets.UTF_8),
    )

    fun hasSpace(freeBytes: Long?, payloadBytes: Long): Boolean =
        freeBytes != null && payloadBytes >= 0L &&
            freeBytes >= payloadBytes + FREE_SPACE_MARGIN_BYTES

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
