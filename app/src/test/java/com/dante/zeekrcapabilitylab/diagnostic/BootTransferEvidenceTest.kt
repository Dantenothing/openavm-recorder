package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BootTransferEvidenceTest {
    private fun observed(boot: Int? = 8, elapsed: Long = 1000, uptime: Long = 500,
                         epoch: Long = 100_000, process: String = "123-100000") =
        BootObservation(epoch, elapsed, uptime, boot, process)
    private fun phase(observation: BootObservation, value: TransferDiagnosticPhase, ordinal: Int = 1) =
        TransferBoundaryEvidence(observation, ordinal, TransferDiagnosticSource.OPENAVM_USB, value)

    @Test fun firstObservationDoesNotClaimThatABootOccurred() {
        val result = BootTransferEvidence.classify(null, observed())
        assertEquals(BootClassification.FIRST_OBSERVATION, result.classification)
        assertEquals(BootEvidenceReason.NO_PREVIOUS_OBSERVATION, result.reason)
    }

    @Test fun sameCounterAndForwardMonotonicClocksIdentifyOnlyProcessRestart() {
        val result = BootTransferEvidence.classify(observed(), observed(elapsed = 3000, uptime = 700, process = "456-200000"))
        assertEquals(BootClassification.SAME_BOOT_PROCESS_RESTART, result.classification)
    }

    @Test fun increasedReadableCounterConfirmsRebootEvenIfNewUptimeIsAlreadyLonger() {
        listOf(10L to 5L, 9000L to 3000L).forEach { (elapsed, uptime) ->
            val result = BootTransferEvidence.classify(observed(), observed(boot = 9, elapsed = elapsed,
                uptime = uptime, process = "456-200000"))
            assertEquals(BootClassification.CONFIRMED_REBOOT, result.classification)
            assertEquals(BootEvidenceReason.BOOT_COUNT_INCREASED, result.reason)
        }
    }

    @Test fun unavailableCounterNeverFallsBackToWallClockOrUptimeGuessing() {
        for ((old, next) in listOf(null to 9, 8 to null, null to null)) {
            val result = BootTransferEvidence.classify(observed(boot = old), observed(boot = next,
                elapsed = 10, uptime = 5, epoch = 900_000, process = "456-900000"))
            assertEquals(BootClassification.UNKNOWN, result.classification)
            assertEquals(BootEvidenceReason.BOOT_COUNT_UNAVAILABLE, result.reason)
        }
    }

    @Test fun wallClockCorrectionIsNotEvidenceOfReboot() {
        val result = BootTransferEvidence.classify(observed(epoch = 1_000_000),
            observed(elapsed = 5000, uptime = 1000, epoch = 100, process = "456-100"))
        assertEquals(BootClassification.SAME_BOOT_PROCESS_RESTART, result.classification)
    }

    @Test fun regressedCounterAndConflictingMonotonicClocksRemainUnknown() {
        assertEquals(BootEvidenceReason.BOOT_COUNT_REGRESSED, BootTransferEvidence.classify(observed(),
            observed(boot = 7, process = "456-200000")).reason)
        assertEquals(BootEvidenceReason.CLOCK_CONFLICT, BootTransferEvidence.classify(observed(),
            observed(elapsed = 10, uptime = 5, process = "456-200000")).reason)
        assertEquals(BootEvidenceReason.CLOCK_CONFLICT, BootTransferEvidence.classify(observed(),
            observed(elapsed = 2000, uptime = 10, process = "456-200000")).reason)
    }

    @Test fun corruptedPreviousCheckpointDoesNotPretendItIsAFirstInstall() {
        assertEquals(BootClassification.UNKNOWN, BootTransferEvidence.classify(null, observed(), previousUnavailable = true).classification)
        assertEquals(BootEvidenceReason.PREVIOUS_CHECKPOINT_UNAVAILABLE,
            BootTransferEvidence.start(BootTransferJournal(), observed(), previousUnavailable = true).starts.single().assessment.reason)
    }

    @Test fun lastTransferCheckpointParticipatesInMonotonicRollbackCheck() {
        var journal = BootTransferEvidence.start(BootTransferJournal(), observed())
        journal = BootTransferEvidence.boundary(journal, phase(observed(elapsed = 10_000, uptime = 8000), TransferDiagnosticPhase.UPLOADING))
        val restarted = BootTransferEvidence.start(journal, observed(elapsed = 4000, uptime = 1000, process = "456-200000"))
        assertEquals(BootEvidenceReason.CLOCK_CONFLICT, restarted.starts.last().assessment.reason)
        assertEquals(TransferDiagnosticPhase.UPLOADING, restarted.transfers.single().phase)
        // A missing terminal boundary is retained as missing, never converted into FAILED or COMPLETED.
        assertEquals(1, restarted.transfers.size)
    }

    @Test fun repeatedChunkProgressDoesNotGrowTheJournalAndTerminalOutcomesSurviveRoundTrip() {
        var journal = BootTransferEvidence.start(BootTransferJournal(), observed())
        repeat(50) { journal = BootTransferEvidence.boundary(journal,
            phase(observed(elapsed = 1000 + it.toLong()), TransferDiagnosticPhase.UPLOADING)) }
        assertEquals(1, journal.transfers.size)
        journal = BootTransferEvidence.boundary(journal, phase(observed(elapsed = 3000), TransferDiagnosticPhase.COMPLETED))
        val parsed = BootTransferEvidence.decode(BootTransferEvidence.encode(journal))!!
        assertEquals(listOf(TransferDiagnosticPhase.UPLOADING, TransferDiagnosticPhase.COMPLETED), parsed.transfers.map { it.phase })
    }

    @Test fun retryReturningToSameWaitingStateRetainsTheNewObservedOutcome() {
        var journal = BootTransferEvidence.start(BootTransferJournal(), observed())
        for (value in listOf(TransferDiagnosticPhase.WAITING_RETRY, TransferDiagnosticPhase.CHECKING_CONNECTION,
            TransferDiagnosticPhase.WAITING_RETRY, TransferDiagnosticPhase.WAITING_RETRY)) {
            journal = BootTransferEvidence.boundary(journal, phase(observed(), value))
        }
        assertEquals(listOf(TransferDiagnosticPhase.WAITING_RETRY, TransferDiagnosticPhase.CHECKING_CONNECTION,
            TransferDiagnosticPhase.WAITING_RETRY), journal.transfers.map { it.phase })
    }

    @Test fun boundedHistoryRetainsNewestProcessAndTransferWithoutAnyPrivatePayloadFields() {
        var journal = BootTransferJournal()
        repeat(10) { index -> journal = BootTransferEvidence.start(journal,
            observed(boot = index, process = "123-${100000 + index}")) }
        repeat(100) { index -> journal = BootTransferEvidence.boundary(journal,
            phase(journal.starts.last().observed.copy(elapsedMs = 2000L + index), TransferDiagnosticPhase.HASHING, index + 1)) }
        assertEquals(4, journal.starts.size); assertEquals(16, journal.transfers.size)
        assertEquals(100, journal.transfers.last().taskOrdinal)
        val bytes = BootTransferEvidence.encode(journal)
        assertTrue(bytes.size <= BootTransferEvidence.MAX_BYTES)
        val record = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["transfers"]!!.jsonArray.first().jsonObject
        assertEquals(setOf("observed", "taskOrdinal", "source", "phase"), record.keys)
        assertEquals(journal, BootTransferEvidence.decode(bytes))
    }

    @Test fun invalidAndOversizedCheckpointsCannotEstablishBootEvidence() {
        assertNull(BootTransferEvidence.decode(ByteArray(BootTransferEvidence.MAX_BYTES + 1)))
        assertNull(BootTransferEvidence.decode("{broken".toByteArray()))
        assertNull(BootTransferEvidence.decode("{\"schemaVersion\":1,\"account\":\"secret\"}".toByteArray()))
        assertEquals(BootEvidenceReason.INVALID_OBSERVATION,
            BootTransferEvidence.classify(null, observed(uptime = 2000)).reason)
        assertEquals(BootEvidenceReason.INVALID_OBSERVATION,
            BootTransferEvidence.classify(null, observed(process = "https://private/token")).reason)
        assertEquals(BootEvidenceReason.SAME_PROCESS, BootTransferEvidence.classify(observed(), observed()).reason)
    }

    @Test fun compactReportRetainsStartupAndTransferWhenOldFaultDetailsAreTrimmed() {
        val boot = buildJsonObject { put("classification", "CONFIRMED_REBOOT") }
        val huge = buildJsonObject { put("recentEvents", "x".repeat(40_000)) }
        val text = ShortRecorderReport.report("test", 1, 32, huge, huge, startupAndTransfer = boot)
        assertTrue(text.toByteArray().size <= ShortRecorderReport.MAX_BYTES)
        assertEquals(boot, Json.parseToJsonElement(text).jsonObject["startupAndTransfer"])
    }
}
