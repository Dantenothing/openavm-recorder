package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.DriveDataCodec
import io.github.dantenothing.avmtransfer.protocol.DriveDataDescriptor
import io.github.dantenothing.avmtransfer.protocol.ProtocolValidation
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private per-video attachment. Not a media item and never an ordinary video-export sidecar. */
object DriveDataFiles {
    const val DIRECTORY = ".drive-data"
    const val SUFFIX = ".drive.json"
    private const val MAX_STORED_BYTES = 2 * DriveDataCodec.MAX_BYTES + 16 * 1024
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val descriptor: DriveDataDescriptor,
        val videoSizeBytes: Long,
        val videoSha256: String,
        val sidecarSha256: String,
        // Keep the exact original bytes represented as UTF-8, not a re-encoded bundle with a new hash.
        val payloadJson: String,
    )

    /** Returns the controlled attachment path even after the video was moved to Trash. */
    fun forVideo(video: File): File? = runCatching {
        if (ProtocolValidation.cleanFileName(video.name) != video.name || !video.extension.equals("mp4", true)) return null
        val parent = video.parentFile?.canonicalFile ?: return null
        if (video.canonicalFile.parentFile != parent) return null
        val hidden = File(parent, DIRECTORY)
        if (hidden.canonicalFile.parentFile != parent || hidden.canonicalFile.name != DIRECTORY) return null
        File(hidden, video.name + SUFFIX).takeIf { it.canonicalFile.parentFile == hidden.canonicalFile }
    }.getOrNull()

    fun load(video: File): DriveDataBundle? = verified(video)?.second

    internal fun matches(video: File, descriptor: DriveDataDescriptor, sidecarJson: String?, videoSize: Long, videoSha: String): Boolean {
        val result = verified(video) ?: return false
        val stored = result.first
        return stored.descriptor == descriptor && stored.videoSizeBytes == videoSize && stored.videoSha256 == videoSha &&
            sidecarJson != null && stored.sidecarSha256 == sha256(sidecarJson.toByteArray(Charsets.UTF_8))
    }

    private fun verified(video: File): Pair<Stored, DriveDataBundle>? = runCatching {
        if (!video.isFile) return null
        val file = forVideo(video) ?: return null
        val bytes = readBounded(file, MAX_STORED_BYTES) ?: return null
        val stored = json.decodeFromString<Stored>(bytes.toString(Charsets.UTF_8))
        if (stored.version != 1 || !DriveDataCodec.validDescriptor(stored.descriptor) ||
            stored.videoSizeBytes != video.length() || stored.videoSizeBytes !in 0..TransferProtocol.MAX_FILE_BYTES ||
            ProtocolValidation.normalizedSha(stored.videoSha256) != stored.videoSha256) return null
        val sidecar = MediaIndexScanner.sidecarCandidates(video).firstOrNull { it.isFile } ?: return null
        if (sidecar.canonicalFile.parentFile != video.parentFile?.canonicalFile) return null
        val sidecarBytes = readBounded(sidecar, TransferProtocol.MAX_SIDECAR_BYTES) ?: return null
        if (sha256(sidecarBytes) != stored.sidecarSha256) return null
        val bundle = DriveDataCodec.verify(stored.payloadJson.toByteArray(Charsets.UTF_8), stored.descriptor, sidecarBytes.toString(Charsets.UTF_8)) ?: return null
        stored to bundle
    }.getOrNull()

    internal fun write(video: File, bytes: ByteArray, descriptor: DriveDataDescriptor, sidecarJson: String?, videoSize: Long, videoSha: String) {
        require(DriveDataCodec.verify(bytes, descriptor, sidecarJson) != null)
        require(videoSize in 0..TransferProtocol.MAX_FILE_BYTES && ProtocolValidation.normalizedSha(videoSha) == videoSha)
        val target = forVideo(video) ?: error("INVALID_DRIVE_DATA_PATH")
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val stored = Stored(descriptor = descriptor, videoSizeBytes = videoSize, videoSha256 = videoSha,
            sidecarSha256 = sha256(sidecarJson!!.toByteArray(Charsets.UTF_8)), payloadJson = bytes.toString(Charsets.UTF_8))
        val encoded = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
        check(encoded.size <= MAX_STORED_BYTES)
        val temporary = File(target.absolutePath + ".partial")
        try {
            FileOutputStream(temporary).use { it.write(encoded); it.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }

    internal fun removeUncommitted(video: File) {
        if (video.exists()) return
        forVideo(video)?.let { File(it.absolutePath + ".partial").delete(); it.delete() }
    }

    internal fun cleanupOrphans(receivedRoot: File, reservedNames: Set<String>) {
        val hidden = File(receivedRoot, DIRECTORY)
        if (hidden.canonicalFile.parentFile != receivedRoot.canonicalFile || hidden.canonicalFile.name != DIRECTORY) return
        hidden.listFiles().orEmpty().forEach { entry ->
            val partial = entry.name.endsWith(SUFFIX + ".partial")
            val name = entry.name.removeSuffix(".partial").takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX) ?: return@forEach
            val video = File(receivedRoot, name)
            val expected = forVideo(video) ?: return@forEach
            if (entry.canonicalFile != (if (partial) File(expected.absolutePath + ".partial") else expected).canonicalFile) return@forEach
            if (partial || (!video.exists() && name !in reservedNames)) entry.delete()
        }
    }

    internal fun readBounded(file: File, limit: Int): ByteArray? = runCatching {
        if (!file.isFile || file.length() !in 1..limit.toLong()) return null
        file.inputStream().use { input ->
            val output = ByteArrayOutputStream(minOf(file.length().toInt(), 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > limit) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
