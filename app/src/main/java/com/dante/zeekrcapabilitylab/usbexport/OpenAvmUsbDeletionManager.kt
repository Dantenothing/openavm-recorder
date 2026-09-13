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
                "previewActive" to recorder.previewActive.toString()) + details)
        }
        audit("STARTED")
        if (CameraRecordingService.isRunning()) {
            audit("BLOCKED", mapOf("reason" to "RECORDING_ACTIVE"))
            return blocked(requests, "RECORDING_ACTIVE")
        }
        var deletedRecordings = 0
        var deletedUnits = 0
        var deletedBytes = 0L
        val errors = mutableListOf<String>()

        requests.distinctBy { it.recordingKey }.forEach { request ->
            val outcome = runCatching {
                UsbMutationCoordinator.withTarget(request.storageUuid) {
                    val target = UsbExportVolumeResolver.mountedTargets(appContext)
                        .singleOrNull { it.storageUuid.equals(request.storageUuid, ignoreCase = true) }
                        ?: error("TARGET_NOT_MOUNTED")
                    val freshSegments = segmentCatalog.snapshot(target).segments
                        .associateBy { it.manifestData.bundleId }
                    val freshLegacy = legacyCatalog.snapshot(target).completeExports
                        .associateBy { it.exportKey }
                    val units = request.units.distinct()
                    require(units.isNotEmpty()) { "NO_VERIFIED_OWNED_UNITS" }

                    val segments = units.filter { it.kind == OpenAvmOwnedUnitKind.SEGMENT_BUNDLE }
                        .map { ref -> freshSegments[ref.id] ?: error("SEGMENT_NOT_FRESH:${ref.id.take(12)}") }
                    val legacy = units.filter { it.kind == OpenAvmOwnedUnitKind.LEGACY_EXPORT }
                        .map { ref -> freshLegacy[ref.id] ?: error("EXPORT_NOT_FRESH:${ref.id.take(12)}") }

                    segments.forEach { bundle ->
                        require(!bundle.manifestData.protected) { "USB_SEGMENT_PROTECTED" }
                        require(!UsbBundleLeaseRegistry.isLeased(target.storageUuid, bundle.manifestData.bundleId)) {
                            "USB_SEGMENT_TRANSFER_ACTIVE"
                        }
                        require(!isPlaying(target, listOf(bundle.video))) { "USB_SEGMENT_PLAYING" }
                    }
                    legacy.forEach { export ->
                        require(!isPlaying(target, export.assets.filter { it.mimeType == "video/mp4" })) {
                            "USB_EXPORT_PLAYING"
                        }
                    }

                    val bytes = segments.sumOf { it.existingBytes } + legacy.sumOf { it.existingBytes }
                    segments.forEach { segmentRetention.deleteValidatedBundle(target, it) }
                    legacy.forEach { deleteLegacy(target, it) }

                    // A final fresh catalog must no longer expose any requested complete unit.
                    val remainingSegments = segmentCatalog.snapshot(target).segments
                        .mapTo(mutableSetOf()) { it.manifestData.bundleId }
                    val remainingLegacy = legacyCatalog.snapshot(target).completeExports
                        .mapTo(mutableSetOf()) { it.exportKey }
                    require(units.none { ref ->
                        when (ref.kind) {
                            OpenAvmOwnedUnitKind.SEGMENT_BUNDLE -> ref.id in remainingSegments
                            OpenAvmOwnedUnitKind.LEGACY_EXPORT -> ref.id in remainingLegacy
                        }
                    }) { "POST_DELETE_CATALOG_VERIFY_FAILED" }
                    units.size to bytes
                }
            }
            outcome.onSuccess { (unitCount, bytes) ->
                deletedRecordings += 1
                deletedUnits += unitCount
                deletedBytes += bytes
            }.onFailure { error ->
                errors += "${request.recordingKey}:${error.message ?: error.javaClass.simpleName}"
            }
        }
        val result = OpenAvmUsbDeleteResult(
            requestedRecordings = requests.distinctBy { it.recordingKey }.size,
            deletedRecordings = deletedRecordings,
            deletedUnits = deletedUnits,
            deletedBytes = deletedBytes,
            blockedRecordings = requests.distinctBy { it.recordingKey }.size - deletedRecordings,
            errors = errors,
        )
        audit("COMPLETED", mapOf("deletedRecordings" to result.deletedRecordings.toString(),
            "deletedUnits" to result.deletedUnits.toString(), "blockedRecordings" to result.blockedRecordings.toString(),
            "errors" to result.errors.take(3).joinToString("; ").take(500)))
        return result
    }

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
