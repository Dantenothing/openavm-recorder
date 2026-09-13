package com.dante.zeekrcapabilitylab.sentry

import kotlinx.serialization.Serializable

@Serializable
enum class UsbCanaryPhase { INTENDED, BOUND, MUXING, MUX_STOPPED, SYNCED, VERIFIED, PUBLISHED, CLEANED }
@Serializable
data class UsbCanaryRecord(
    val token: String,
    val collectionUri: String,
    val ownerPackage: String,
    val itemUri: String? = null,
    val phase: UsbCanaryPhase = UsbCanaryPhase.INTENDED,
) {
    init { require(token.matches(Regex("[0-9a-f-]{36}"))) }
    val displayName get() = "OpenAVM_SentryCanary_$token.mp4"
    fun owns(observed: UsbCanaryMetadata): Boolean = itemUri != null && observed.itemUri == itemUri &&
        itemUri.startsWith("$collectionUri/") && itemUri.removePrefix("$collectionUri/").matches(Regex("[0-9]+")) &&
        observed.displayName == displayName && observed.relativePath == RELATIVE_PATH && observed.ownerPackage == ownerPackage
    companion object { const val RELATIVE_PATH = "Download/OpenAVM/SentryCanary/" }
}
data class UsbCanaryMetadata(val itemUri: String, val displayName: String?, val relativePath: String?, val ownerPackage: String?)
@Serializable
data class UsbCanaryVerification(val decodable: Boolean, val samples: Int, val bytes: Long, val sha256: String,
    val longestWriteSampleMs: Long = 0)
@Serializable
data class UsbCanaryResult(
    val phase: UsbCanaryPhase,
    val passed: Boolean = false,
    val reason: String,
    val stopDurationMs: Long? = null,
    val verification: UsbCanaryVerification? = null,
)

interface UsbCanaryJournal {
    fun read(): UsbCanaryRecord?
    /** Must be durable before returning. */
    fun write(record: UsbCanaryRecord)
}
interface UsbCanaryMuxSession {
    fun writeTriggeredSamples()
    fun stopAndRelease(): Long
    fun syncAndClose()
    fun abortClose()
}
interface UsbCanaryBackend {
    fun insertPending(record: UsbCanaryRecord): String
    fun inspect(uri: String): UsbCanaryMetadata?
    fun openMux(uri: String): UsbCanaryMuxSession
    fun verify(uri: String): UsbCanaryVerification
    fun publish(uri: String)
    fun delete(uri: String)
}

/** Pure S1C transaction and recovery rules; deliberately no scan/discovery API. */
class UsbCanaryCommit(private val journal: UsbCanaryJournal, private val backend: UsbCanaryBackend) {
    fun run(intended: UsbCanaryRecord): UsbCanaryResult {
        check(journal.read()?.phase.let { it == null || it == UsbCanaryPhase.CLEANED }) { "CLEAN_PREVIOUS_USB_CANARY_FIRST" }
        require(intended.phase == UsbCanaryPhase.INTENDED && intended.itemUri == null)
        var record = intended
        var handle: UsbCanaryMuxSession? = null
        var stopMs: Long? = null
        try {
            journal.write(record)
            val uri = backend.insertPending(record)
            // If this durable bind fails, no mutation or cleanup of the unbound URI is authorized.
            val bound = record.copy(itemUri = uri, phase = UsbCanaryPhase.BOUND)
            journal.write(bound)
            record = bound
            requireOwned(record)
            handle = backend.openMux(uri)
            record = record.copy(phase = UsbCanaryPhase.MUXING).also(journal::write)
            handle.writeTriggeredSamples()
            stopMs = handle.stopAndRelease()
            record = record.copy(phase = UsbCanaryPhase.MUX_STOPPED).also(journal::write)
            handle.syncAndClose()
            handle = null
            record = record.copy(phase = UsbCanaryPhase.SYNCED).also(journal::write)
            requireOwned(record)
            val verified = backend.verify(uri)
            check(verified.decodable && verified.samples > 1 && verified.bytes > 0) { "USB_VERIFICATION_FAILED" }
            record = record.copy(phase = UsbCanaryPhase.VERIFIED).also(journal::write)
            requireOwned(record)
            backend.publish(uri)
            record = record.copy(phase = UsbCanaryPhase.PUBLISHED).also(journal::write)
            val latencyOkay = stopMs <= 5_000 && verified.longestWriteSampleMs <= 500
            return UsbCanaryResult(record.phase, latencyOkay,
                if (latencyOkay) "USB_FD_CANARY_VERIFIED" else "USB_LATENCY_GATE_NOT_MET", stopMs, verified)
        } catch (error: Exception) {
            // The current MP4 is untrusted until an exact-URI recovery probe verifies it.
            return UsbCanaryResult(record.phase, reason = safeReason(error), stopDurationMs = stopMs)
        } finally { runCatching { handle?.abortClose() } }
    }

    fun recover(): UsbCanaryResult {
        val record = journal.read() ?: return UsbCanaryResult(UsbCanaryPhase.CLEANED, reason = "NO_PENDING_CANARY")
        if (record.phase == UsbCanaryPhase.CLEANED) return UsbCanaryResult(record.phase, reason = "ALREADY_CLEANED")
        if (record.itemUri == null) return UsbCanaryResult(record.phase, reason = "UNBOUND_INSERT_NO_SCAN_AUTHORITY")
        return try {
            requireOwned(record)
            val verified = backend.verify(record.itemUri)
            UsbCanaryResult(record.phase, reason = if (verified.decodable) "VERIFIED_PARTIAL_RETAINED" else "UNPLAYABLE_PENDING_RETAINED",
                verification = verified)
        } catch (error: Exception) { UsbCanaryResult(record.phase, reason = safeReason(error)) }
    }

    /** User-requested cleanup of this single journalled canary, never a volume scan. */
    fun cleanup(): UsbCanaryResult {
        val record = journal.read() ?: return UsbCanaryResult(UsbCanaryPhase.CLEANED, reason = "NOTHING_TO_CLEAN")
        if (record.phase == UsbCanaryPhase.CLEANED) return UsbCanaryResult(record.phase, reason = "ALREADY_CLEANED")
        if (record.itemUri == null) return UsbCanaryResult(record.phase, reason = "UNBOUND_INSERT_NO_SCAN_AUTHORITY")
        return try {
            requireOwned(record)
            backend.delete(record.itemUri)
            journal.write(record.copy(phase = UsbCanaryPhase.CLEANED))
            UsbCanaryResult(UsbCanaryPhase.CLEANED, reason = "EXACT_CANARY_CLEANED")
        } catch (error: Exception) { UsbCanaryResult(record.phase, reason = safeReason(error)) }
    }

    private fun requireOwned(record: UsbCanaryRecord) {
        val observed = record.itemUri?.let(backend::inspect) ?: error("OWNED_URI_UNAVAILABLE")
        check(record.owns(observed)) { "OWNED_URI_IDENTITY_MISMATCH" }
    }
    private fun safeReason(error: Exception) = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
        ?: "USB_${error.javaClass.simpleName.uppercase()}"
}
