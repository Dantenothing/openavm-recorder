package com.dante.zeekrbridge.core

import android.content.Context
import io.github.dantenothing.avmtransfer.protocol.ProtocolValidation
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import io.github.dantenothing.avmtransfer.protocol.UploadCompleteResponse as V2CompleteResponse
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest as V2CreateRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse as V2CreateResponse
import io.github.dantenothing.avmtransfer.protocol.UploadStatusResponse as V2StatusResponse
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReliableStoredUpload(
    val uploadId: String,
    val request: V2CreateRequest,
    val chunkSize: Int,
    val totalChunks: Int,
    val status: String = "UPLOADING",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedFileName: String? = null,
)

sealed interface ReliableCommitResult {
    data class Success(val response: V2CompleteResponse) : ReliableCommitResult
    data class Rejected(val code: Int, val message: String) : ReliableCommitResult
}

/**
 * Persistent receiver state shared by both legacy and OpenAVM protocol adapters.
 * The final MP4 rename is the commit point: cancellation never removes a committed file.
 */
object ReliableUploadStore {
    private const val ORPHAN_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private lateinit var filesRoot: File
    private val locks = ConcurrentHashMap<String, Any>()

    fun init(appContext: Context) = initAt(appContext.applicationContext.filesDir)

    internal fun initForTests(root: File) = initAt(root)

    private fun initAt(root: File) {
        filesRoot = root.apply { mkdirs() }
        uploadsRoot().mkdirs()
        receivedRoot().mkdirs()
        cleanupOrphans()
    }

    fun create(request: V2CreateRequest, authenticatedCarId: String): V2CreateResponse? {
        if (request.carId != authenticatedCarId || !ProtocolValidation.validIdentity(request.carId)) return null
        if (!ProtocolValidation.validIdentity(request.clientTransferId)) return null
        val name = ProtocolValidation.cleanFileName(request.fileName) ?: return null
        val sha = ProtocolValidation.normalizedSha(request.sha256) ?: return null
        if ((request.sidecarJson?.toByteArray()?.size ?: 0) > TransferProtocol.MAX_SIDECAR_BYTES) return null
        val total = ProtocolValidation.totalChunks(request.sizeBytes, TransferProtocol.CHUNK_SIZE) ?: return null
        uploadsRoot().listFiles().orEmpty().filter(File::isDirectory).forEach { dir ->
            val existing = load(dir.name) ?: return@forEach
            if (existing.request.carId == authenticatedCarId && existing.request.clientTransferId == request.clientTransferId) {
                return if (
                    existing.request.sizeBytes == request.sizeBytes &&
                    existing.request.sha256 == sha &&
                    existing.request.fileName == name
                ) {
                    V2CreateResponse(existing.uploadId, existing.chunkSize, existing.totalChunks)
                } else null
            }
        }
        val id = UUID.randomUUID().toString()
        val stored = ReliableStoredUpload(
            uploadId = id,
            request = request.copy(fileName = name, sha256 = sha),
            chunkSize = TransferProtocol.CHUNK_SIZE,
            totalChunks = total,
        )
        writeMetadata(uploadDir(id).apply { mkdirs() }, stored)
        return V2CreateResponse(id, stored.chunkSize, stored.totalChunks)
    }

    fun status(uploadId: String, carId: String): V2StatusResponse? = synchronized(lock(uploadId)) {
        val session = load(uploadId) ?: return null
        if (session.request.carId != carId) return null
        V2StatusResponse(
            uploadId = uploadId,
            receivedChunks = chunks(uploadDir(uploadId)),
            totalChunks = session.totalChunks,
            status = session.status,
            chunkSize = session.chunkSize,
            sha256 = session.request.sha256,
        )
    }

    fun isLegacy(uploadId: String, carId: String): Boolean? = synchronized(lock(uploadId)) {
        val session = load(uploadId) ?: return null
        if (session.request.carId != carId) return null
        session.request.clientTransferId.startsWith("legacy-")
    }

    fun storeChunk(uploadId: String, carId: String, index: Int, bytes: ByteArray): Boolean =
        synchronized(lock(uploadId)) {
            val session = load(uploadId) ?: return false
            if (session.request.carId != carId || session.status != "UPLOADING") return false
            val expected = ProtocolValidation.expectedChunkSize(
                session.request.sizeBytes,
                session.chunkSize,
                session.totalChunks,
                index,
            ) ?: return false
            if (bytes.size != expected) return false
            val dir = uploadDir(uploadId)
            val partial = File(dir, "chunk-$index.partial")
            val final = File(dir, "chunk-$index")
            FileOutputStream(partial).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            moveReplacing(partial, final)
            writeMetadata(dir, session.copy(updatedAt = System.currentTimeMillis()))
            true
        }

    fun complete(
        uploadId: String,
        carId: String,
        requestedSha: String,
        requestedName: String,
    ): ReliableCommitResult = synchronized(lock(uploadId)) {
        var session = load(uploadId) ?: return ReliableCommitResult.Rejected(404, "unknown upload")
        if (session.request.carId != carId) return ReliableCommitResult.Rejected(403, "forbidden")
        val sha = ProtocolValidation.normalizedSha(requestedSha)
        if (sha == null || sha != session.request.sha256) {
            return ReliableCommitResult.Rejected(400, "sha256 mismatch")
        }
        if (session.status == "COMPLETED") {
            val name = session.completedFileName
                ?: return ReliableCommitResult.Rejected(500, "completed file missing")
            val existing = File(receivedRoot(), name)
            if (!existing.isFile) return ReliableCommitResult.Rejected(500, "completed file missing")
            return ReliableCommitResult.Success(V2CompleteResponse(uploadId, true, sha, name))
        }
        val indices = chunks(uploadDir(uploadId))
        if (indices != (0 until session.totalChunks).toList()) {
            return ReliableCommitResult.Rejected(409, "upload incomplete")
        }
        val cleanName = ProtocolValidation.cleanFileName(requestedName)
            ?: return ReliableCommitResult.Rejected(400, "invalid file name")
        if (session.status !in setOf("UPLOADING", "COMMITTING")) {
            return ReliableCommitResult.Rejected(409, "upload not active")
        }
        if (session.status == "UPLOADING") {
            session = session.copy(
                status = "COMMITTING",
                updatedAt = System.currentTimeMillis(),
                completedFileName = uniqueTarget(cleanName).name,
            )
            writeMetadata(uploadDir(uploadId), session)
        }
        val targetName = session.completedFileName
            ?: return ReliableCommitResult.Rejected(500, "commit target missing")
        val target = File(receivedRoot(), targetName)
        val partial = File(receivedRoot(), "$targetName.partial")
        try {
            if (!target.isFile) {
                FileOutputStream(partial).use { out ->
                    indices.forEach { i ->
                        File(uploadDir(uploadId), "chunk-$i").inputStream().use { it.copyTo(out) }
                    }
                    out.fd.sync()
                }
                if (partial.length() != session.request.sizeBytes) {
                    partial.delete()
                    return ReliableCommitResult.Rejected(409, "size mismatch")
                }
                if (sha256(partial) != sha) {
                    partial.delete()
                    return ReliableCommitResult.Rejected(409, "hash mismatch")
                }
                session.request.sidecarJson?.let { sidecar ->
                    val sidecarTarget = File(target.parentFile, target.nameWithoutExtension + ".json")
                    val sidecarTmp = File(sidecarTarget.absolutePath + ".partial")
                    sidecarTmp.writeText(sidecar)
                    moveReplacing(sidecarTmp, sidecarTarget)
                }
                moveWithoutReplace(partial, target)
            }
            if (target.length() != session.request.sizeBytes || sha256(target) != sha) {
                return ReliableCommitResult.Rejected(500, "reserved target verification failed")
            }
            writeMetadata(
                uploadDir(uploadId),
                session.copy(
                    status = "COMPLETED",
                    updatedAt = System.currentTimeMillis(),
                    completedFileName = target.name,
                ),
            )
            cleanupCompletedChunks(uploadDir(uploadId))
            ReliableCommitResult.Success(V2CompleteResponse(uploadId, true, sha, target.name))
        } catch (t: Throwable) {
            partial.delete()
            ReliableCommitResult.Rejected(500, t.message ?: "commit failed")
        }
    }

    /** Returns false when ownership differs or the final commit already won the race. */
    fun cancel(uploadId: String, carId: String): Boolean = synchronized(lock(uploadId)) {
        val session = load(uploadId) ?: return true
        if (session.request.carId != carId || session.status == "COMPLETED") return false
        if (session.status == "COMMITTING") {
            val target = session.completedFileName?.let { File(receivedRoot(), it) }
            if (
                target?.isFile == true &&
                target.length() == session.request.sizeBytes &&
                sha256(target) == session.request.sha256
            ) {
                writeMetadata(
                    uploadDir(uploadId),
                    session.copy(status = "COMPLETED", updatedAt = System.currentTimeMillis()),
                )
                cleanupCompletedChunks(uploadDir(uploadId))
                return false
            }
            target?.let {
                File(it.absolutePath + ".partial").delete()
                File(it.parentFile, it.nameWithoutExtension + ".json.partial").delete()
                File(it.parentFile, it.nameWithoutExtension + ".json").delete()
            }
        }
        uploadDir(uploadId).deleteRecursively()
        true
    }

    private fun cleanupOrphans() {
        val cutoff = System.currentTimeMillis() - ORPHAN_TTL_MS
        uploadsRoot().listFiles().orEmpty().filter(File::isDirectory).forEach { dir ->
            val session = load(dir.name)
            if (session == null || (session.status != "COMPLETED" && session.updatedAt < cutoff)) {
                dir.deleteRecursively()
            } else if (session.status == "COMPLETED") {
                cleanupCompletedChunks(dir)
            }
        }
        receivedRoot().listFiles().orEmpty().filter { it.name.endsWith(".partial") }.forEach(File::delete)
    }

    private fun load(id: String): ReliableStoredUpload? {
        if (!ProtocolValidation.validIdentity(id)) return null
        return try {
            json.decodeFromString(
                ReliableStoredUpload.serializer(),
                File(uploadDir(id), "metadata.json").readText(),
            ).takeIf { it.uploadId == id }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeMetadata(dir: File, session: ReliableStoredUpload) {
        val tmp = File(dir, "metadata.json.tmp")
        tmp.writeText(json.encodeToString(ReliableStoredUpload.serializer(), session))
        moveReplacing(tmp, File(dir, "metadata.json"))
    }

    private fun chunks(dir: File): List<Int> = dir.listFiles().orEmpty().mapNotNull {
        Regex("^chunk-([0-9]+)$").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
    }.distinct().sorted()

    private fun cleanupCompletedChunks(dir: File) {
        dir.listFiles().orEmpty().filter { it.name.startsWith("chunk-") }.forEach(File::delete)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueTarget(name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var result = File(receivedRoot(), name)
        var suffix = 1
        while (result.exists() || File(result.absolutePath + ".partial").exists()) {
            result = File(receivedRoot(), "$base-$suffix$ext")
            suffix++
        }
        return result
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun moveWithoutReplace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun lock(id: String) = locks.computeIfAbsent(id) { Any() }
    private fun uploadsRoot() = File(filesRoot, "uploads")
    private fun uploadDir(id: String) = File(uploadsRoot(), id)
    private fun receivedRoot() = File(filesRoot, "received")
}
