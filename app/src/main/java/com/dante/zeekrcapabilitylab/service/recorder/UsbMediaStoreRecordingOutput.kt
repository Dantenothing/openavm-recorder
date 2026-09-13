package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAsset
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAssetKind
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackEvent
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCatalog
import com.dante.zeekrcapabilitylab.usbexport.UsbPendingVideo
import java.io.File

/** Creates the final-name pending MediaStore MP4 and binds MediaRecorder directly to its FD. */
class UsbMediaStoreRecordingOutputSink(
    context: Context,
    private val target: UsbExportTarget,
    private val recordingSessionId: String,
    private val recordingMode: RecordingMode,
    private val tokenDir: File,
    private val journal: UsbRecordingRecoveryJournal,
) : RecordingOutputSink {
    private val appContext = context.applicationContext
    private val backend = UsbMediaStoreBackend(appContext)

    override fun openSegment(
        segmentNumber: Int,
        profile: CameraFormatProfile,
        startedAtEpochMs: Long,
    ): RecordingOutputHandle {
        val operationId = UsbExportPolicy.operationId()
        val bundleId = UsbExportPolicy.bundleId(recordingSessionId, segmentNumber, startedAtEpochMs)
        val prefix = UsbExportPolicy.prefix(startedAtEpochMs, bundleId)
        val displayName = UsbExportPolicy.videoName(prefix, segmentNumber)
        val inserted = backend.insertPending(target, displayName, "video/mp4")
        require(inserted.displayName == displayName) { "USB_PROVIDER_NAME_CONFLICT" }
        require(inserted.volumeName.equals(target.volumeName, ignoreCase = true)) {
            "USB_PROVIDER_VOLUME_MISMATCH"
        }
        val descriptor = try {
            backend.openRecordingDescriptor(inserted.uri)
        } catch (failure: Throwable) {
            cleanupInserted(inserted, segmentNumber)
            throw failure
        }
        tokenDir.mkdirs()
        val marker = File(tokenDir, "$operationId.token")
        marker.writeText(operationId)
        val pending = UsbPendingVideo(
            operationId = operationId,
            bundleId = bundleId,
            target = target,
            logicalId = "session:$recordingSessionId",
            recordingSessionId = recordingSessionId,
            segmentNumber = segmentNumber,
            recordingMode = recordingMode.name,
            startedAtEpochMs = startedAtEpochMs,
            displayName = displayName,
            itemUri = inserted.uri.toString(),
            observedOwnerPackage = inserted.ownerPackage,
        )
        journal.put(
            UsbPendingRecordingOutput(
                operationId = operationId,
                recordingSessionId = recordingSessionId,
                segmentNumber = segmentNumber,
                storageUuid = target.storageUuid,
                volumeName = target.volumeName,
                itemUri = inserted.uri.toString(),
                requestedName = displayName,
                createdAtEpochMs = System.currentTimeMillis(),
                observedOwnerPackage = inserted.ownerPackage,
                bundleId = bundleId,
                assets = listOf(
                    UsbPendingRecordingAsset(
                        kind = UsbExportAssetKind.VIDEO,
                        itemUri = inserted.uri.toString(),
                        requestedName = displayName,
                        expectedMimeType = "video/mp4",
                        observedOwnerPackage = inserted.ownerPackage,
                    ),
                ),
            ),
        )
        return UsbMediaStoreRecordingOutputHandle(
            appContext = appContext,
            backend = backend,
            target = target,
            pendingVideo = pending,
            descriptor = descriptor,
            localWorkingFile = marker,
            journal = journal,
        )
    }

    private fun cleanupInserted(metadata: UsbMediaStoreBackend.Metadata, segmentNumber: Int) {
        backend.deleteOwned(
            target,
            UsbExportAsset(
                kind = UsbExportAssetKind.VIDEO,
                segmentNumber = segmentNumber,
                requestedName = metadata.displayName.orEmpty(),
                expectedBytes = metadata.sizeBytes ?: 0L,
                expectedSha256 = "",
                expectedMimeType = "video/mp4",
                itemUri = metadata.uri.toString(),
                observedName = metadata.displayName,
                observedRelativePath = metadata.relativePath,
                observedVolumeName = metadata.volumeName,
                observedSizeBytes = metadata.sizeBytes,
                observedMimeType = metadata.mimeType,
                observedPending = metadata.pending,
                observedOwnerPackage = metadata.ownerPackage,
            ),
        )
    }
}

class UsbMediaStoreRecordingOutputHandle internal constructor(
    private val appContext: Context,
    private val backend: UsbMediaStoreBackend,
    private val target: UsbExportTarget,
    val pendingVideo: UsbPendingVideo,
    private var descriptor: ParcelFileDescriptor?,
    override val localWorkingFile: File,
    private val journal: UsbRecordingRecoveryJournal,
) : RecordingOutputHandle {
    override val storage = RecordingStorageIdentity(
        kind = RecordingStorageKind.USB_MEDIASTORE,
        storageUuid = target.storageUuid,
        volumeName = target.volumeName,
        description = target.description,
    )
    override val displayName: String = pendingVideo.displayName

    override fun bind(recorder: MediaRecorder) {
        recorder.setOutputFile(requireNotNull(descriptor).fileDescriptor)
    }

    override fun close() {
        val current = descriptor ?: return
        // Sync is attempted on IO after producer/recorder release, never on the camera worker.
        val syncFailure = runCatching { backend.sync(current) }.exceptionOrNull()
        current.close() // Retain the handle if close throws; the cleanup owner remains quarantined.
        descriptor = null
        if (syncFailure != null) throw OutputFlushException(syncFailure)
    }

    override fun abort() {
        close()
        // A disappearing provider must not escape into a recorder/camera executor and kill
        // the process. The durable journal intentionally remains for the next same-volume mount.
        runCatching { UsbPendingRecordingRecoveryEngine.recoverMounted(appContext) }
        runCatching {
            if (journal.entries().none { it.operationId == pendingVideo.operationId }) {
                localWorkingFile.delete()
            }
        }
    }

    /** Closes only our descriptor; no sync, MediaStore query, publication, or deletion. */
    fun abandonUnavailableTarget() {
        val current = descriptor ?: return
        current.close()
        descriptor = null
    }

    fun markCommitted() {
        journal.remove(pendingVideo.operationId)
        localWorkingFile.delete()
    }
}

/** Reconciles only exact alpha19 pending URIs; it never scans or mutates SentryMode. */
private object LegacyUsbPendingRecordingRecovery {
    fun recoverMounted(context: Context) {
        val appContext = context.applicationContext
        val journal = UsbRecordingRecoveryJournal(appContext)
        val backend = UsbMediaStoreBackend(appContext)
        val targets = UsbExportVolumeResolver.mountedTargets(appContext)
        journal.entries().forEach { entry ->
            val target = targets.singleOrNull {
                it.storageUuid.equals(entry.storageUuid, ignoreCase = true) &&
                    it.volumeName.equals(entry.volumeName, ignoreCase = true)
            } ?: return@forEach
            UsbMutationCoordinator.withTarget(target.storageUuid) {
                val uri = Uri.parse(entry.itemUri)
                val metadata = runCatching { backend.metadata(uri) }.getOrNull()
                if (metadata == null) {
                    journal.remove(entry.operationId)
                    return@withTarget
                }
                val exact = metadata.volumeName.equals(entry.volumeName, ignoreCase = true) &&
                    metadata.relativePath?.trim()?.replace('\\', '/')?.trim('/')
                        .equals(UsbExportPolicy.RELATIVE_PATH.trim('/'), ignoreCase = true) &&
                    metadata.displayName == entry.requestedName &&
                    !metadata.ownerPackage.isNullOrBlank() &&
                    (entry.observedOwnerPackage == null || metadata.ownerPackage == entry.observedOwnerPackage)
                if (!exact) {
                    UsbFastTrackReportStore.append(
                        appContext,
                        UsbFastTrackEvent(
                            operationId = entry.operationId,
                            storageUuid = entry.storageUuid,
                            volumeName = entry.volumeName,
                            segmentNumber = entry.segmentNumber,
                            storageKind = "USB_MEDIASTORE",
                            state = "RECOVERY_IDENTITY_MISMATCH",
                            message = "Pending URI retained without mutation",
                        ),
                    )
                    return@withTarget
                }
                val alreadyCommitted = runCatching {
                    UsbSegmentCatalog(appContext, backend).snapshot(target).segments.any {
                        it.video.uri == uri
                    }
                }.getOrDefault(false)
                if (alreadyCommitted) {
                    journal.remove(entry.operationId)
                    return@withTarget
                }
                val result = backend.deleteOwned(
                    target,
                    UsbExportAsset(
                        kind = UsbExportAssetKind.VIDEO,
                        segmentNumber = entry.segmentNumber,
                        requestedName = entry.requestedName,
                        expectedBytes = metadata.sizeBytes ?: 0L,
                        expectedSha256 = "",
                        expectedMimeType = "video/mp4",
                        itemUri = entry.itemUri,
                        observedName = metadata.displayName,
                        observedRelativePath = metadata.relativePath,
                        observedVolumeName = metadata.volumeName,
                        observedSizeBytes = metadata.sizeBytes,
                        observedMimeType = metadata.mimeType,
                        observedPending = metadata.pending,
                        observedOwnerPackage = metadata.ownerPackage,
                    ),
                )
                if (result.deletionConfirmed) journal.remove(entry.operationId)
                UsbFastTrackReportStore.append(
                    appContext,
                    UsbFastTrackEvent(
                        operationId = entry.operationId,
                        storageUuid = entry.storageUuid,
                        volumeName = entry.volumeName,
                        segmentNumber = entry.segmentNumber,
                        storageKind = "USB_MEDIASTORE",
                        state = if (result.deletionConfirmed) "RECOVERY_CLEANED" else "RECOVERY_RETAINED",
                        message = result.error,
                    ),
                )
            }
        }
    }
}
