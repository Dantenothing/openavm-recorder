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
    private val onUnconfirmedDescriptorClose: ((ParcelFileDescriptor) -> Unit)? = null,
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
        val marker = File(tokenDir, "$operationId.token")
        try {
        tokenDir.mkdirs()
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
        } catch (failure: Throwable) {
            // No recorder/camera has seen this descriptor yet. Do not leak it if local journal storage fails.
            runCatching { descriptor.close() }.onFailure {
                failure.addSuppressed(it)
                onUnconfirmedDescriptorClose?.invoke(descriptor)
            }
            runCatching { cleanupInserted(inserted, segmentNumber) }
            runCatching { journal.remove(operationId); marker.delete() }
            throw failure
        }
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
    override val nativeReleaseConfirmed: Boolean get() = descriptor == null

    override fun appendFinalMetadata(document: String) {
        check(nativeReleaseConfirmed) { "PRODUCT_DESCRIPTOR_STILL_OWNED" }
        UsbMutationCoordinator.withTarget(target.storageUuid) {
            requireNotNull(appContext.contentResolver.openFileDescriptor(Uri.parse(pendingVideo.itemUri), "rw")).use { fd ->
                ContinuousFileMetadata.append(RecordingDescriptorChannel(fd.fileDescriptor), document)
                android.system.Os.fsync(fd.fileDescriptor)
            }
        }
    }

    override fun bind(recorder: MediaRecorder) {
        recorder.setOutputFile(requireNotNull(descriptor).fileDescriptor)
    }

    fun bindNext(recorder: MediaRecorder) {
        recorder.setNextOutputFile(requireNotNull(descriptor).fileDescriptor)
    }

    /** Local metadata only. Never reads, publishes, renames or syncs the active video. */
    fun checkpointNative(sidecar: SegmentSidecar) {
        check(journal.checkpointNative(pendingVideo.operationId, sidecar)) { "NATIVE_OUTPUT_JOURNAL_MISSING" }
    }

    fun clearNativeCheckpoint() = journal.clearNativeCheckpoint(pendingVideo.operationId)

    fun bytesAfterRecorderRelease(): Long? = backend.closedFileLength(Uri.parse(pendingVideo.itemUri))

    /** Muxer owns its native duplicate; this handle still owns durability/FD/journal lifetime. */
    override fun createMuxer(): android.media.MediaMuxer = android.media.MediaMuxer(requireNotNull(descriptor).fileDescriptor,
        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

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
        runCatching { UsbPendingRecordingRecoveryEngine.recoverMounted(appContext, pendingVideo.operationId) }
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

/** A seekable view of the granted descriptor. Does not reopen a provider path or own the FD. */
private class RecordingDescriptorChannel(private val descriptor: java.io.FileDescriptor) : java.nio.channels.SeekableByteChannel {
    private var open = true
    override fun isOpen() = open && descriptor.valid()
    override fun close() { open = false }
    private fun checkOpen() { check(isOpen) { "PRODUCT_METADATA_DESCRIPTOR_CLOSED" } }
    override fun position(): Long { checkOpen(); return android.system.Os.lseek(descriptor, 0, android.system.OsConstants.SEEK_CUR) }
    override fun position(newPosition: Long): java.nio.channels.SeekableByteChannel {
        require(newPosition >= 0); checkOpen(); android.system.Os.lseek(descriptor, newPosition, android.system.OsConstants.SEEK_SET); return this
    }
    override fun size(): Long { checkOpen(); return android.system.Os.fstat(descriptor).st_size }
    override fun truncate(size: Long): java.nio.channels.SeekableByteChannel = error("PRODUCT_METADATA_TRUNCATE_FORBIDDEN")
    override fun read(dst: java.nio.ByteBuffer): Int {
        checkOpen(); if (!dst.hasRemaining()) return 0
        return android.system.Os.read(descriptor, dst).let { if (it == 0) -1 else it }
    }
    override fun write(src: java.nio.ByteBuffer): Int { checkOpen(); return android.system.Os.write(descriptor, src) }
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
