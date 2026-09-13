package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.Serializable

@Serializable
data class UsbWriteProbeResult(
    val attempted: Boolean = true,
    val createSucceeded: Boolean = false,
    val writeSucceeded: Boolean = false,
    val syncSucceeded: Boolean = false,
    val seekSucceeded: Boolean = false,
    val readBackSucceeded: Boolean = false,
    val renameSucceeded: Boolean = false,
    val cleanupSucceeded: Boolean = false,
    val error: String? = null,
) {
    val exportCandidate: Boolean
        get() = createSucceeded && writeSucceeded && syncSucceeded && readBackSucceeded &&
            renameSucceeded && cleanupSucceeded

    val fileDescriptorCandidate: Boolean
        get() = exportCandidate && seekSucceeded
}

@Serializable
data class AppSpecificStorageObservation(
    val index: Int,
    val path: String?,
    val exists: Boolean,
    val removable: Boolean?,
    val readable: Boolean,
    val writable: Boolean,
    val usableBytes: Long?,
    val totalBytes: Long?,
    val writeProbe: UsbWriteProbeResult? = null,
)

@Serializable
data class StorageVolumeObservation(
    val description: String,
    val uuid: String?,
    val state: String,
    val primary: Boolean,
    val removable: Boolean,
    val emulated: Boolean,
    val directory: String?,
    val mediaStoreVolumeName: String? = null,
)

@Serializable
enum class MediaStoreDuplicateBehavior {
    NOT_ATTEMPTED,
    INSERT_REJECTED,
    SAME_NAME_ALLOWED,
    AUTO_RENAMED,
    UNKNOWN,
}

@Serializable
data class MediaStorePublishedItemObservation(
    val requestedDisplayName: String,
    val itemUri: String? = null,
    val createSucceeded: Boolean = false,
    val writeSucceeded: Boolean = false,
    val syncSucceeded: Boolean = false,
    val seekSucceeded: Boolean = false,
    val readBackSucceeded: Boolean = false,
    val publishUpdateCount: Int? = null,
    val uriQuerySucceeded: Boolean = false,
    val collectionQuerySucceeded: Boolean = false,
    val observedDisplayName: String? = null,
    val observedRelativePath: String? = null,
    val observedSizeBytes: Long? = null,
    val observedMimeType: String? = null,
    val observedPending: Int? = null,
    val observedVolumeName: String? = null,
    val metadataMatched: Boolean = false,
    val deleteCount: Int? = null,
    val deletionConfirmed: Boolean = false,
    val error: String? = null,
) {
    val publicationCandidate: Boolean
        get() = createSucceeded && writeSucceeded && syncSucceeded && seekSucceeded &&
            readBackSucceeded && (publishUpdateCount ?: 0) > 0 && uriQuerySucceeded &&
            collectionQuerySucceeded && metadataMatched && (deleteCount ?: 0) > 0 &&
            deletionConfirmed
}

@Serializable
data class MediaStoreDuplicateObservation(
    val attempted: Boolean = false,
    val behavior: MediaStoreDuplicateBehavior = MediaStoreDuplicateBehavior.NOT_ATTEMPTED,
    val requestedDisplayName: String? = null,
    val firstObservedDisplayName: String? = null,
    val secondObservedDisplayName: String? = null,
    val secondItemUri: String? = null,
    val secondItemDeleted: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class MediaStorePublicationProbeResult(
    val attempted: Boolean = true,
    val expectedRelativePath: String,
    val expectedMimeType: String,
    val firstItem: MediaStorePublishedItemObservation,
    val duplicate: MediaStoreDuplicateObservation,
) {
    val exportCandidate: Boolean
        get() = firstItem.publicationCandidate && duplicate.attempted &&
            duplicate.behavior != MediaStoreDuplicateBehavior.UNKNOWN &&
            duplicate.secondItemDeleted != false

    val fileDescriptorCandidate: Boolean
        get() = exportCandidate && firstItem.seekSucceeded
}

@Serializable
data class MediaStoreVolumeObservation(
    val volumeName: String,
    val collectionUri: String,
    val storageDescription: String? = null,
    val storageUuid: String? = null,
    val primary: Boolean? = null,
    val removable: Boolean? = null,
    val mounted: Boolean? = null,
    val querySucceeded: Boolean,
    val queryError: String? = null,
    val writeProbe: UsbWriteProbeResult? = null,
    val publicationProbe: MediaStorePublicationProbeResult? = null,
)

@Serializable
data class SafPickerObservation(
    val intentAction: String,
    val handlerAvailable: Boolean,
    val resolvedPackage: String? = null,
    val resolvedActivity: String? = null,
    val launchAttempted: Boolean = false,
    val launchDispatchSucceeded: Boolean? = null,
    val resultReturned: Boolean? = null,
    val treeSelected: Boolean? = null,
    val launchError: String? = null,
)

@Serializable
data class MountObservation(
    val source: String,
    val mountPoint: String,
    val fileSystem: String,
    val options: List<String>,
)

@Serializable
data class SafTreeObservation(
    val uri: String,
    val documentId: String?,
    val displayName: String?,
    val providerFlags: Long?,
    val persistedRead: Boolean,
    val persistedWrite: Boolean,
    val supportsCreate: Boolean?,
    val supportsRename: Boolean?,
    val writeProbe: UsbWriteProbeResult? = null,
    val error: String? = null,
)

@Serializable
data class UsbProbeReport(
    val schemaVersion: Int = 3,
    val generatedAtEpochMs: Long,
    val buildVersion: String,
    val buildGitSha: String,
    val allFilesAccessGranted: Boolean,
    val androidSdkInt: Int = 0,
    val appSpecific: List<AppSpecificStorageObservation>,
    val storageVolumes: List<StorageVolumeObservation>,
    val mediaStoreVolumes: List<MediaStoreVolumeObservation> = emptyList(),
    val mounts: List<MountObservation>,
    val safPicker: SafPickerObservation? = null,
    val safTree: SafTreeObservation?,
    val notes: List<String> = emptyList(),
)

@Serializable
data class UsbBackendSummary(
    val backend: String,
    val visible: Boolean,
    val exportCandidate: Boolean,
    val fileDescriptorCandidate: Boolean,
    val detail: String,
)

object UsbMountTableParser {
    fun parse(text: String): List<MountObservation> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 4) return@mapNotNull null
            val mountPoint = decodeMountField(fields[1])
            if (!isRelevantMount(mountPoint)) return@mapNotNull null
            MountObservation(
                source = decodeMountField(fields[0]),
                mountPoint = mountPoint,
                fileSystem = fields[2],
                options = fields[3].split(',').filter(String::isNotBlank),
            )
        }
        .distinctBy { Triple(it.source, it.mountPoint, it.fileSystem) }
        .sortedBy(MountObservation::mountPoint)
        .toList()

    private fun isRelevantMount(path: String): Boolean =
        (path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) &&
            !path.startsWith("/storage/emulated") &&
            !path.startsWith("/storage/self")

    private fun decodeMountField(value: String): String = value
        .replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
}

object UsbProbeCapabilitySummarizer {
    fun summarize(report: UsbProbeReport): List<UsbBackendSummary> {
        val removableAppDirs = report.appSpecific.filter { it.removable == true }
        val appPassed = removableAppDirs.any { it.writeProbe?.exportCandidate == true }
        val appFdPassed = removableAppDirs.any { it.writeProbe?.fileDescriptorCandidate == true }
        val safProbe = report.safTree?.writeProbe
        val removableMediaStore = report.mediaStoreVolumes.filter { it.removable == true }
        val mediaStorePassed = removableMediaStore.any {
            it.publicationProbe?.exportCandidate == true || it.writeProbe?.exportCandidate == true
        }
        val mediaStoreFdPassed = removableMediaStore.any {
            it.publicationProbe?.fileDescriptorCandidate == true || it.writeProbe?.fileDescriptorCandidate == true
        }
        val rawVisible = report.mounts.isNotEmpty()
        return listOf(
            UsbBackendSummary(
                backend = "APP_SPECIFIC_EXTERNAL",
                visible = removableAppDirs.isNotEmpty(),
                exportCandidate = appPassed,
                fileDescriptorCandidate = appFdPassed,
                detail = when {
                    removableAppDirs.isEmpty() -> "No removable app-specific directory was returned"
                    appPassed -> "A removable app-owned directory passed the explicit write probe"
                    else -> "Visible, but the explicit write probe has not passed"
                },
            ),
            UsbBackendSummary(
                backend = "MEDIASTORE_REMOVABLE",
                visible = removableMediaStore.isNotEmpty(),
                exportCandidate = mediaStorePassed,
                fileDescriptorCandidate = mediaStoreFdPassed,
                detail = when {
                    removableMediaStore.isEmpty() -> "No MediaStore volume was mapped to removable storage"
                    mediaStorePassed -> "A removable MediaStore volume passed the Gate A.2 publication probe"
                    else -> "A removable MediaStore volume is visible, but publication has not passed Gate A.2"
                },
            ),
            UsbBackendSummary(
                backend = "SAF_TREE",
                visible = report.safTree != null,
                exportCandidate = safProbe?.exportCandidate == true,
                fileDescriptorCandidate = safProbe?.fileDescriptorCandidate == true,
                detail = when {
                    report.safTree == null -> "No persisted SAF tree is selected"
                    safProbe?.exportCandidate == true -> "Selected tree passed the explicit write probe"
                    else -> "Selected tree is visible, but the explicit write probe has not passed"
                },
            ),
            UsbBackendSummary(
                backend = "RAW_REMOVABLE_PATH",
                visible = rawVisible,
                exportCandidate = false,
                fileDescriptorCandidate = false,
                detail = when {
                    !rawVisible -> "No removable mount was observed in /proc/mounts"
                    report.allFilesAccessGranted -> "Mount observed and all-files access granted; raw writes are intentionally not tested in Gate A"
                    else -> "Mount observed; all-files access is not granted and raw writes are not tested"
                },
            ),
        )
    }
}
