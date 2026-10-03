package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only observed boot counters establish a reboot. Wall-clock changes never do. */
@Serializable
internal data class BootObservation(
    val epochMs: Long,
    val elapsedMs: Long,
    val uptimeMs: Long,
    val bootCount: Int?,
    val process: String,
)

@Serializable
internal enum class BootClassification { FIRST_OBSERVATION, CONFIRMED_REBOOT, SAME_BOOT_PROCESS_RESTART, UNKNOWN }

@Serializable
internal enum class BootEvidenceReason {
    NO_PREVIOUS_OBSERVATION, BOOT_COUNT_INCREASED, BOOT_COUNT_UNCHANGED,
    BOOT_COUNT_UNAVAILABLE, BOOT_COUNT_REGRESSED, CLOCK_CONFLICT, SAME_PROCESS, INVALID_OBSERVATION,
    PREVIOUS_CHECKPOINT_UNAVAILABLE,
}

@Serializable
internal data class BootAssessment(val classification: BootClassification, val reason: BootEvidenceReason)

/** Finite, public categories only: no server errors, source paths, device names or transfer IDs. */
@Serializable
internal enum class TransferDiagnosticPhase {
    TASK_SELECTED, CHECKING_CONNECTION, CHECKING_RECEIVER, READING_SOURCE, HASHING,
    PREPARING_DRIVE_DATA, CREATING_UPLOAD, CHECKING_REMOTE_STATUS, SENDING_DRIVE_DATA,
    QUEUED, PREPARING, UPLOADING, COMMITTING, WAITING_RETRY, CANCEL_PENDING,
    COMPLETED, CANCELLED, FAILED, RESTORED_WAITING_RETRY,
}

@Serializable
internal enum class TransferDiagnosticSource { INTERNAL_RECORDING, OPENAVM_USB, FACTORY_SENTRY_USB }

@Serializable
internal data class BootStartEvidence(val observed: BootObservation, val assessment: BootAssessment)

@Serializable
internal data class TransferBoundaryEvidence(
    val observed: BootObservation,
    /** Local ordinal, unique only within this process; the actual task ID is never serialized. */
    val taskOrdinal: Int,
    val source: TransferDiagnosticSource,
    val phase: TransferDiagnosticPhase,
)

@Serializable
internal data class BootTransferJournal(
    val schemaVersion: Int = 1,
    val starts: List<BootStartEvidence> = emptyList(),
    val transfers: List<TransferBoundaryEvidence> = emptyList(),
)

internal object BootTransferEvidence {
    const val MAX_BYTES = 16 * 1024
    const val MAX_STARTS = 4
    const val MAX_TRANSFERS = 16
    private val json = Json { encodeDefaults = true }
    private val processPattern = Regex("[0-9]{1,12}-[0-9]{1,16}")

    fun valid(observed: BootObservation): Boolean = observed.epochMs >= 0 && observed.elapsedMs >= 0 &&
        observed.uptimeMs in 0..observed.elapsedMs && (observed.bootCount == null || observed.bootCount >= 0) &&
        processPattern.matches(observed.process)

    fun classify(previous: BootObservation?, current: BootObservation, previousUnavailable: Boolean = false): BootAssessment {
        fun result(type: BootClassification, reason: BootEvidenceReason) = BootAssessment(type, reason)
        if (!valid(current) || previous?.let { !valid(it) } == true)
            return result(BootClassification.UNKNOWN, BootEvidenceReason.INVALID_OBSERVATION)
        if (previous == null && previousUnavailable)
            return result(BootClassification.UNKNOWN, BootEvidenceReason.PREVIOUS_CHECKPOINT_UNAVAILABLE)
        if (previous == null) return result(BootClassification.FIRST_OBSERVATION, BootEvidenceReason.NO_PREVIOUS_OBSERVATION)
        val oldBoot = previous.bootCount
        val newBoot = current.bootCount
        if (oldBoot == null || newBoot == null)
            return result(BootClassification.UNKNOWN, BootEvidenceReason.BOOT_COUNT_UNAVAILABLE)
        if (newBoot < oldBoot) return result(BootClassification.UNKNOWN, BootEvidenceReason.BOOT_COUNT_REGRESSED)
        if (current.process == previous.process)
            return result(BootClassification.UNKNOWN, BootEvidenceReason.SAME_PROCESS)
        if (newBoot > oldBoot) return result(BootClassification.CONFIRMED_REBOOT, BootEvidenceReason.BOOT_COUNT_INCREASED)
        // Some OEMs expose an unchanging counter. A monotonic-clock rollback makes it inconclusive.
        if (current.elapsedMs < previous.elapsedMs || current.uptimeMs < previous.uptimeMs)
            return result(BootClassification.UNKNOWN, BootEvidenceReason.CLOCK_CONFLICT)
        return result(BootClassification.SAME_BOOT_PROCESS_RESTART, BootEvidenceReason.BOOT_COUNT_UNCHANGED)
    }

    fun start(journal: BootTransferJournal, current: BootObservation, previousUnavailable: Boolean = false): BootTransferJournal {
        require(valid(current))
        val lastStart = journal.starts.lastOrNull()?.observed
        val lastObserved = journal.transfers.asSequence().map { it.observed }.filter {
            lastStart != null && it.process == lastStart.process && it.elapsedMs >= lastStart.elapsedMs
        }.maxByOrNull { it.elapsedMs } ?: lastStart
        val started = BootStartEvidence(current, classify(lastObserved, current, previousUnavailable))
        return journal.copy(starts = (journal.starts + started).takeLast(MAX_STARTS))
    }

    fun boundary(journal: BootTransferJournal, edge: TransferBoundaryEvidence): BootTransferJournal {
        require(valid(edge.observed) && edge.taskOrdinal > 0)
        val last = journal.transfers.lastOrNull()
        if (last?.observed?.process == edge.observed.process && last.taskOrdinal == edge.taskOrdinal &&
            last.phase == edge.phase && last.source == edge.source) return journal
        return journal.copy(transfers = (journal.transfers + edge).takeLast(MAX_TRANSFERS))
    }

    fun encode(journal: BootTransferJournal): ByteArray {
        require(validJournal(journal))
        return json.encodeToString(BootTransferJournal.serializer(), journal).toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): BootTransferJournal? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return runCatching { json.decodeFromString(BootTransferJournal.serializer(), bytes.toString(Charsets.UTF_8)) }
            .getOrNull()?.takeIf(::validJournal)
    }

    private fun validJournal(journal: BootTransferJournal): Boolean = journal.schemaVersion == 1 &&
        journal.starts.size <= MAX_STARTS && journal.transfers.size <= MAX_TRANSFERS &&
        journal.starts.all { valid(it.observed) } && journal.transfers.all { valid(it.observed) && it.taskOrdinal > 0 }
}
