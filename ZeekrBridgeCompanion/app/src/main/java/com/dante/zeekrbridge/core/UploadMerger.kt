package com.dante.zeekrbridge.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class MergeOutcome(
    val file: File?,
    val sha256: String?,
    val error: String?,
) {
    companion object {
        const val ERR_INCOMPLETE = "INCOMPLETE"
        const val ERR_INVALID_NAME = "INVALID_NAME"
        const val ERR_SIZE = "SIZE_MISMATCH"
        const val ERR_HASH = "HASH_MISMATCH"
        const val ERR_SHA = "SHA_MISMATCH"
        const val ERR_IO = "IO_ERROR"
    }
}

/**
 * Merges finalized chunks in numeric order into `received/<carId>/<name>.partial`,
 * verifies declared size and SHA-256, then atomically finalizes. On any failure
 * the partial is removed so a bad file is never exposed as a received success.
 */
object UploadMerger {
    fun merge(
        session: UploadSession,
        uploadsRoot: File,
        receivedRoot: File,
        requestedFileName: String?,
    ): MergeOutcome {
        val uploadDir = File(uploadsRoot, session.uploadId)
        val indices = ChunkTools.parseChunkFiles(uploadDir.listFiles())
        if (!ChunkTools.isComplete(indices, session.totalChunks)) {
            return MergeOutcome(null, null, MergeOutcome.ERR_INCOMPLETE)
        }
        val name = PathSafety.cleanFileName(
            requestedFileName?.takeIf { it.isNotBlank() } ?: session.request.fileName,
        ) ?: return MergeOutcome(null, null, MergeOutcome.ERR_INVALID_NAME)
        val carDir = File(receivedRoot, PathSafety.cleanCarId(session.request.carId)).apply { mkdirs() }
        val target = PathSafety.uniqueFile(carDir, name)
        val partial = File(carDir, target.name + ".partial")
        return try {
            FileOutputStream(partial).use { out ->
                indices.forEach { index ->
                    File(uploadDir, "chunk-$index").inputStream().use { it.copyTo(out) }
                }
            }
            if (partial.length() != session.request.sizeBytes) {
                partial.delete()
                return MergeOutcome(null, null, MergeOutcome.ERR_SIZE)
            }
            val sha = StreamingSha256.hash(partial)
            if (sha != session.request.sha256) {
                partial.delete()
                return MergeOutcome(null, sha, MergeOutcome.ERR_HASH)
            }
            try {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (t: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            MergeOutcome(target, sha, null)
        } catch (t: Throwable) {
            try {
                partial.delete()
            } catch (t2: Throwable) {
                // Ignore.
            }
            MergeOutcome(null, null, MergeOutcome.ERR_IO)
        }
    }
}
