package com.dante.zeekrbridge.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UploadSession(
    val uploadId: String,
    val request: UploadCreateRequest,
    val chunkSize: Int,
    val totalChunks: Int,
    val status: String = "UPLOADING",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedPath: String? = null,
    val completedSha256: String? = null,
)

data class ChunkStoreResult(
    val ok: Boolean,
    val error: String? = null,
)

/**
 * Persistent upload session store shared by the HTTP and Bluetooth servers.
 * Metadata is written atomically (tmp + rename) into each upload directory so
 * a process restart can resume valid sessions; corrupt metadata is quarantined
 * and reported as an explicit error instead of a silent 404.
 */
object UploadSessionStore {
    const val COMPLETED_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val METADATA = "metadata.json"
    private const val METADATA_TMP = "metadata.json.tmp"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    private var root: File? = null
    private val corrupt = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (root == null) {
            root = File(context.filesDir, "uploads").apply { mkdirs() }
            scan()
        }
    }

    fun initForTests(dir: File) {
        root = dir.apply { mkdirs() }
        corrupt.clear()
        scan()
    }

    fun uploadsRoot(): File = root ?: error("UploadSessionStore not initialized")

    fun create(request: UploadCreateRequest, chunkSize: Int): UploadSession {
        val fileName = PathSafety.cleanFileName(request.fileName)
            ?: throw IllegalArgumentException("invalid file name")
        val size = request.sizeBytes
        if (size < 0) throw IllegalArgumentException("invalid size")
        if (chunkSize <= 0) throw IllegalArgumentException("invalid chunk size")
        val sha = ShaHex.normalize(request.sha256) ?: throw IllegalArgumentException("invalid sha256")
        val totalLong = if (size == 0L) 0L else (size - 1) / chunkSize + 1
        if (totalLong > Protocol.MAX_UPLOAD_TOTAL_CHUNKS) throw IllegalArgumentException("upload too large")
        val uploadId = UUID.randomUUID().toString()
        val session = UploadSession(
            uploadId = uploadId,
            request = request.copy(
                fileName = fileName,
                carId = PathSafety.cleanCarId(request.carId),
                sha256 = sha,
            ),
            chunkSize = chunkSize,
            totalChunks = totalLong.toInt(),
        )
        val dir = dirFor(uploadId).apply { mkdirs() }
        writeMetadata(dir, session)
        return session
    }

    fun get(uploadId: String): UploadSession? {
        val id = PathSafety.cleanUploadId(uploadId) ?: return null
        val dir = dirFor(id)
        if (!dir.isDirectory) return null
        val meta = File(dir, METADATA)
        if (!meta.exists()) {
            quarantine(dir, id)
            return null
        }
        return try {
            val session = json.decodeFromString(UploadSession.serializer(), meta.readText())
            if (session.uploadId != id) {
                quarantine(dir, id)
                null
            } else {
                session
            }
        } catch (t: Throwable) {
            quarantine(dir, id)
            null
        }
    }

    fun isCorrupt(uploadId: String): Boolean = corrupt.contains(uploadId)

    fun receivedChunks(session: UploadSession): List<Int> =
        if (session.status == "COMPLETED") {
            (0 until session.totalChunks).toList()
        } else {
            ChunkTools.parseChunkFiles(dirFor(session.uploadId).listFiles())
        }

    fun expectedChunkSize(session: UploadSession, index: Int): Int {
        if (index < 0 || index >= session.totalChunks) return -1
        if (index < session.totalChunks - 1) return session.chunkSize
        val remainder = session.request.sizeBytes - (session.totalChunks - 1).toLong() * session.chunkSize
        return if (session.request.sizeBytes == 0L) 0 else remainder.toInt()
    }

    /** Writes to `chunk-<index>.partial`, then atomically renames to the final name. */
    fun storeChunk(session: UploadSession, index: Int, bytes: ByteArray): ChunkStoreResult {
        if (session.status == "COMPLETED") return ChunkStoreResult(false, "upload already completed")
        val expected = expectedChunkSize(session, index)
        if (expected < 0) return ChunkStoreResult(false, "chunk index out of range")
        if (bytes.size != expected) return ChunkStoreResult(false, "chunk size mismatch expected=$expected actual=${bytes.size}")
        val dir = dirFor(session.uploadId)
        val partial = File(dir, "chunk-$index.partial")
        val target = File(dir, "chunk-$index")
        return try {
            partial.writeBytes(bytes)
            atomicMove(partial.toPath(), target.toPath())
            ChunkStoreResult(true)
        } catch (t: Throwable) {
            try {
                partial.delete()
            } catch (t2: Throwable) {
                // Ignore.
            }
            ChunkStoreResult(false, "store failed")
        }
    }

    fun delete(uploadId: String) {
        val id = PathSafety.cleanUploadId(uploadId) ?: return
        dirFor(id).deleteRecursively()
        corrupt.remove(id)
    }

    /** Persists the successful completion result, then frees chunk files. */
    fun markCompleted(
        session: UploadSession,
        path: String,
        sha: String,
        now: Long = System.currentTimeMillis(),
    ): UploadSession {
        val completed = session.copy(
            status = "COMPLETED",
            completedPath = path,
            completedSha256 = sha,
            updatedAt = now,
        )
        val dir = dirFor(session.uploadId)
        writeMetadata(dir, completed)
        dir.listFiles()
            ?.filter { it.name.startsWith("chunk-") || it.name.endsWith(".partial") }
            ?.forEach { it.delete() }
        return completed
    }

    fun cleanupExpiredCompleted(
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = COMPLETED_TTL_MS,
    ) {
        uploadsRoot().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val meta = File(dir, METADATA)
            if (!meta.exists()) return@forEach
            val session = runCatching {
                json.decodeFromString(UploadSession.serializer(), meta.readText())
            }.getOrNull() ?: return@forEach
            if (session.status == "COMPLETED" && now - session.updatedAt > ttlMs) {
                dir.deleteRecursively()
                corrupt.remove(session.uploadId)
            }
        }
    }

    private fun dirFor(uploadId: String): File = File(uploadsRoot(), uploadId)

    private fun writeMetadata(dir: File, session: UploadSession) {
        val tmp = File(dir, METADATA_TMP)
        tmp.writeText(json.encodeToString(UploadSession.serializer(), session))
        atomicMove(tmp.toPath(), File(dir, METADATA).toPath())
    }

    private fun scan() {
        cleanupExpiredCompleted()
        uploadsRoot().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name
            val meta = File(dir, METADATA)
            val valid = meta.exists() && runCatching {
                val session = json.decodeFromString(UploadSession.serializer(), meta.readText())
                session.uploadId == id
            }.getOrDefault(false)
            if (!valid) {
                quarantine(dir, id)
            } else {
                dir.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
            }
        }
    }

    private fun quarantine(dir: File, id: String) {
        corrupt.add(id)
        try {
            val corruptRoot = File(uploadsRoot().parentFile, "uploads-corrupt").apply { mkdirs() }
            val target = File(corruptRoot, id)
            if (!target.exists()) atomicMove(dir.toPath(), target.toPath())
        } catch (t: Throwable) {
            // Leave the directory in place; it will keep failing validation.
        }
    }

    private fun atomicMove(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
