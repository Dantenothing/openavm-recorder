package com.dante.zeekrcapabilitylab.usbexport

import kotlinx.serialization.Serializable

@Serializable
enum class UsbExportState {
    QUEUED,
    WAITING_FOR_TARGET,
    PREPARING_SOURCE,
    COPYING,
    VERIFYING,
    PUBLISHING,
    CLEANING_UP,
    CANCEL_REQUESTED,
    COMPLETED,
    ALREADY_EXPORTED,
    FAILED_RECOVERABLE,
    FAILED,
    CANCELLED,
}

@Serializable
enum class UsbExportAssetKind { VIDEO, SIDECAR, MANIFEST }

@Serializable
enum class UsbExportBundleFormat { LEGACY_EXPORT, SEGMENT_BUNDLE }


@Serializable
enum class OpenAvmOwnedUnitKind { SEGMENT_BUNDLE, LEGACY_EXPORT }

/** Stable ownership reference only; deletion must resolve it against a fresh USB catalog. */
@Serializable
data class OpenAvmOwnedUnitRef(
    val kind: OpenAvmOwnedUnitKind,
    val id: String,
)
@Serializable
enum class UsbSegmentSourceKind { DIRECT_RECORDING, MANUAL_EXPORT }

@Serializable
data class UsbExportTarget(
    val volumeName: String,
    val storageUuid: String,
    val description: String,
    val directoryPath: String? = null,
    val collectionUri: String,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
)

@Serializable
data class UsbExportSourceSnapshot(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val segmentNumber: Int,
    val sidecarFileName: String,
    val sidecarJson: String,
    val sourceSha256: String? = null,
    val sidecarSha256: String? = null,
)

@Serializable
data class UsbExportAsset(
    val kind: UsbExportAssetKind,
    val segmentNumber: Int? = null,
    val requestedName: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val expectedMimeType: String? = null,
    val itemUri: String? = null,
    val observedName: String? = null,
    val observedRelativePath: String? = null,
    val observedVolumeName: String? = null,
    val observedSizeBytes: Long? = null,
    val observedMimeType: String? = null,
    val observedPending: Int? = null,
    val observedOwnerPackage: String? = null,
    val rereadBytes: Long? = null,
    val rereadSha256: String? = null,
    val verificationFailures: List<String> = emptyList(),
    val copiedBytes: Long = 0L,
    val verified: Boolean = false,
    val published: Boolean = false,
    val deleteAttempted: Boolean = false,
    val deletionConfirmed: Boolean = false,
    val error: String? = null,
)

@Serializable
data class UsbExportTask(
    val schemaVersion: Int = 4,
    val id: String,
    val logicalId: String,
    val recordingMode: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val target: UsbExportTarget,
    val sources: List<UsbExportSourceSnapshot>,
    /** Old queued tasks safely retain their original session-wide transaction. */
    val bundleFormat: UsbExportBundleFormat = UsbExportBundleFormat.LEGACY_EXPORT,
    val state: UsbExportState = UsbExportState.QUEUED,
    val exportKey: String? = null,
    val exportPrefix: String? = null,
    val relativePath: String = UsbExportPolicy.RELATIVE_PATH,
    val assets: List<UsbExportAsset> = emptyList(),
    val progressBytes: Long = 0L,
    val totalBytes: Long = sources.sumOf { it.sizeBytes + it.sidecarJson.toByteArray().size },
    val freeBytesAtStart: Long? = null,
    val minimumObservedFreeBytes: Long? = null,
    val targetTotalBytes: Long? = target.totalBytes,
    val openAvmQuotaBytes: Long = UsbExportPolicy.OPENAVM_QUOTA_BYTES,
    val openAvmBytesBefore: Long? = null,
    val quotaReclaimedBytes: Long = 0L,
    val errorCode: String? = null,
    val message: String? = null,
    /** Hides only the status card; the durable task remains ownership evidence. */
    val statusCardDismissed: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun terminal(): Boolean = state in setOf(
        UsbExportState.COMPLETED,
        UsbExportState.ALREADY_EXPORTED,
        UsbExportState.FAILED,
        UsbExportState.CANCELLED,
    )

    fun statusCardVisible(): Boolean = state != UsbExportState.CANCELLED && !statusCardDismissed

    fun statusCardDismissible(): Boolean = terminal()
}

@Serializable
data class UsbPortableAsset(
    val kind: UsbExportAssetKind,
    val segmentNumber: Int? = null,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val mimeType: String,
)

@Serializable
data class UsbPortableManifest(
    val schemaVersion: Int = 1,
    val exportKey: String,
    val appVersion: String,
    val buildGitSha: String,
    val logicalId: String,
    val recordingMode: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val segmentCount: Int,
    val assets: List<UsbPortableAsset>,
    val complete: Boolean = true,
)


/** One independently playable, transferable and reclaimable one-minute bundle. */
@Serializable
data class UsbPortableSegmentManifest(
    val schemaVersion: Int = 2,
    val kind: String = "OPENAVM_SEGMENT",
    val bundleId: String,
    val contentKey: String,
    val sourceKind: UsbSegmentSourceKind,
    val appVersion: String,
    val buildGitSha: String,
    val logicalId: String,
    val recordingSessionId: String,
    val segmentNumber: Int,
    val recordingMode: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val protected: Boolean = false,
    val assets: List<UsbPortableAsset>,
    val complete: Boolean = true,
)
@Serializable
data class UsbExportReportAsset(
    val kind: UsbExportAssetKind,
    val segmentNumber: Int? = null,
    val requestedName: String,
    val observedName: String? = null,
    val expectedBytes: Long,
    val copiedBytes: Long,
    val expectedSha256: String,
    val expectedMimeType: String? = null,
    val observedRelativePath: String? = null,
    val observedVolumeName: String? = null,
    val observedSizeBytes: Long? = null,
    val observedMimeType: String? = null,
    val observedPending: Int? = null,
    val observedOwnerPackage: String? = null,
    val rereadBytes: Long? = null,
    val rereadSha256: String? = null,
    val verificationFailures: List<String> = emptyList(),
    val verified: Boolean,
    val published: Boolean,
    val deletionConfirmed: Boolean,
    val error: String? = null,
)

@Serializable
data class UsbExportReport(
    val schemaVersion: Int = 3,
    val generatedAtEpochMs: Long,
    val buildVersion: String,
    val buildGitSha: String,
    val operationId: String,
    val logicalId: String,
    val state: UsbExportState,
    val targetDescription: String,
    val targetUuid: String,
    val targetVolumeName: String,
    val relativePath: String,
    val exportKey: String? = null,
    val progressBytes: Long,
    val totalBytes: Long,
    val freeBytesAtStart: Long? = null,
    val minimumObservedFreeBytes: Long? = null,
    val targetTotalBytes: Long? = null,
    val freeSpaceMarginBytes: Long = UsbExportPolicy.FREE_SPACE_MARGIN_BYTES,
    val openAvmQuotaBytes: Long = UsbExportPolicy.OPENAVM_QUOTA_BYTES,
    val openAvmBytesBefore: Long? = null,
    val quotaReclaimedBytes: Long = 0L,
    val sourceCount: Int,
    val assets: List<UsbExportReportAsset>,
    val errorCode: String? = null,
    val message: String? = null,
)
