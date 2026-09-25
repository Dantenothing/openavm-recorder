package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.net.Uri
import com.dante.zeekrcapabilitylab.usbexport.UsbCommittedBundleStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAsset
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAssetKind
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackEvent
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import com.dante.zeekrcapabilitylab.usbexport.UsbPortableSegmentManifest
import java.io.File
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Recovers only exact MediaStore item URIs recorded before a direct USB segment commit.
 * It never scans or mutates the factory SentryMode namespace.
 */
internal object UsbPendingRecordingRecoveryEngine {
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun recoverMounted(context: Context, onlyOperationId: String? = null) {
        val appContext = context.applicationContext
        val journal = UsbRecordingRecoveryJournal(appContext)
        val backend = UsbMediaStoreBackend(appContext)
        val targets = UsbExportVolumeResolver.mountedTargets(appContext)
        journal.entries().filter { onlyOperationId == null || it.operationId == onlyOperationId }.forEach { entry ->
            val target = targets.singleOrNull {
                it.storageUuid.equals(entry.storageUuid, ignoreCase = true) &&
                    it.volumeName.equals(entry.volumeName, ignoreCase = true)
            } ?: return@forEach
            UsbMutationCoordinator.withTarget(target.storageUuid) {
                recoverEntry(appContext, journal, backend, target, entry)
            }
        }
    }

    private fun recoverEntry(
        context: Context,
        journal: UsbRecordingRecoveryJournal,
        backend: UsbMediaStoreBackend,
        target: UsbExportTarget,
        entry: UsbPendingRecordingOutput,
    ) {
        // A gallery refresh/recovery pass must never inspect or clean files owned by this live process.
        if (entry.nativeCheckpoint?.processStartId == com.dante.zeekrcapabilitylab.ZeekrApp.processStartId) return
        val recordedAssets = entry.assets.ifEmpty {
            listOf(
                UsbPendingRecordingAsset(
                    kind = UsbExportAssetKind.VIDEO,
                    itemUri = entry.itemUri,
                    requestedName = entry.requestedName,
                    expectedMimeType = "video/mp4",
                    observedOwnerPackage = entry.observedOwnerPackage,
                ),
            )
        }
        val shapeValid = recordedAssets.isNotEmpty() &&
            recordedAssets.distinctBy { it.itemUri }.size == recordedAssets.size &&
            recordedAssets.count { it.kind == UsbExportAssetKind.VIDEO } == 1 &&
            recordedAssets.count { it.kind == UsbExportAssetKind.SIDECAR } <= 1 &&
            recordedAssets.count { it.kind == UsbExportAssetKind.MANIFEST } <= 1
        if (!shapeValid) {
            report(context, entry, target, "RECOVERY_IDENTITY_MISMATCH", "Recovery asset ledger is ambiguous")
            return
        }

        val observations = try {
            recordedAssets.map { recorded ->
                ObservedAsset(recorded, backend.metadata(Uri.parse(recorded.itemUri)))
            }
        } catch (failure: Throwable) {
            report(
                context,
                entry,
                target,
                "RECOVERY_RETAINED",
                "Metadata query failed: ${failure.javaClass.simpleName}",
            )
            return
        }
        val identityFailures = observations.flatMap { observation ->
            identityFailures(context, target, observation)
        }
        if (identityFailures.isNotEmpty()) {
            report(
                context,
                entry,
                target,
                "RECOVERY_IDENTITY_MISMATCH",
                identityFailures.joinToString(";"),
            )
            return
        }

        val committed = committedManifest(entry, observations, backend)
        if (committed != null) {
            val ownershipPersisted = UsbCommittedBundleStore(context).mark(
                target.storageUuid,
                committed.manifest.bundleId,
                committed.ownerPackage,
            )
            if (ownershipPersisted) {
                removeJournalAndToken(context, journal, entry)
                report(context, entry, target, "RECOVERY_COMMITTED", "Published segment manifest verified")
            } else {
                report(context, entry, target, "RECOVERY_RETAINED", "Ownership marker could not be persisted")
            }
            return
        }

        entry.nativeCheckpoint?.let { checkpoint ->
            val video = observations.singleOrNull { it.recorded.kind == UsbExportAssetKind.VIDEO }
            val onlyVideoPresent = observations.none { it.recorded.kind != UsbExportAssetKind.VIDEO && it.metadata != null }
            val actualBytes = video?.metadata?.uri?.let { uri -> runCatching { backend.closedFileLength(uri) }.getOrNull() }
            val action = NativePendingRecoveryPolicy.decide(false, actualBytes, onlyVideoPresent)
            if (action == NativePendingRecoveryPolicy.Action.RECOVER && video != null &&
                checkpoint.recordingSessionId == entry.recordingSessionId && checkpoint.segmentNumber == entry.segmentNumber &&
                entry.bundleId != null) {
                val recovered = runCatching {
                    // Rollback may have removed sidecars but retained the video. Forget only verified-missing assets.
                    journal.put(entry.copy(assets = listOf(video.recorded)))
                    val pending = com.dante.zeekrcapabilitylab.usbexport.UsbPendingVideo(entry.operationId,
                        entry.bundleId, target, "session:${entry.recordingSessionId}", entry.recordingSessionId,
                        entry.segmentNumber, checkpoint.recordingMode.name, checkpoint.requestedAtEpochMs ?: entry.createdAtEpochMs,
                        entry.requestedName, entry.itemUri, entry.observedOwnerPackage)
                    val result = com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCommitEngine(context)
                        .commitDirect(pending, checkpoint, preserveVideoOnFailure = true)
                    check(UsbCommittedBundleStore(context).mark(target.storageUuid, result.bundleId, result.observedOwnerPackage))
                    removeJournalAndToken(context, journal, entry)
                }
                report(context, entry, target, if (recovered.isSuccess) "NATIVE_RECOVERED" else "NATIVE_RETAINED",
                    if (recovered.isSuccess) "Closed native segment verified" else "Nonempty video retained for recovery")
                return
            }
            if (action != NativePendingRecoveryPolicy.Action.REMOVE_EMPTY) {
                report(context, entry, target, "NATIVE_RETAINED", "Uncertain native output retained without deletion")
                return
            }
        }

        val failures = mutableListOf<String>()
        observations.sortedBy { cleanupOrder(it.recorded.kind) }.forEach { observation ->
            val metadata = observation.metadata ?: return@forEach
            val result = backend.deleteOwned(target, ownedAsset(entry, observation.recorded, metadata))
            if (!result.deletionConfirmed) {
                failures += "${observation.recorded.kind}:${result.error ?: "DELETE_FAILED"}"
            }
        }
        if (failures.isEmpty()) {
            removeJournalAndToken(context, journal, entry)
            report(context, entry, target, "RECOVERY_CLEANED", "Exact pending assets removed")
        } else {
            report(context, entry, target, "RECOVERY_RETAINED", failures.joinToString(";"))
        }
    }

    private fun identityFailures(
        context: Context,
        target: UsbExportTarget,
        observation: ObservedAsset,
    ): List<String> {
        val metadata = observation.metadata ?: return emptyList()
        val recorded = observation.recorded
        val itemUri = runCatching { Uri.parse(recorded.itemUri) }.getOrNull()
        return buildList {
            if (itemUri == null || !isExactItemUri(target, itemUri)) add("${recorded.kind}:URI")
            if (!metadata.volumeName.equals(target.volumeName, ignoreCase = true)) add("${recorded.kind}:VOLUME")
            if (normalize(metadata.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH)) add("${recorded.kind}:PATH")
            if (metadata.displayName != recorded.requestedName) add("${recorded.kind}:NAME")
            if (metadata.mimeType != recorded.expectedMimeType) add("${recorded.kind}:MIME")
            if (metadata.sizeBytes == null) add("${recorded.kind}:SIZE")
            val expectedOwner = recorded.observedOwnerPackage
            if (metadata.ownerPackage.isNullOrBlank() ||
                if (expectedOwner != null) metadata.ownerPackage != expectedOwner
                else metadata.ownerPackage != context.packageName
            ) add("${recorded.kind}:OWNER")
        }
    }

    private fun committedManifest(
        entry: UsbPendingRecordingOutput,
        observations: List<ObservedAsset>,
        backend: UsbMediaStoreBackend,
    ): CommittedManifest? {
        val expectedBundleId = entry.bundleId ?: return null
        val manifestObservation = observations.singleOrNull {
            it.recorded.kind == UsbExportAssetKind.MANIFEST
        } ?: return null
        val manifestMetadata = manifestObservation.metadata?.takeIf { it.pending == 0 } ?: return null
        val manifest = runCatching {
            json.decodeFromString<UsbPortableSegmentManifest>(
                backend.readBytes(manifestMetadata.uri, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        if (!manifest.complete || manifest.kind != "OPENAVM_SEGMENT") return null
        if (manifest.bundleId != expectedBundleId || manifest.recordingSessionId != entry.recordingSessionId) return null
        if (manifest.segmentNumber != entry.segmentNumber) return null
        if (manifest.assets.size != 2 || manifest.assets.map { it.kind }.toSet() !=
            setOf(UsbExportAssetKind.VIDEO, UsbExportAssetKind.SIDECAR)
        ) return null

        val committedAssets = observations.filter {
            it.recorded.kind in setOf(UsbExportAssetKind.VIDEO, UsbExportAssetKind.SIDECAR)
        }
        if (committedAssets.size != 2) return null
        val assetsMatch = committedAssets.all { observation ->
            val metadata = observation.metadata ?: return@all false
            val declared = manifest.assets.singleOrNull { it.kind == observation.recorded.kind }
                ?: return@all false
            metadata.pending == 0 &&
                metadata.displayName == declared.displayName &&
                metadata.sizeBytes == declared.sizeBytes &&
                metadata.mimeType == declared.mimeType &&
                normalize(metadata.relativePath) == normalize(declared.relativePath) &&
                declared.displayName == observation.recorded.requestedName &&
                declared.mimeType == observation.recorded.expectedMimeType
        }
        return if (assetsMatch) {
            CommittedManifest(manifest, manifestMetadata.ownerPackage)
        } else {
            null
        }
    }

    private fun ownedAsset(
        entry: UsbPendingRecordingOutput,
        recorded: UsbPendingRecordingAsset,
        metadata: UsbMediaStoreBackend.Metadata,
    ) = UsbExportAsset(
        kind = recorded.kind,
        segmentNumber = entry.segmentNumber,
        requestedName = recorded.requestedName,
        expectedBytes = metadata.sizeBytes ?: 0L,
        expectedSha256 = "",
        expectedMimeType = recorded.expectedMimeType,
        itemUri = recorded.itemUri,
        observedName = metadata.displayName,
        observedRelativePath = metadata.relativePath,
        observedVolumeName = metadata.volumeName,
        observedSizeBytes = metadata.sizeBytes,
        observedMimeType = metadata.mimeType,
        observedPending = metadata.pending,
        observedOwnerPackage = recorded.observedOwnerPackage,
        verified = true,
        published = metadata.pending == 0,
    )

    private fun report(
        context: Context,
        entry: UsbPendingRecordingOutput,
        target: UsbExportTarget,
        state: String,
        message: String,
    ) {
        val video = entry.assets.firstOrNull { it.kind == UsbExportAssetKind.VIDEO }
        UsbFastTrackReportStore.append(
            context,
            UsbFastTrackEvent(
                operationId = entry.operationId,
                bundleId = entry.bundleId,
                storageUuid = entry.storageUuid,
                volumeName = entry.volumeName,
                targetDescription = target.description,
                segmentNumber = entry.segmentNumber,
                storageKind = "USB_MEDIASTORE",
                state = state,
                relativePath = UsbExportPolicy.RELATIVE_PATH,
                requestedName = video?.requestedName ?: entry.requestedName,
                itemUri = video?.itemUri ?: entry.itemUri,
                message = message,
            ),
        )
    }

    private fun removeJournalAndToken(
        context: Context,
        journal: UsbRecordingRecoveryJournal,
        entry: UsbPendingRecordingOutput,
    ) {
        journal.remove(entry.operationId)
        File(context.filesDir, "recordings/usb-tokens/${entry.operationId}.token").delete()
    }

    private fun cleanupOrder(kind: UsbExportAssetKind): Int = when (kind) {
        UsbExportAssetKind.MANIFEST -> 0
        UsbExportAssetKind.SIDECAR -> 1
        UsbExportAssetKind.VIDEO -> 2
    }

    private fun isExactItemUri(target: UsbExportTarget, item: Uri): Boolean {
        val collection = Uri.parse(target.collectionUri)
        if (!item.scheme.equals(collection.scheme, ignoreCase = true)) return false
        if (!item.authority.equals(collection.authority, ignoreCase = true)) return false
        val parent = collection.pathSegments
        val child = item.pathSegments
        if (child.size != parent.size + 1) return false
        if (!parent.indices.all { child[it].equals(parent[it], ignoreCase = true) }) return false
        return child.last().toLongOrNull() != null
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    private data class ObservedAsset(
        val recorded: UsbPendingRecordingAsset,
        val metadata: UsbMediaStoreBackend.Metadata?,
    )

    private data class CommittedManifest(
        val manifest: UsbPortableSegmentManifest,
        val ownerPackage: String?,
    )
}
