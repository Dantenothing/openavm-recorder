package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class UsbOwnedSegmentBundle(
    val manifestData: UsbPortableSegmentManifest,
    val manifest: UsbMediaStoreBackend.Metadata,
    val video: UsbMediaStoreBackend.Metadata,
    val sidecar: UsbMediaStoreBackend.Metadata,
    val incident: com.dante.zeekrcapabilitylab.service.recorder.IncidentTag? = null,
) {
    val existingBytes: Long
        get() = listOf(manifest, video, sidecar).sumOf { it.sizeBytes ?: 0L }
}

data class UsbSegmentCatalogSnapshot(
    val namespaceBytes: Long,
    val segments: List<UsbOwnedSegmentBundle>,
)

class UsbCommittedBundleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "usb_committed_bundles",
        Context.MODE_PRIVATE,
    )

    private fun key(storageUuid: String, bundleId: String) =
        "${storageUuid.lowercase()}|${bundleId.lowercase()}"

    fun mark(storageUuid: String, bundleId: String, owner: String?): Boolean =
        prefs.edit().putString(key(storageUuid, bundleId), owner ?: "unknown").commit()

    fun contains(storageUuid: String, bundleId: String): Boolean =
        prefs.contains(key(storageUuid, bundleId))
}

class UsbSegmentCatalog(
    context: Context,
    private val backend: UsbMediaStoreBackend = UsbMediaStoreBackend(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val committed = UsbCommittedBundleStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(target: UsbExportTarget): UsbSegmentCatalogSnapshot {
        val all = backend.listPublishedOpenAvm(target)
            .filter { VerifiedUsbOwnedDeletion.isPhysicallyPresent(target, it) }
        val byName = all.mapNotNull { item -> item.displayName?.let { it to item } }.toMap()
        val trustedOwners = buildSet {
            add(packageName)
            addAll(UsbExportRepository.trustedProviderOwners(target))
        }
        val markers = UsbIncidentMarkers(appContext)
        val segments = all.mapNotNull { metadata ->
            parse(target, metadata, byName, trustedOwners)
        }.map { bundle ->
            val protection = markers.read(target, bundle.manifestData, byName)
            bundle.copy(manifestData = bundle.manifestData.copy(protected = bundle.manifestData.protected || protection.protected),
                incident = protection.marker?.tag())
        }.distinctBy { it.manifestData.bundleId }
            .sortedBy { it.manifestData.startedAtEpochMs }
        return UsbSegmentCatalogSnapshot(
            namespaceBytes = segments.sumOf { it.existingBytes },
            segments = segments,
        )
    }

    private fun parse(
        target: UsbExportTarget,
        manifestMetadata: UsbMediaStoreBackend.Metadata,
        byName: Map<String, UsbMediaStoreBackend.Metadata>,
        trustedOwners: Set<String>,
    ): UsbOwnedSegmentBundle? {
        val name = manifestMetadata.displayName ?: return null
        if (!name.endsWith(".segment.json", ignoreCase = true)) return null
        if ((manifestMetadata.sizeBytes ?: Long.MAX_VALUE) > MAX_MANIFEST_BYTES) return null
        if (normalize(manifestMetadata.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH)) return null
        if (manifestMetadata.pending != 0) return null
        val manifest = runCatching {
            json.decodeFromString<UsbPortableSegmentManifest>(
                backend.readBytes(manifestMetadata.uri, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        if (!manifest.complete || manifest.kind != "OPENAVM_SEGMENT") return null
        if (!manifest.bundleId.matches(Regex("[a-f0-9]{64}")) || !manifest.contentKey.matches(Regex("[a-f0-9]{64}"))) return null
        if (manifest.assets.size != 2 || manifest.assets.map { it.kind }.toSet() !=
            setOf(UsbExportAssetKind.VIDEO, UsbExportAssetKind.SIDECAR)
        ) return null
        val trustedManifest = manifestMetadata.ownerPackage in trustedOwners ||
            committed.contains(target.storageUuid, manifest.bundleId)
        if (!trustedManifest) return null
        val assets = manifest.assets.mapNotNull { declared ->
            byName[declared.displayName]?.takeIf { observed ->
                observed.pending == 0 &&
                    observed.sizeBytes == declared.sizeBytes &&
                    observed.mimeType == declared.mimeType &&
                    normalize(observed.relativePath) == normalize(UsbExportPolicy.RELATIVE_PATH) &&
                    observed.volumeName.equals(target.volumeName, ignoreCase = true) &&
                    (observed.ownerPackage == manifestMetadata.ownerPackage || observed.ownerPackage in trustedOwners)
            }
        }
        if (assets.size != 2) return null
        val video = assets.firstOrNull { it.mimeType == "video/mp4" } ?: return null
        val sidecar = assets.firstOrNull { it.mimeType == "application/json" } ?: return null
        return UsbOwnedSegmentBundle(manifest, manifestMetadata, video, sidecar)
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    companion object {
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    }
}

@Serializable
private data class UsbCleanupIntent(
    val storageUuid: String,
    val bundleId: String,
    val exactUris: List<String>,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
)

/** Segment-only retention. Legacy exports remain indivisible and are never partially deleted. */
class UsbSegmentRetentionManager(context: Context) {
    private val appContext = context.applicationContext
    private val backend = UsbMediaStoreBackend(appContext)
    private val catalog = UsbSegmentCatalog(appContext, backend)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun admit(
        target: UsbExportTarget,
        incomingBytes: Long,
        quotaBytes: Long,
        protectedBundleId: String,
    ): UsbQuotaCleanupResult = UsbMutationCoordinator.withTarget(target.storageUuid) {
        if (incomingBytes > quotaBytes) {
            throw UsbExportQuotaException(
                "USB_SEGMENT_EXCEEDS_QUOTA",
                "One recording segment is larger than the configured OpenAVM quota",
            )
        }
        val fresh = UsbExportVolumeResolver.resolveExact(appContext, target)
            ?: throw UsbExportQuotaException("TARGET_NOT_MOUNTED", "The selected USB is not mounted")
        val before = catalog.snapshot(fresh)
        val legacyBeforeBytes = UsbExportCatalog(appContext, backend).snapshot(fresh)
            .completeExports.sumOf { it.existingBytes }
        val beforeOwnedBytes = before.namespaceBytes + legacyBeforeBytes
        val quotaRequired = (beforeOwnedBytes + incomingBytes - quotaBytes).coerceAtLeast(0L)
        val freeRequired = (
            incomingBytes + UsbRecordingFreeSpace.RESERVE_BYTES - (fresh.freeBytes ?: -1L)
            ).coerceAtLeast(0L)
        var required = maxOf(quotaRequired, freeRequired)
        val requiredBeforeCleanup = required
        if (required <= 0L) {
            UsbFastTrackReportStore.append(
                appContext,
                UsbFastTrackEvent(
                    storageUuid = fresh.storageUuid,
                    volumeName = fresh.volumeName,
                    targetDescription = fresh.description,
                    storageKind = "USB_MEDIASTORE",
                    state = "QUOTA_ADMITTED",
                    quotaBytes = quotaBytes,
                    quotaBeforeBytes = beforeOwnedBytes,
                    quotaRequiredBytes = 0L,
                    quotaAfterBytes = beforeOwnedBytes,
                    reclaimedBytes = 0L,
                ),
            )
            return@withTarget UsbQuotaCleanupResult(beforeOwnedBytes, 0L, beforeOwnedBytes)
        }
        var reclaimed = 0L
        before.segments.forEach { bundle ->
            val rawVideo = fresh.directoryPath?.let { root ->
                File(root, "${UsbExportPolicy.RELATIVE_PATH}${bundle.video.displayName}")
            }
            if (rawVideo != null && PlaybackPinRegistry.isPinned(rawVideo)) return@forEach
            if (required <= 0L) return@forEach
            val id = bundle.manifestData.bundleId
            if (id == protectedBundleId || bundle.manifestData.protected) return@forEach
            if (UsbBundleLeaseRegistry.isLeased(fresh.storageUuid, id)) return@forEach
            val bytes = bundle.existingBytes
            deleteValidatedBundle(fresh, bundle)
            reclaimed += bytes
            required -= bytes
        }
        val after = catalog.snapshot(fresh)
        val legacyAfterBytes = UsbExportCatalog(appContext, backend).snapshot(fresh)
            .completeExports.sumOf { it.existingBytes }
        val afterOwnedBytes = after.namespaceBytes + legacyAfterBytes
        val currentFree = UsbExportVolumeResolver.resolveExact(appContext, fresh)?.freeBytes ?: -1L
        val stillOverQuota = afterOwnedBytes + incomingBytes > quotaBytes
        val stillLowFree = currentFree < incomingBytes + UsbRecordingFreeSpace.RESERVE_BYTES
        if (stillOverQuota || stillLowFree) {
            UsbFastTrackReportStore.append(
                appContext,
                UsbFastTrackEvent(
                    storageUuid = fresh.storageUuid,
                    volumeName = fresh.volumeName,
                    targetDescription = fresh.description,
                    storageKind = "USB_MEDIASTORE",
                    state = "QUOTA_BLOCKED",
                    quotaBytes = quotaBytes,
                    quotaBeforeBytes = beforeOwnedBytes,
                    quotaRequiredBytes = requiredBeforeCleanup,
                    quotaAfterBytes = afterOwnedBytes,
                    reclaimedBytes = reclaimed,
                ),
            )
            throw UsbExportQuotaException(
                "OPENAVM_SEGMENT_QUOTA_BLOCKED",
                "No additional verified one-minute bundles can be reclaimed; legacy exports stay read-only",
            )
        }
        UsbFastTrackReportStore.append(
            appContext,
            UsbFastTrackEvent(
                storageUuid = fresh.storageUuid,
                volumeName = fresh.volumeName,
                targetDescription = fresh.description,
                storageKind = "USB_MEDIASTORE",
                state = "QUOTA_ADMITTED",
                quotaBytes = quotaBytes,
                quotaBeforeBytes = beforeOwnedBytes,
                quotaRequiredBytes = requiredBeforeCleanup,
                quotaAfterBytes = afterOwnedBytes,
                reclaimedBytes = reclaimed,
            ),
        )
        UsbQuotaCleanupResult(beforeOwnedBytes, reclaimed, afterOwnedBytes)
    }

    internal fun deleteValidatedBundle(target: UsbExportTarget, bundle: UsbOwnedSegmentBundle) {
        val ordered = listOf(bundle.manifest, bundle.sidecar, bundle.video)
        val intent = UsbCleanupIntent(
            storageUuid = target.storageUuid,
            bundleId = bundle.manifestData.bundleId,
            exactUris = ordered.map { it.uri.toString() },
        )
        writeCleanupIntent(intent)
        ordered.forEach { metadata ->
            val asset = UsbExportAsset(
                kind = when (metadata) {
                    bundle.manifest -> UsbExportAssetKind.MANIFEST
                    bundle.sidecar -> UsbExportAssetKind.SIDECAR
                    else -> UsbExportAssetKind.VIDEO
                },
                segmentNumber = bundle.manifestData.segmentNumber,
                requestedName = requireNotNull(metadata.displayName),
                expectedBytes = requireNotNull(metadata.sizeBytes),
                expectedSha256 = "",
                expectedMimeType = metadata.mimeType,
                itemUri = metadata.uri.toString(),
                observedName = metadata.displayName,
                observedRelativePath = metadata.relativePath,
                observedVolumeName = metadata.volumeName,
                observedSizeBytes = metadata.sizeBytes,
                observedMimeType = metadata.mimeType,
                observedPending = metadata.pending,
                observedOwnerPackage = metadata.ownerPackage,
                verified = true,
                published = true,
            )
            val deletion = VerifiedUsbOwnedDeletion.delete(target, metadata, backend, asset)
            require(deletion.deleted) {
                "USB_SEGMENT_DELETE_FAILED:${metadata.displayName}:${deletion.error.orEmpty()}"
            }
        }
        writeCleanupIntent(intent.copy(completed = true))
        runCatching { UsbIncidentMarkers(appContext).removeAfterVideoDeletion(target, bundle) }
    }

    private fun writeCleanupIntent(intent: UsbCleanupIntent) {
        val dir = File(appContext.filesDir, "recordings/usb-cleanup")
        dir.mkdirs()
        val file = File(dir, "${intent.bundleId}.json")
        val partial = File(dir, "${intent.bundleId}.json.partial")
        partial.writeText(json.encodeToString(intent))
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }
}

object UsbRecordingFreeSpace {
    const val RESERVE_BYTES = 10L * 1024L * 1024L * 1024L
}
