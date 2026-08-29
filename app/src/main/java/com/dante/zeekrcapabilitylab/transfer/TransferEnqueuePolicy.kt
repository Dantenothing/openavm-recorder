package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.TransferTaskState

data class TransferEnqueueDecision(
    val initialState: TransferTaskState,
    val reason: String?,
    val startServiceImmediately: Boolean,
)

/** A user request is durable even when the paired phone is temporarily offline. */
object TransferEnqueuePolicy {
    fun decide(connected: Boolean): TransferEnqueueDecision = if (connected) {
        TransferEnqueueDecision(TransferTaskState.QUEUED, null, true)
    } else {
        TransferEnqueueDecision(
            TransferTaskState.WAITING_RETRY,
            "Waiting for phone connection",
            false,
        )
    }
}

enum class TransferCancelAction {
    FINISH_LOCALLY,
    REMOVE_REMOTE_PARTIAL,
}

object TransferCancelPolicy {
    fun action(uploadId: String?): TransferCancelAction = if (uploadId == null) {
        TransferCancelAction.FINISH_LOCALLY
    } else {
        TransferCancelAction.REMOVE_REMOTE_PARTIAL
    }
}
