package com.dante.zeekrbridge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class WavParams(
    val format: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataBytes: Long,
)

@Serializable
data class OutboundOffer(
    val offerId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val wav: WavParams,
    val createdAt: Long,
)

/**
 * App-private store for user-selected WAV files that the car can download.
 * Payloads are copied with a fixed buffer (never readBytes), validated, hashed
 * and described by an atomically persisted metadata file.
 */
object OutboundOfferStore {
    private const val METADATA = "metadata.json"
    private const val METADATA_TMP = "metadata.json.tmp"
    private const val PAYLOAD = "sound.wav"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    private var root: File? = null
    private var trashRoot: File? = null

    fun init(context: Context) {
        if (root == null) {
            root = File(context.filesDir, "outbound").apply { mkdirs() }
            trashRoot = File(context.filesDir, "outbound-trash").apply { mkdirs() }
            scan()
        }
    }

    fun initForTests(dir: File) {
        root = dir.apply { mkdirs() }
        trashRoot = File(dir.parentFile, "outbound-trash").apply { mkdirs() }
        scan()
    }

    fun offers(): List<OutboundOffer> =
        rootDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val offer = loadMetadata(dir)
                if (offer == null || !payloadMatches(offer)) {
                    if (offer != null) quarantine(dir)
                    null
                } else {
                    offer
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun get(offerId: String): OutboundOffer? {
        val id = PathSafety.cleanUploadId(offerId) ?: return null
        val offer = loadMetadata(File(rootDir(), id)) ?: return null
        if (!payloadMatches(offer)) {
            quarantine(File(rootDir(), id))
            return null
        }
        return offer
    }

    fun fileFor(offerId: String): File? {
        val offer = get(offerId) ?: return null
        val file = File(File(rootDir(), offer.offerId), PAYLOAD)
        return if (file.isFile) file else null
    }

    fun importUri(context: Context, uri: Uri): OutboundOffer {
        val displayName = queryDisplayName(context, uri)
        val open: () -> InputStream = {
            context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open content uri")
        }
        return importStream(displayName, open)
    }

    /** Copies, validates and hashes a stream into a new offer. Throws on invalid input. */
    fun importStream(displayName: String?, open: () -> InputStream): OutboundOffer {
        val offerId = UUID.randomUUID().toString()
        val dir = File(rootDir(), offerId).apply { mkdirs() }
        val payload = File(dir, PAYLOAD)
        val tmp = File(dir, "$PAYLOAD.tmp")
        try {
            tmp.outputStream().use { out ->
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            total += read
                            if (total > WavValidator.MAX_BYTES) {
                                throw IOException("file exceeds ${WavValidator.MAX_BYTES} bytes")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            atomicMove(tmp.toPath(), payload.toPath())
            val info = WavValidator.validateFile(payload)
            if (!WavValidator.isAcceptable(info)) {
                throw IOException("not an acceptable WAV: ${info.reason ?: "INVALID"}")
            }
            val sha = StreamingSha256.hash(payload)
            val name = wavFileName(displayName, offerId)
            val offer = OutboundOffer(
                offerId = offerId,
                fileName = name,
                mimeType = "audio/wav",
                sizeBytes = payload.length(),
                sha256 = sha,
                wav = WavValidator.toParams(info)!!,
                createdAt = System.currentTimeMillis(),
            )
            writeMetadata(dir, offer)
            return offer
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    fun delete(offerId: String): Boolean {
        val id = PathSafety.cleanUploadId(offerId) ?: return false
        val dir = File(rootDir(), id)
        if (!dir.isDirectory) return false
        val target = File(trashRoot(), "$id-${System.currentTimeMillis()}")
        return try {
            atomicMove(dir.toPath(), target.toPath())
            true
        } catch (t: Throwable) {
            dir.deleteRecursively()
            !dir.exists()
        }
    }

    fun metadataMap(offer: OutboundOffer): Map<String, String> = mapOf(
        "offerId" to offer.offerId,
        "fileName" to offer.fileName,
        "mimeType" to offer.mimeType,
        "sizeBytes" to offer.sizeBytes.toString(),
        "sha256" to offer.sha256,
        "wavFormat" to offer.wav.format.toString(),
        "wavChannels" to offer.wav.channels.toString(),
        "wavSampleRate" to offer.wav.sampleRate.toString(),
        "wavBitsPerSample" to offer.wav.bitsPerSample.toString(),
        "wavDataBytes" to offer.wav.dataBytes.toString(),
        "createdAt" to offer.createdAt.toString(),
    )

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (t: Throwable) {
            null
        }

    private fun rootDir(): File = root ?: error("OutboundOfferStore not initialized")

    private fun trashRoot(): File = trashRoot ?: error("OutboundOfferStore not initialized")

    private fun loadMetadata(dir: File): OutboundOffer? {
        val meta = File(dir, METADATA)
        if (!meta.isFile) return null
        return try {
            json.decodeFromString(OutboundOffer.serializer(), meta.readText())
        } catch (t: Throwable) {
            null
        }
    }

    /** Safe .wav-ending name: keeps a reasonable basename, else tone-<id>.wav. */
    fun wavFileName(raw: String?, offerId: String): String {
        val clean = PathSafety.cleanFileName(raw)
        val base = clean?.substringBeforeLast('.', clean)?.trim()
        val safeBase = base?.take(180)?.takeIf { it.isNotBlank() }
        return if (safeBase != null) "$safeBase.wav" else "tone-$offerId.wav"
    }

    /**
     * Metadata must match the app-private payload exactly: directory id, file
     * presence, size, streamed SHA-256 and parsed WAV params. Files are <= 1 MiB
     * so this is an acceptable cost before any offer is listed or served.
     */
    private fun payloadMatches(offer: OutboundOffer): Boolean {
        val id = PathSafety.cleanUploadId(offer.offerId) ?: return false
        val dir = File(rootDir(), id)
        if (!dir.isDirectory) return false
        val file = File(dir, PAYLOAD)
        if (!file.isFile) return false
        if (file.length() != offer.sizeBytes) return false
        if (StreamingSha256.hash(file) != offer.sha256) return false
        val params = WavValidator.toParams(WavValidator.validateFile(file)) ?: return false
        return params == offer.wav
    }

    private fun writeMetadata(dir: File, offer: OutboundOffer) {
        val tmp = File(dir, METADATA_TMP)
        tmp.writeText(json.encodeToString(OutboundOffer.serializer(), offer))
        atomicMove(tmp.toPath(), File(dir, METADATA).toPath())
    }

    private fun scan() {
        val corruptRoot = File(rootDir().parentFile, "outbound-corrupt").apply { mkdirs() }
        rootDir().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val offer = loadMetadata(dir)
            if (offer == null || !payloadMatches(offer)) {
                quarantine(dir)
            }
        }
    }

    private fun quarantine(dir: File) {
        val corruptRoot = File(rootDir().parentFile, "outbound-corrupt").apply { mkdirs() }
        try {
            atomicMove(dir.toPath(), File(corruptRoot, "${dir.name}-${System.currentTimeMillis()}").toPath())
        } catch (t: Throwable) {
            dir.deleteRecursively()
        }
    }

    private fun atomicMove(from: java.nio.file.Path, to: java.nio.file.Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
