package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.diagnostic.BootTransferDiagnostics
import com.dante.zeekrcapabilitylab.diagnostic.TransferDiagnosticPhase
import com.dante.zeekrcapabilitylab.diagnostic.TransferDiagnosticSource
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState

internal fun transferDiagnosticState(previous: TransferTaskState, next: TransferTaskState): TransferDiagnosticPhase? {
    if (previous == next && next !in setOf(TransferTaskState.WAITING_RETRY, TransferTaskState.CANCEL_PENDING,
            TransferTaskState.COMPLETED, TransferTaskState.CANCELLED, TransferTaskState.FAILED)) return null
    // A future business state must never throw from diagnostic-only code.
    return TransferDiagnosticPhase.entries.firstOrNull { it.name == next.name }
}

/** Explicit allowlist: never forward a TransferTask, path, UUID, phone name or free-text failure. */
internal fun TransferTask.recordDiagnostic(phase: TransferDiagnosticPhase) {
    BootTransferDiagnostics.transfer(id, when (sourceKind) {
        TransferSourceKind.MANAGED_RECORDING -> TransferDiagnosticSource.INTERNAL_RECORDING
        TransferSourceKind.OPENAVM_USB -> TransferDiagnosticSource.OPENAVM_USB
        TransferSourceKind.FACTORY_SENTRY_USB -> TransferDiagnosticSource.FACTORY_SENTRY_USB
    }, phase)
}
