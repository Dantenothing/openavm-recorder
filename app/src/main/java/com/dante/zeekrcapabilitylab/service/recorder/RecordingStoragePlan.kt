package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAssetKind
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RecordingStoragePreference { USB_PREFERRED, INTERNAL_ONLY }

@Serializable
enum class RecordingStorageKind { INTERNAL, USB_MEDIASTORE }

@Serializable
data class RecordingStorageIdentity(
    val kind: RecordingStorageKind,
    val storageUuid: String? = null,
    val volumeName: String? = null,
    val description: String? = null,
)

/** Frozen once a manual recording session starts; no silent target switching is allowed. */
@Serializable
data class RecordingSessionStoragePlan(
    val preference: RecordingStoragePreference,
    val active: RecordingStorageIdentity,
    val usbQuotaBytes: Long,
    val fallbackConsumed: Boolean = false,
) {
    fun fallbackToInternal(): RecordingSessionStoragePlan? {
        if (active.kind != RecordingStorageKind.USB_MEDIASTORE || fallbackConsumed) return null
        return copy(
            active = RecordingStorageIdentity(RecordingStorageKind.INTERNAL),
            fallbackConsumed = true,
        )
    }
}

data class UsbQuotaAdmission(
    val admitted: Boolean,
    val bytesToReclaim: Long,
    val reason: String? = null,
)

/** Pure admission math; verified segment reclamation is performed by the USB retention manager. */
object UsbRecordingQuotaPolicy {
    const val DEFAULT_QUOTA_BYTES = 30L * 1024L * 1024L * 1024L
    const val MIN_CUSTOM_QUOTA_BYTES = 5L * 1024L * 1024L * 1024L
    const val MAX_CUSTOM_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L * 1024L
    const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 10L * 1024L * 1024L * 1024L

    fun isValidQuota(bytes: Long): Boolean = bytes in MIN_CUSTOM_QUOTA_BYTES..MAX_CUSTOM_QUOTA_BYTES

    fun admission(
        namespaceBytes: Long,
        freeBytes: Long,
        incomingBytes: Long,
        quotaBytes: Long,
        reserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    ): UsbQuotaAdmission {
        if (listOf(namespaceBytes, freeBytes, incomingBytes, quotaBytes, reserveBytes).any { it < 0L }) {
            return UsbQuotaAdmission(false, 0L, "NEGATIVE_INPUT")
        }
        if (!isValidQuota(quotaBytes)) return UsbQuotaAdmission(false, 0L, "INVALID_QUOTA")
        if (incomingBytes > quotaBytes) return UsbQuotaAdmission(false, 0L, "SEGMENT_EXCEEDS_QUOTA")
        val quotaReclaim = (namespaceBytes + incomingBytes - quotaBytes).coerceAtLeast(0L)
        val freeSpaceReclaim = (incomingBytes + reserveBytes - freeBytes).coerceAtLeast(0L)
        return UsbQuotaAdmission(true, maxOf(quotaReclaim, freeSpaceReclaim))
    }
}

@Serializable
data class UsbPendingRecordingAsset(
    val kind: UsbExportAssetKind,
    val itemUri: String,
    val requestedName: String,
    val expectedMimeType: String,
    val observedOwnerPackage: String? = null,
)

@Serializable
data class UsbPendingRecordingOutput(
    val operationId: String,
    val recordingSessionId: String,
    val segmentNumber: Int,
    val storageUuid: String,
    val volumeName: String,
    val itemUri: String,
    val requestedName: String,
    val observedOwnerPackage: String? = null,
    val createdAtEpochMs: Long,
    val bundleId: String? = null,
    val assets: List<UsbPendingRecordingAsset> = emptyList(),
)

/** Internal recovery ledger for C1B pending MediaStore items. It never touches USB itself. */
class UsbRecordingRecoveryJournal(context: Context) {
    private val file = File(context.applicationContext.filesDir, "recordings/usb-pending.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun entries(): List<UsbPendingRecordingOutput> = synchronized(LOCK) { read().entries }

    fun put(entry: UsbPendingRecordingOutput) = synchronized(LOCK) {
        val current = read()
        write(current.copy(entries = current.entries.filterNot { it.operationId == entry.operationId } + entry))
    }

    fun addAsset(operationId: String, asset: UsbPendingRecordingAsset): Boolean = synchronized(LOCK) {
        val current = read()
        var matched = false
        val updated = current.entries.map { entry ->
            if (entry.operationId != operationId) return@map entry
            matched = true
            entry.copy(assets = (entry.assets + asset).distinctBy { it.itemUri })
        }
        if (matched) write(current.copy(entries = updated))
        matched
    }

    fun remove(operationId: String) = synchronized(LOCK) {
        write(UsbPendingRecordingLedger(entries = read().entries.filterNot { it.operationId == operationId }))
    }

    private fun read(): UsbPendingRecordingLedger {
        if (!file.isFile) return UsbPendingRecordingLedger()
        return runCatching { json.decodeFromString<UsbPendingRecordingLedger>(file.readText()) }
            .getOrDefault(UsbPendingRecordingLedger())
    }

    private fun write(value: UsbPendingRecordingLedger) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeText(json.encodeToString(value))
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }

    companion object {
        private val LOCK = Any()
    }

    @Serializable
    private data class UsbPendingRecordingLedger(
        val schemaVersion: Int = 2,
        val entries: List<UsbPendingRecordingOutput> = emptyList(),
    )
}
