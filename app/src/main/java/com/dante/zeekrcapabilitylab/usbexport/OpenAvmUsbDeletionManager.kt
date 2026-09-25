package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class OpenAvmUsbDeleteRequest(
    val recordingKey: String,
    val storageUuid: String,
    val units: List<OpenAvmOwnedUnitRef>,
    val includeProtected: Boolean = false,
)

data class OpenAvmUsbDeleteResult(
    val requestedRecordings: Int,
    val deletedRecordings: Int,
    val deletedUnits: Int,
    val deletedBytes: Long,
    val blockedRecordings: Int,
    val errors: List<String>,
)

@Serializable
private data class LegacyDeleteIntent(
    val operationId: String,
    val storageUuid: String,
    val exportKey: String,
    val exactUris: List<String>,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
)

/**
 * Explicit deletion for complete OpenAVM-owned USB units.
 * Cached paths are never deletion authority: every request is resolved against fresh catalogs
 * on the same mounted UUID. Factory /SentryMode/ files never enter either catalog.
 */
class OpenAvmUsbDeletionManager(context: Context) {
    private val appContext = context.applicationContext
    private val backend = UsbMediaStoreBackend(appContext)
    private val segmentCatalog = UsbSegmentCatalog(appContext, backend)
    private val legacyCatalog = UsbExportCatalog(appContext, backend)
    private val segmentRetention = UsbSegmentRetentionManager(appContext)
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun delete(requests: List<OpenAvmUsbDeleteRequest>): OpenAvmUsbDeleteResult {
        val operation = UUID.randomUUID().toString()
        fun audit(stage: String, details: Map<String, String> = emptyMap()) {
            if (requests.isEmpty()) return
            val recorder = CameraRecordingService.state.value
            EventLogger.logEvent(Categories.SYSTEM, "USB_MANUAL_DELETE_$stage", payload = mapOf(
                "operation" to operation, "requested" to requests.size.toString(),
                "recorderStatus" to recorder.status, "recordingMode" to recorder.recordingMode.name,
                "previewActive" to recorder.previewActive.toString(),
                "cameraWorkKind" to com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.kind) + details)
        }
        audit("STARTED")
        blockingReason()?.let { reason ->
            audit("BLOCKED", mapOf("reason" to reason))
            return blocked(requests, reason)
        }
        val began = System.nanoTime()
        var catalogNanos = 0L
        var deleteNanos = 0L
        var lockWaitNanos = 0L
        var catalogSnapshots = 0
        fun snapshot(target: UsbExportTarget, kinds: Set<OpenAvmOwnedUnitKind>): DeleteInventory {
            val started = System.nanoTime()
            try {
                catalogSnapshots++
                return DeleteInventory(target,
                    if (OpenAvmOwnedUnitKind.SEGMENT_BUNDLE in kinds) segmentCatalog.snapshot(target).segments
                        .associateBy { it.manifestData.bundleId } else emptyMap(),
                    if (OpenAvmOwnedUnitKind.LEGACY_EXPORT in kinds) legacyCatalog.snapshot(target).completeExports
                        .associateBy { it.exportKey } else emptyMap())
            } finally { catalogNanos += System.nanoTime() - started }
        }
        val result = UsbManualDeleteBatch(
            withTarget = { uuid, action ->
                val waiting = System.nanoTime()
                UsbMutationCoordinator.withTarget(uuid) {
                    lockWaitNanos += System.nanoTime() - waiting
                    action()
                }
            },
            inspect = { uuid, kinds ->
                blockingReason()?.let { error(it) }
                val target = UsbExportVolumeResolver.mountedTargets(appContext)
                    .singleOrNull { it.storageUuid.equals(uuid, ignoreCase = true) } ?: error("TARGET_NOT_MOUNTED")
                snapshot(target, kinds)
            },
            remove = { inventory: DeleteInventory, request ->
                val started = System.nanoTime()
                try { deleteRequested(inventory, request) }
                finally { deleteNanos += System.nanoTime() - started }
            },
            remaining = { inventory, kinds ->
                val target = UsbExportVolumeResolver.resolveExact(appContext, inventory.target) ?: error("TARGET_NOT_MOUNTED")
                val after = snapshot(target, kinds)
                after.segments.keys.map { OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.SEGMENT_BUNDLE, it) }.toSet() +
                    after.legacy.keys.map { OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.LEGACY_EXPORT, it) }
            },
        ).delete(requests)
        audit("COMPLETED", mapOf("deletedRecordings" to result.deletedRecordings.toString(),
            "deletedUnits" to result.deletedUnits.toString(), "blockedRecordings" to result.blockedRecordings.toString(),
            "elapsedMs" to ((System.nanoTime() - began) / 1_000_000).toString(),
            "catalogMs" to (catalogNanos / 1_000_000).toString(), "catalogSnapshots" to catalogSnapshots.toString(),
            "deleteMs" to (deleteNanos / 1_000_000).toString(), "lockWaitMs" to (lockWaitNanos / 1_000_000).toString(),
            "errors" to result.errors.take(3).joinToString("; ").take(500)))
        return result
    }

    private data class DeleteInventory(val target: UsbExportTarget,
        val segments: Map<String, UsbOwnedSegmentBundle>, val legacy: Map<String, UsbOwnedExport>)

    private fun deleteRequested(inventory: DeleteInventory, request: OpenAvmUsbDeleteRequest): Long {
        blockingReason()?.let { error(it) }
        val target = UsbExportVolumeResolver.resolveExact(appContext, inventory.target) ?: error("TARGET_NOT_MOUNTED")
        val segments = request.units.filter { it.kind == OpenAvmOwnedUnitKind.SEGMENT_BUNDLE }
            .map { ref -> inventory.segments[ref.id] ?: error("SEGMENT_NOT_FRESH:${ref.id.take(12)}") }
        val legacy = request.units.filter { it.kind == OpenAvmOwnedUnitKind.LEGACY_EXPORT }
            .map { ref -> inventory.legacy[ref.id] ?: error("EXPORT_NOT_FRESH:${ref.id.take(12)}") }
        segments.forEach { bundle ->
            require(!bundle.manifestData.protected || request.includeProtected) { "USB_SEGMENT_PROTECTED" }
            require(!UsbBundleLeaseRegistry.isLeased(target.storageUuid, bundle.manifestData.bundleId)) {
                "USB_SEGMENT_TRANSFER_ACTIVE"
            }
            require(!isPlaying(target, listOf(bundle.video))) { "USB_SEGMENT_PLAYING" }
        }
        legacy.forEach { export ->
            require(!isPlaying(target, export.assets.filter { it.mimeType == "video/mp4" })) { "USB_EXPORT_PLAYING" }
        }
        val bytes = segments.sumOf { it.existingBytes } + legacy.sumOf { it.existingBytes }
        blockingReason()?.let { error(it) }
        segments.forEach { segmentRetention.deleteValidatedBundle(target, it) }
        legacy.forEach { deleteLegacy(target, it) }
        return bytes
    }

    private fun blockingReason() = UsbDeleteAdmission.blockingReason(CameraRecordingService.isRunning(),
        com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value)

    private fun deleteLegacy(target: UsbExportTarget, export: UsbOwnedExport) {
        val ordered = listOf(export.manifest) + export.assets
        val intent = LegacyDeleteIntent(
            operationId = UUID.randomUUID().toString(),
            storageUuid = target.storageUuid,
            exportKey = export.exportKey,
            exactUris = ordered.map { it.uri.toString() },
        )
        writeIntent(intent)
        ordered.forEach { metadata ->
            val name = requireNotNull(metadata.displayName)
            val asset = UsbExportAsset(
                kind = when {
                    metadata === export.manifest -> UsbExportAssetKind.MANIFEST
                    name.endsWith(".sidecar.json", ignoreCase = true) -> UsbExportAssetKind.SIDECAR
                    else -> UsbExportAssetKind.VIDEO
                },
                requestedName = name,
                expectedBytes = requireNotNull(metadata.sizeBytes),
                expectedSha256 = "",
                expectedMimeType = metadata.mimeType,
                itemUri = metadata.uri.toString(),
                observedName = name,
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
                "USB_LEGACY_DELETE_FAILED:$name:${deletion.error.orEmpty()}"
            }
        }
        writeIntent(intent.copy(completed = true))
    }

    private fun isPlaying(
        target: UsbExportTarget,
        items: List<UsbMediaStoreBackend.Metadata>,
    ): Boolean {
        val root = target.directoryPath ?: return false
        return items.any { metadata ->
            val relative = metadata.relativePath?.trim()?.trim('/', '\\').orEmpty()
            val name = metadata.displayName ?: return@any false
            PlaybackPinRegistry.isPinned(File(File(root, relative), name))
        }
    }

    private fun writeIntent(intent: LegacyDeleteIntent) {
        val dir = File(appContext.filesDir, "recordings/usb-manual-cleanup").apply { mkdirs() }
        val file = File(dir, "${intent.operationId}.json")
        val partial = File(dir, "${intent.operationId}.json.partial")
        partial.writeText(json.encodeToString(intent))
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }

    private fun blocked(requests: List<OpenAvmUsbDeleteRequest>, reason: String) =
        OpenAvmUsbDeleteResult(
            requestedRecordings = requests.distinctBy { it.recordingKey }.size,
            deletedRecordings = 0,
            deletedUnits = 0,
            deletedBytes = 0L,
            blockedRecordings = requests.distinctBy { it.recordingKey }.size,
            errors = listOf(reason),
        )
}
