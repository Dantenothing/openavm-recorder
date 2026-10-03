package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse

/** A persisted cancel/terminal state is an admission fence, not merely a UI label. */
internal object TransferCancellationPolicy {
    private val terminal = setOf(TransferTaskState.COMPLETED, TransferTaskState.CANCELLED, TransferTaskState.FAILED)
    fun canUpload(state: TransferTaskState) = state !in terminal && state != TransferTaskState.CANCEL_PENDING

    fun requestAllowed(task: TransferTask, method: String, path: String): Boolean {
        if (canUpload(task.state)) return true
        // Only a known remote session may be inspected or deleted while resolving cancellation.
        val uploadId = task.uploadId ?: return false
        return method in setOf("GET", "DELETE") && path == "/api/uploads/" + uploadId
    }

    fun admit(lock: Any, current: () -> TransferTask?, method: String, path: String, register: () -> Unit): Boolean =
        synchronized(lock) {
            val task = current() ?: return@synchronized false
            if (!requestAllowed(task, method, path)) return@synchronized false
            register()
            true
        }

    fun workerUpdate(current: TransferTask, proposed: TransferTask): TransferTask? {
        if (current.id != proposed.id || current.state in terminal || proposed.state in terminal) return null
        if (current.state == TransferTaskState.CANCEL_PENDING) {
            return if (proposed.state == TransferTaskState.CANCEL_PENDING) current.copy(reason = proposed.reason) else null
        }
        // These values are persisted by dedicated snapshot/create paths. A stale progress copy must
        // not remove them or alter the user's frozen inclusion choice.
        return current.copy(state = proposed.state, reason = proposed.reason,
            uploadedChunks = proposed.uploadedChunks, sha256 = current.sha256 ?: proposed.sha256)
    }

    fun canFinish(current: TransferTaskState, result: TransferTaskState): Boolean =
        current !in terminal && result in terminal &&
            (current != TransferTaskState.CANCEL_PENDING || result in setOf(TransferTaskState.CANCELLED, TransferTaskState.COMPLETED))

    fun created(current: TransferTask, response: UploadCreateResponse): TransferTask {
        if (current.state == TransferTaskState.COMPLETED || current.uploadId != null) return current
        return current.copy(uploadId = response.uploadId, chunkSize = response.chunkSize, totalChunks = response.totalChunks,
            // A late successful create learns only the cleanup handle; it never resumes upload.
            state = if (current.state == TransferTaskState.CANCELLED) TransferTaskState.CANCEL_PENDING else current.state)
    }
}
