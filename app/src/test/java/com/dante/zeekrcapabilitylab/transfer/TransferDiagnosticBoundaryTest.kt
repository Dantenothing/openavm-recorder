package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.diagnostic.TransferDiagnosticPhase
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import org.junit.Assert.*
import org.junit.Test

class TransferDiagnosticBoundaryTest {
    @Test fun everyCurrentBusinessStateHasAnExplicitDiagnosticCategory() {
        TransferTaskState.entries.forEach { next ->
            val different = TransferTaskState.entries.first { it != next }
            assertEquals(next.name, transferDiagnosticState(different, next)?.name)
        }
    }

    @Test fun sameStateRetryAndTerminalOutcomesRemainObservableButChunkProgressDoesNot() {
        for (state in listOf(TransferTaskState.WAITING_RETRY, TransferTaskState.CANCEL_PENDING,
            TransferTaskState.COMPLETED, TransferTaskState.CANCELLED, TransferTaskState.FAILED)) {
            assertEquals(state.name, transferDiagnosticState(state, state)?.name)
        }
        for (state in listOf(TransferTaskState.PREPARING, TransferTaskState.UPLOADING, TransferTaskState.COMMITTING, TransferTaskState.QUEUED)) {
            assertNull(transferDiagnosticState(state, state))
        }
        assertEquals(TransferDiagnosticPhase.COMMITTING,
            transferDiagnosticState(TransferTaskState.UPLOADING, TransferTaskState.COMMITTING))
    }
}
