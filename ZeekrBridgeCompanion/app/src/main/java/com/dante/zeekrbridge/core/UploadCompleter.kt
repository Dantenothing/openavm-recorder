package com.dante.zeekrbridge.core

import java.io.File

/**
 * Complete-flow guard shared by HTTP and Bluetooth. The requested SHA-256 is
 * validated *before* any merge/finalize; already-completed sessions replay the
 * stored result instead of merging again, so a lost success response can never
 * produce a duplicate received file.
 */
object UploadCompleter {
    private val completionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Complete-flow guard shared by HTTP and Bluetooth. The per-uploadId lock
     * makes completion idempotent under concurrency: the latest persisted
     * session is re-read inside the lock, so two parallel completes can never
     * both merge and produce duplicate received files.
     */
    fun completeForUpload(
        uploadId: String,
        requestedSha: String?,
        fileName: String?,
        loadSession: (String) -> UploadSession?,
        uploadsRoot: File,
        receivedRoot: File,
    ): MergeOutcome {
        val lock = completionLocks.computeIfAbsent(uploadId) { Any() }
        return synchronized(lock) {
            val session = loadSession(uploadId)
                ?: return MergeOutcome(null, null, "SESSION_MISSING")
            complete(session, requestedSha, fileName, uploadsRoot, receivedRoot)
        }
    }

    fun complete(
        session: UploadSession,
        requestedSha: String?,
        fileName: String?,
        uploadsRoot: File,
        receivedRoot: File,
    ): MergeOutcome {
        val sha = ShaHex.normalize(requestedSha)
        if (sha == null || sha != session.request.sha256) {
            return MergeOutcome(null, null, MergeOutcome.ERR_SHA)
        }
        if (session.status == "COMPLETED") {
            val file = session.completedPath?.let { File(it) }
            return if (file != null && file.isFile && session.completedSha256 != null) {
                MergeOutcome(file, session.completedSha256, null)
            } else {
                MergeOutcome(null, null, MergeOutcome.ERR_IO)
            }
        }
        val outcome = UploadMerger.merge(session, uploadsRoot, receivedRoot, fileName)
        if (outcome.file != null && outcome.sha256 != null) {
            val persisted = try {
                UploadSessionStore.markCompleted(session, outcome.file.absolutePath, outcome.sha256)
                true
            } catch (t: Throwable) {
                false
            }
            if (!persisted) {
                outcome.file.delete()
                return MergeOutcome(null, null, MergeOutcome.ERR_IO)
            }
        }
        return outcome
    }
}
