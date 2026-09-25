package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import android.net.Uri
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStorageLock
import java.io.File
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UsbExportEngine(context: Context) {
    private val appContext = context.applicationContext
    private val backend = UsbMediaStoreBackend(appContext)
    private val catalog = UsbExportCatalog(appContext, backend)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun execute(taskId: String) {
        var task = UsbExportRepository.get(taskId) ?: return
        if (com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.kind == "MULTI") {
            UsbExportRepository.update(task.copy(state = UsbExportState.FAILED_RECOVERABLE, errorCode = "MULTI_RECORDING_ACTIVE", message = "Stop the two-camera test before exporting"))
            return
        }
        if (task.state == UsbExportState.CANCEL_REQUESTED) {
            cancelBeforeStart(task)
            return
        }
        val target = UsbExportVolumeResolver.resolveExact(appContext, task.target)
        if (target == null) {
            UsbExportPinRegistry.release(task.id)
            UsbExportRepository.update(
                task.copy(
                    state = UsbExportState.WAITING_FOR_TARGET,
                    errorCode = "TARGET_NOT_MOUNTED",
                    message = "Reconnect the same USB drive and retry",
                ),
            )
            return
        }
        task = task.copy(target = target)

        if (task.assets.isNotEmpty()) {
            val cleaned = if (task.assets.any { it.itemUri != null && !it.deletionConfirmed }) {
                cleanup(task, target)
            } else {
                task
            }
            if (cleaned.assets.any { it.itemUri != null && !it.deletionConfirmed }) {
                UsbExportPinRegistry.release(task.id)
                UsbExportRepository.update(
                    cleaned.copy(
                        state = UsbExportState.FAILED_RECOVERABLE,
                        errorCode = "STALE_DESTINATION_CLEANUP_FAILED",
                        message = "The same USB is present, but a previous owned item could not be cleaned",
                    ),
                )
                return
            }
            task = cleaned.copy(assets = emptyList(), progressBytes = 0L)
            UsbExportRepository.update(task)
        }

        val files = task.sources.map { File(it.path) }
        val pinned = synchronized(RecorderStorageLock.lock) {
            val valid = task.sources.zip(files).all { (snapshot, file) ->
                RecorderLibrary.isManaged(file) &&
                    file.length() == snapshot.sizeBytes &&
                    file.lastModified() == snapshot.lastModifiedMs
            }
            if (valid) UsbExportPinRegistry.acquire(task.id, files)
            valid
        }
        if (!pinned) {
            UsbExportPinRegistry.release(task.id)
            UsbExportRepository.update(
                task.copy(
                    state = UsbExportState.FAILED,
                    errorCode = "SOURCE_CHANGED",
                    message = "A selected recording is missing, changed, or no longer finalized",
                ),
            )
            return
        }

        try {
            task = persist(
                task.copy(
                    state = UsbExportState.PREPARING_SOURCE,
                    errorCode = null,
                    message = "Hashing finalized source files",
                    progressBytes = 0L,
                ),
            )
            val hashed = task.sources.mapIndexed { index, source ->
                val file = File(source.path)
                val sourceHash = backend.hashFile(
                    file = file,
                    cancelled = { cancelled(task.id) },
                    onBytes = {},
                )
                if (file.length() != source.sizeBytes || file.lastModified() != source.lastModifiedMs) {
                    throw UsbExportFailure("SOURCE_CHANGED", "Source changed while hashing")
                }
                source.copy(
                    sourceSha256 = sourceHash,
                    sidecarSha256 = UsbExportPolicy.sha256(source.sidecarJson.toByteArray(Charsets.UTF_8)),
                ).also {
                    task = persist(
                        task.copy(
                            sources = task.sources.toMutableList().apply { this[index] = it },
                            message = "Hashed ${index + 1}/${task.sources.size} source files",
                        ),
                    )
                }
            }
            val exportKey = UsbExportPolicy.exportKey(task.logicalId, hashed)
            val sourceSidecar = hashed.singleOrNull()?.let { source ->
                runCatching {
                    json.decodeFromString<com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar>(source.sidecarJson)
                }.getOrNull()
            }
            val bundleId = if (task.bundleFormat == UsbExportBundleFormat.SEGMENT_BUNDLE) {
                UsbExportPolicy.bundleId(
                    task.logicalId,
                    hashed.single().segmentNumber,
                    sourceSidecar?.startedAtEpochMs ?: task.startedAtEpochMs,
                )
            } else exportKey
            val prefix = UsbExportPolicy.prefix(
                sourceSidecar?.startedAtEpochMs ?: task.startedAtEpochMs,
                bundleId,
            )
            val payloads = buildPayloads(prefix, hashed, task.bundleFormat)
            val portableAssets = payloads.map {
                UsbPortableAsset(
                    kind = it.kind,
                    segmentNumber = it.segmentNumber,
                    displayName = it.name,
                    relativePath = UsbExportPolicy.RELATIVE_PATH,
                    sizeBytes = it.bytes,
                    sha256 = it.sha256,
                    mimeType = it.mimeType,
                )
            }
            val manifestBytes = if (task.bundleFormat == UsbExportBundleFormat.SEGMENT_BUNDLE) {
                val source = hashed.single()
                val sidecar = requireNotNull(sourceSidecar) { "Segment sidecar is unavailable" }
                json.encodeToString(
                    UsbPortableSegmentManifest(
                        bundleId = bundleId,
                        contentKey = UsbExportPolicy.contentKey(
                            task.logicalId,
                            source.segmentNumber,
                            requireNotNull(source.sourceSha256),
                            requireNotNull(source.sidecarSha256),
                        ),
                        sourceKind = UsbSegmentSourceKind.MANUAL_EXPORT,
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        buildGitSha = BuildConfig.GIT_SHA,
                        logicalId = task.logicalId,
                        recordingSessionId = sidecar.recordingSessionId ?: task.logicalId.removePrefix("session:"),
                        segmentNumber = source.segmentNumber,
                        recordingMode = task.recordingMode,
                        startedAtEpochMs = sidecar.startedAtEpochMs ?: task.startedAtEpochMs,
                        stoppedAtEpochMs = sidecar.stoppedAtEpochMs ?: task.stoppedAtEpochMs,
                        protected = sidecar.protected,
                        assets = portableAssets,
                    ),
                ).toByteArray(Charsets.UTF_8)
            } else {
                json.encodeToString(
                    UsbPortableManifest(
                        exportKey = exportKey,
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        buildGitSha = BuildConfig.GIT_SHA,
                        logicalId = task.logicalId,
                        recordingMode = task.recordingMode,
                        startedAtEpochMs = task.startedAtEpochMs,
                        stoppedAtEpochMs = task.stoppedAtEpochMs,
                        segmentCount = hashed.size,
                        assets = portableAssets,
                    ),
                ).toByteArray(Charsets.UTF_8)
            }
            val manifestPayload = Payload(
                kind = UsbExportAssetKind.MANIFEST,
                segmentNumber = hashed.singleOrNull()?.segmentNumber,
                name = if (task.bundleFormat == UsbExportBundleFormat.SEGMENT_BUNDLE) {
                    "$prefix.segment.json"
                } else UsbExportPolicy.manifestName(prefix),
                mimeType = "application/json",
                bytes = manifestBytes.size.toLong(),
                sha256 = UsbExportPolicy.sha256(manifestBytes),
                byteContent = manifestBytes,
            )
            val totalBytes = payloads.sumOf { it.bytes } + manifestPayload.bytes
            task = persist(
                task.copy(
                    sources = hashed,
                    exportKey = exportKey,
                    exportPrefix = prefix,
                    totalBytes = totalBytes,
                    message = "Source identity prepared",
                ),
            )

            val freshTarget = UsbExportVolumeResolver.resolveExact(appContext, target)
                ?: throw UsbExportFailure("TARGET_NOT_MOUNTED", "USB was removed before copy")
            task = persist(task.copy(target = freshTarget, targetTotalBytes = freshTarget.totalBytes))

            val existingManifest = backend.findPublished(freshTarget, manifestPayload.name)
            if (existingManifest != null) {
                if (verifyExisting(existingManifest.uri, manifestBytes, payloads, freshTarget)) {
                    val existingAssets = (payloads + manifestPayload).map { payload ->
                        val metadata = backend.findPublished(freshTarget, payload.name)
                        UsbExportAsset(
                            kind = payload.kind,
                            segmentNumber = payload.segmentNumber,
                            requestedName = payload.name,
                            expectedBytes = payload.bytes,
                            expectedSha256 = payload.sha256,
                            expectedMimeType = payload.mimeType,
                            itemUri = metadata?.uri?.toString(),
                            observedName = metadata?.displayName,
                            observedRelativePath = metadata?.relativePath,
                            observedVolumeName = metadata?.volumeName,
                            observedSizeBytes = metadata?.sizeBytes,
                            observedMimeType = metadata?.mimeType,
                            observedPending = metadata?.pending,
                            observedOwnerPackage = metadata?.ownerPackage,
                            copiedBytes = payload.bytes,
                            verified = metadata != null,
                            published = metadata?.pending == 0,
                        )
                    }
                    task = task.copy(
                        state = UsbExportState.ALREADY_EXPORTED,
                        assets = existingAssets,
                        progressBytes = totalBytes,
                        message = "The same verified export already exists; no duplicate was created",
                    )
                    UsbExportRepository.update(task)
                    return
                }
                throw UsbExportFailure("DESTINATION_CONFLICT", "A manifest with the deterministic name exists but does not verify")
            }

            val quota = if (task.bundleFormat == UsbExportBundleFormat.SEGMENT_BUNDLE) {
                UsbSegmentRetentionManager(appContext).admit(
                    target = freshTarget,
                    incomingBytes = totalBytes,
                    quotaBytes = task.openAvmQuotaBytes,
                    protectedBundleId = bundleId,
                )
            } else catalog.enforceQuotaBeforeExport(
                target = freshTarget,
                incomingBytes = totalBytes,
                protectedExportKey = exportKey,
            )
            val capacityTarget = UsbExportVolumeResolver.resolveExact(appContext, freshTarget)
                ?: throw UsbExportFailure("TARGET_NOT_MOUNTED", "USB was removed before copy")
            task = persist(
                task.copy(
                    target = capacityTarget,
                    targetTotalBytes = capacityTarget.totalBytes,
                    freeBytesAtStart = capacityTarget.freeBytes,
                    minimumObservedFreeBytes = capacityTarget.freeBytes,
                    openAvmBytesBefore = quota.ownedBytesBefore,
                    quotaReclaimedBytes = quota.reclaimedBytes,
                ),
            )

            fun requireCapacity(remainingBytes: Long): UsbExportTarget {
                val current = UsbExportVolumeResolver.resolveExact(appContext, capacityTarget)
                    ?: throw UsbExportFailure("TARGET_NOT_MOUNTED", "USB was removed during export")
                val free = current.freeBytes
                val minimum = listOfNotNull(task.minimumObservedFreeBytes, free).minOrNull()
                task = persist(
                    task.copy(
                        target = current,
                        targetTotalBytes = current.totalBytes,
                        minimumObservedFreeBytes = minimum,
                    ),
                )
                if (!UsbExportPolicy.hasSpace(free, remainingBytes.coerceAtLeast(0L))) {
                    throw UsbExportFailure(
                        "INSUFFICIENT_SPACE",
                        "USB free space fell below the remaining payload plus the 1 GiB safety margin",
                    )
                }
                return current
            }

            requireCapacity(totalBytes)
            var completedBeforeAsset = 0L
            for (payload in payloads + manifestPayload) {
                if (cancelled(task.id)) throw UsbExportCancelledException()
                val currentTarget = requireCapacity(totalBytes - completedBeforeAsset)
                val stage = if (payload.kind == UsbExportAssetKind.MANIFEST) {
                    UsbExportState.VERIFYING
                } else {
                    UsbExportState.COPYING
                }
                task = persist(task.copy(state = stage, message = "Writing ${payload.name}"))
                val inserted = backend.insertPending(currentTarget, payload.name, payload.mimeType)
                var asset = UsbExportAsset(
                    kind = payload.kind,
                    segmentNumber = payload.segmentNumber,
                    requestedName = payload.name,
                    expectedBytes = payload.bytes,
                    expectedSha256 = payload.sha256,
                    expectedMimeType = payload.mimeType,
                    itemUri = inserted.uri.toString(),
                    observedName = inserted.displayName,
                    observedRelativePath = inserted.relativePath,
                    observedVolumeName = inserted.volumeName,
                    observedSizeBytes = inserted.sizeBytes,
                    observedMimeType = inserted.mimeType,
                    observedPending = inserted.pending,
                    observedOwnerPackage = inserted.ownerPackage,
                )
                task = persist(task.copy(assets = task.assets + asset))
                if (inserted.displayName != payload.name) {
                    throw UsbExportFailure("PROVIDER_NAME_CONFLICT", "MediaStore changed the deterministic destination name")
                }
                var lastPersisted = 0L
                var lastCapacityCheck = 0L
                val onBytes: (Long) -> Unit = { copied ->
                    if (copied - lastCapacityCheck >= CAPACITY_CHECK_STEP_BYTES || copied == payload.bytes) {
                        lastCapacityCheck = copied
                        requireCapacity(totalBytes - completedBeforeAsset - copied)
                    }
                    if (copied - lastPersisted >= PROGRESS_STEP_BYTES || copied == payload.bytes) {
                        lastPersisted = copied
                        asset = asset.copy(copiedBytes = copied)
                        task = persist(
                            task.copy(
                                progressBytes = completedBeforeAsset + copied,
                                assets = task.assets.map { if (it.itemUri == asset.itemUri) asset else it },
                            ),
                        )
                    }
                }
                val copied = when {
                    payload.file != null -> backend.copyFile(payload.file, inserted.uri, { cancelled(task.id) }, onBytes)
                    payload.byteContent != null -> backend.copyBytes(payload.byteContent, inserted.uri, { cancelled(task.id) }, onBytes)
                    else -> error("Payload has no source")
                }
                if (copied.first != payload.bytes || copied.second != payload.sha256) {
                    throw UsbExportFailure("COPY_HASH_MISMATCH", "Copied bytes do not match the source identity")
                }
                task = persist(task.copy(state = UsbExportState.VERIFYING, message = "Verifying ${payload.name}"))
                val reread = backend.hashUri(inserted.uri) { cancelled(task.id) }
                val metadata = backend.metadata(inserted.uri)
                    ?: throw UsbExportFailure("DESTINATION_QUERY_FAILED", "Written item cannot be queried")
                val verificationFailures = buildList {
                    if (reread.first != payload.bytes) add("REREAD_SIZE")
                    if (reread.second != payload.sha256) add("REREAD_SHA256")
                    if (metadata.displayName != payload.name) add("DISPLAY_NAME")
                    if (normalize(metadata.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH)) add("RELATIVE_PATH")
                    if (!metadata.volumeName.equals(freshTarget.volumeName, ignoreCase = true)) add("VOLUME_NAME")
                    if (metadata.pending != 1) add("IS_PENDING")
                }
                asset = asset.copy(
                    observedName = metadata.displayName,
                    observedRelativePath = metadata.relativePath,
                    observedVolumeName = metadata.volumeName,
                    observedSizeBytes = metadata.sizeBytes,
                    observedMimeType = metadata.mimeType,
                    observedPending = metadata.pending,
                    observedOwnerPackage = metadata.ownerPackage,
                    rereadBytes = reread.first,
                    rereadSha256 = reread.second,
                    verificationFailures = verificationFailures,
                )
                task = persist(
                    task.copy(
                        assets = task.assets.map { if (it.itemUri == asset.itemUri) asset else it },
                    ),
                )
                if (verificationFailures.isNotEmpty()) {
                    throw UsbExportFailure("DESTINATION_VERIFY_FAILED", "Destination reread or metadata verification failed")
                }
                asset = asset.copy(
                    copiedBytes = payload.bytes,
                    verified = true,
                    verificationFailures = emptyList(),
                )
                completedBeforeAsset += payload.bytes
                task = persist(
                    task.copy(
                        progressBytes = completedBeforeAsset,
                        assets = task.assets.map { if (it.itemUri == asset.itemUri) asset else it },
                    ),
                )
            }

            requireCapacity(0L)
            task = persist(task.copy(state = UsbExportState.PUBLISHING, message = "Publishing verified files"))
            val ordered = task.assets.sortedBy { if (it.kind == UsbExportAssetKind.MANIFEST) 1 else 0 }
            for (asset in ordered) {
                if (cancelled(task.id)) throw UsbExportCancelledException()
                val uri = Uri.parse(asset.itemUri ?: throw UsbExportFailure("MISSING_DESTINATION_URI", "Destination URI is missing"))
                if (backend.publish(uri) <= 0) {
                    throw UsbExportFailure("PUBLISH_FAILED", "MediaStore did not publish ${asset.requestedName}")
                }
                val metadata = backend.metadata(uri)
                    ?: throw UsbExportFailure("PUBLISHED_QUERY_FAILED", "Published item cannot be queried")
                val publicationFailures = buildList {
                    if (metadata.pending != 0) add("IS_PENDING")
                    if (metadata.displayName != asset.requestedName) add("DISPLAY_NAME")
                    if (metadata.sizeBytes != asset.expectedBytes) add("SIZE")
                    if (asset.expectedMimeType != null && metadata.mimeType != asset.expectedMimeType) add("MIME_TYPE")
                    if (normalize(metadata.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH)) add("RELATIVE_PATH")
                    if (!metadata.volumeName.equals(freshTarget.volumeName, ignoreCase = true)) add("VOLUME_NAME")
                }
                val publishedAsset = asset.copy(
                    observedName = metadata.displayName,
                    observedRelativePath = metadata.relativePath,
                    observedVolumeName = metadata.volumeName,
                    observedSizeBytes = metadata.sizeBytes,
                    observedMimeType = metadata.mimeType,
                    observedPending = metadata.pending,
                    observedOwnerPackage = metadata.ownerPackage,
                    published = publicationFailures.isEmpty(),
                    verificationFailures = publicationFailures,
                )
                task = persist(
                    task.copy(
                        assets = task.assets.map {
                            if (it.itemUri == asset.itemUri) publishedAsset else it
                        },
                    ),
                )
                if (publicationFailures.isNotEmpty()) {
                    throw UsbExportFailure("PUBLISHED_METADATA_MISMATCH", "Published metadata does not match")
                }
            }
            UsbExportRepository.update(
                task.copy(
                    state = UsbExportState.COMPLETED,
                    progressBytes = totalBytes,
                    errorCode = null,
                    message = "Export verified; manifest published last; internal sources kept",
                ),
            )
        } catch (_: UsbExportCancelledException) {
            failAndCleanup(task.id, target, "CANCELLED", "Export cancelled by user", cancelled = true)
        } catch (failure: UsbExportFailure) {
            failAndCleanup(task.id, target, failure.code, failure.message ?: failure.code)
        } catch (failure: UsbExportQuotaException) {
            failAndCleanup(task.id, target, failure.code, failure.message ?: failure.code)
        } catch (t: Throwable) {
            failAndCleanup(
                task.id,
                target,
                "UNEXPECTED_EXPORT_FAILURE",
                safeMessage(task, target, t),
            )
        } finally {
            UsbExportPinRegistry.release(task.id)
        }
    }

    private fun buildPayloads(prefix: String, sources: List<UsbExportSourceSnapshot>, format: UsbExportBundleFormat): List<Payload> =
        sources.flatMapIndexed { index, source ->
            val ordinal = if (format == UsbExportBundleFormat.SEGMENT_BUNDLE) source.segmentNumber else index + 1
            listOf(
                Payload(
                    kind = UsbExportAssetKind.VIDEO,
                    segmentNumber = source.segmentNumber,
                    name = UsbExportPolicy.videoName(prefix, ordinal),
                    mimeType = "video/mp4",
                    bytes = source.sizeBytes,
                    sha256 = requireNotNull(source.sourceSha256),
                    file = File(source.path),
                ),
                Payload(
                    kind = UsbExportAssetKind.SIDECAR,
                    segmentNumber = source.segmentNumber,
                    name = UsbExportPolicy.sidecarName(prefix, ordinal),
                    mimeType = "application/json",
                    bytes = source.sidecarJson.toByteArray(Charsets.UTF_8).size.toLong(),
                    sha256 = requireNotNull(source.sidecarSha256),
                    byteContent = source.sidecarJson.toByteArray(Charsets.UTF_8),
                ),
            )
        }

    private fun verifyExisting(
        manifestUri: Uri,
        expectedManifestBytes: ByteArray,
        payloads: List<Payload>,
        target: UsbExportTarget,
    ): Boolean = runCatching {
        if (UsbExportPolicy.sha256(backend.readBytes(manifestUri)) !=
            UsbExportPolicy.sha256(expectedManifestBytes)
        ) return@runCatching false
        payloads.all { asset ->
            val metadata = backend.findPublished(target, asset.name) ?: return@all false
            val reread = backend.hashUri(metadata.uri)
            metadata.sizeBytes == asset.bytes &&
                reread.first == asset.bytes &&
                reread.second == asset.sha256
        }
    }.getOrDefault(false)

    private fun cancelBeforeStart(task: UsbExportTask) {
        val target = UsbExportVolumeResolver.resolveExact(appContext, task.target)
        val cleaned = if (target != null) cleanup(task, target) else task
        val cleanupPending = cleaned.assets.any { it.itemUri != null && !it.deletionConfirmed }
        UsbExportRepository.update(
            cleaned.copy(
                state = if (cleanupPending) UsbExportState.FAILED_RECOVERABLE else UsbExportState.CANCELLED,
                errorCode = if (cleanupPending) "CANCEL_CLEANUP_WAITING_FOR_TARGET" else null,
                message = if (cleanupPending) "Reconnect the same USB to clean the cancelled export" else "Export cancelled",
            ),
        )
        UsbExportPinRegistry.release(task.id)
    }

    private fun failAndCleanup(
        taskId: String,
        target: UsbExportTarget,
        code: String,
        message: String,
        cancelled: Boolean = false,
    ) {
        val latest = UsbExportRepository.get(taskId) ?: return
        val exactTarget = UsbExportVolumeResolver.resolveExact(appContext, target)
        val cleaned = if (exactTarget != null) cleanup(latest, exactTarget) else latest
        val cleanupPending = cleaned.assets.any { it.itemUri != null && !it.deletionConfirmed }
        UsbExportRepository.update(
            cleaned.copy(
                state = when {
                    cleanupPending -> UsbExportState.FAILED_RECOVERABLE
                    cancelled -> UsbExportState.CANCELLED
                    else -> UsbExportState.FAILED_RECOVERABLE
                },
                errorCode = code,
                message = if (cleanupPending) "$message; reconnect the same USB for exact cleanup" else message,
            ),
        )
    }

    private fun cleanup(task: UsbExportTask, target: UsbExportTarget): UsbExportTask {
        var current = task.copy(state = UsbExportState.CLEANING_UP, message = "Cleaning exact task-owned MediaStore items")
        UsbExportRepository.update(current)
        for (asset in current.assets) {
            if (asset.itemUri == null || asset.deletionConfirmed) continue
            val cleaned = backend.deleteOwned(target, asset)
            current = current.copy(
                assets = current.assets.map { if (it.itemUri == asset.itemUri) cleaned else it },
            )
            UsbExportRepository.update(current)
        }
        return current
    }

    private fun persist(task: UsbExportTask): UsbExportTask {
        if (cancelled(task.id)) throw UsbExportCancelledException()
        UsbExportRepository.update(task)
        return task
    }

    private fun cancelled(taskId: String): Boolean = UsbExportRepository.cancelRequested(taskId)

    private fun safeMessage(task: UsbExportTask, target: UsbExportTarget, failure: Throwable): String {
        var message = "${failure.javaClass.simpleName}: ${failure.message ?: "unknown"}"
        task.sources.forEach { source ->
            message = message.replace(source.path, "<internal-recording>", ignoreCase = true)
        }
        target.directoryPath?.let { path ->
            message = message.replace(path, "<usb-volume>", ignoreCase = true)
        }
        return message
    }

    private fun normalize(path: String?): String? =
        path?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    private data class Payload(
        val kind: UsbExportAssetKind,
        val segmentNumber: Int?,
        val name: String,
        val mimeType: String,
        val bytes: Long,
        val sha256: String,
        val file: File? = null,
        val byteContent: ByteArray? = null,
    )

    private class UsbExportFailure(val code: String, message: String) : RuntimeException(message)

    companion object {
        private const val PROGRESS_STEP_BYTES = 8L * 1024L * 1024L
        private const val CAPACITY_CHECK_STEP_BYTES = 32L * 1024L * 1024L
    }
}
