package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import android.net.Uri
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.recorder.ActualTrackInfo
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.service.recorder.UsbPendingRecordingAsset
import com.dante.zeekrcapabilitylab.service.recorder.UsbRecordingRecoveryJournal
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** One serialization point for every mutation of one physical removable volume. */
object UsbMutationCoordinator {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withTarget(storageUuid: String, block: () -> T): T =
        locks.getOrPut(storageUuid.lowercase()) { ReentrantLock() }.withLock(block)
}

/** Durable transfer tasks mirror their active leases here so quota cannot race an upload. */
object UsbBundleLeaseRegistry {
    private val leases = ConcurrentHashMap<String, Int>()

    private fun key(storageUuid: String, bundleId: String) =
        "${storageUuid.lowercase()}|${bundleId.lowercase()}"

    fun acquire(storageUuid: String, bundleId: String) {
        leases.compute(key(storageUuid, bundleId)) { _, count -> (count ?: 0) + 1 }
    }

    fun release(storageUuid: String, bundleId: String) {
        leases.computeIfPresent(key(storageUuid, bundleId)) { _, count ->
            (count - 1).takeIf { it > 0 }
        }
    }

    fun isLeased(storageUuid: String, bundleId: String): Boolean =
        (leases[key(storageUuid, bundleId)] ?: 0) > 0

    fun reset() {
        leases.clear()
    }
}

class UsbDirectRecordingCapabilityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "usb_direct_recording_capabilities",
        Context.MODE_PRIVATE,
    )

    private fun key(target: UsbExportTarget): String =
        "${BuildConfig.VERSION_CODE}|${target.storageUuid.lowercase()}|${target.volumeName.lowercase()}"

    fun requiresCanary(target: UsbExportTarget): Boolean = !prefs.getBoolean(key(target), false)

    fun markPassed(target: UsbExportTarget, observedOwner: String?) {
        prefs.edit()
            .putBoolean(key(target), true)
            .putString("${key(target)}|owner", observedOwner)
            .apply()
    }
}

data class UsbPendingVideo(
    val operationId: String,
    val bundleId: String,
    val target: UsbExportTarget,
    val logicalId: String,
    val recordingSessionId: String,
    val segmentNumber: Int,
    val recordingMode: String,
    val startedAtEpochMs: Long,
    val displayName: String,
    val itemUri: String,
    val observedOwnerPackage: String?,
)

data class UsbSegmentCommitResult(
    val bundleId: String,
    val contentKey: String,
    val videoFile: File?,
    val videoBytes: Long,
    val videoSha256: String,
    val observedOwnerPackage: String?,
    val track: UsbMediaStoreBackend.TrackMetadata,
)

/** Writes sidecar + segment manifest and publishes the manifest strictly last. */
class UsbSegmentCommitEngine(context: Context) {
    private val appContext = context.applicationContext
    private val backend = UsbMediaStoreBackend(appContext)
    private val recoveryJournal = UsbRecordingRecoveryJournal(appContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun commitDirect(
        pending: UsbPendingVideo,
        sidecar: SegmentSidecar,
        sourceKind: UsbSegmentSourceKind = UsbSegmentSourceKind.DIRECT_RECORDING,
        canaryRequired: Boolean = false,
    ): UsbSegmentCommitResult = UsbMutationCoordinator.withTarget(pending.target.storageUuid) {
        val videoUri = Uri.parse(pending.itemUri)
        val created = mutableListOf<UsbExportAsset>()
        try {
            val videoMetadata = backend.metadata(videoUri) ?: error("USB_VIDEO_QUERY_FAILED")
            require(videoMetadata.displayName == pending.displayName) { "USB_VIDEO_NAME_MISMATCH" }
            require(normalize(videoMetadata.relativePath) == normalize(UsbExportPolicy.RELATIVE_PATH)) {
                "USB_VIDEO_PATH_MISMATCH"
            }
            require(videoMetadata.volumeName.equals(pending.target.volumeName, ignoreCase = true)) {
                "USB_VIDEO_VOLUME_MISMATCH"
            }
            require(videoMetadata.pending == 1) { "USB_VIDEO_NOT_PENDING" }
            val videoHash = backend.hashUri(videoUri)
            require(videoHash.first > 0L) { "USB_VIDEO_EMPTY" }
            val track = backend.trackMetadata(videoUri) ?: error("USB_VIDEO_TRACK_UNREADABLE")
            require((track.width ?: 0) > 0 && (track.height ?: 0) > 0 && (track.durationMs ?: 0L) > 0L) {
                "USB_VIDEO_TRACK_INVALID"
            }
            created += ownedAsset(
                kind = UsbExportAssetKind.VIDEO,
                pending = pending,
                uri = videoUri,
                metadata = videoMetadata,
                bytes = videoHash.first,
                sha256 = videoHash.second,
                mimeType = "video/mp4",
            )

            val rawVideo = pending.target.directoryPath?.let { root ->
                File(root, "${UsbExportPolicy.RELATIVE_PATH}${pending.displayName}")
            }
            val enrichedSidecar = sidecar.copy(
                file = rawVideo?.absolutePath ?: pending.itemUri,
                fileBytes = videoHash.first,
                provisional = false,
                actualTrack = ActualTrackInfo(
                    width = track.width,
                    height = track.height,
                    bitrateBps = track.bitrateBps,
                    durationMs = track.durationMs,
                ),
            )
            val sidecarBytes = json.encodeToString(SegmentSidecar.serializer(), enrichedSidecar)
                .toByteArray(Charsets.UTF_8)
            val prefix = pending.displayName.removeSuffix(".mp4")
            val sidecarName = "$prefix.sidecar.json"
            val sidecarInserted = backend.insertPending(pending.target, sidecarName, "application/json")
            created += ownedAsset(
                UsbExportAssetKind.SIDECAR,
                pending,
                sidecarInserted.uri,
                sidecarInserted,
                sidecarInserted.sizeBytes ?: 0L,
                "",
                "application/json",
            )
            require(recoveryJournal.addAsset(
                pending.operationId,
                recoveryAsset(UsbExportAssetKind.SIDECAR, sidecarInserted, "application/json"),
            )) { "USB_RECOVERY_JOURNAL_MISSING:SIDECAR" }
            require(sidecarInserted.displayName == sidecarName) { "USB_SIDECAR_NAME_CONFLICT" }
            val sidecarWritten = backend.copyBytes(sidecarBytes, sidecarInserted.uri, { false }) {}
            val sidecarReread = backend.hashUri(sidecarInserted.uri)
            require(sidecarWritten == sidecarReread) { "USB_SIDECAR_VERIFY_FAILED" }
            created[created.lastIndex] = ownedAsset(
                UsbExportAssetKind.SIDECAR,
                pending,
                sidecarInserted.uri,
                requireNotNull(backend.metadata(sidecarInserted.uri)),
                sidecarReread.first,
                sidecarReread.second,
                "application/json",
            )

            val contentKey = UsbExportPolicy.contentKey(
                pending.logicalId,
                pending.segmentNumber,
                videoHash.second,
                sidecarReread.second,
            )
            val manifest = UsbPortableSegmentManifest(
                bundleId = pending.bundleId,
                contentKey = contentKey,
                sourceKind = sourceKind,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                buildGitSha = BuildConfig.GIT_SHA,
                logicalId = pending.logicalId,
                recordingSessionId = pending.recordingSessionId,
                segmentNumber = pending.segmentNumber,
                recordingMode = pending.recordingMode,
                startedAtEpochMs = pending.startedAtEpochMs,
                stoppedAtEpochMs = enrichedSidecar.stoppedAtEpochMs ?: System.currentTimeMillis(),
                protected = enrichedSidecar.protected,
                assets = created.map { asset ->
                    UsbPortableAsset(
                        kind = asset.kind,
                        segmentNumber = pending.segmentNumber,
                        displayName = asset.requestedName,
                        relativePath = UsbExportPolicy.RELATIVE_PATH,
                        sizeBytes = asset.expectedBytes,
                        sha256 = asset.expectedSha256,
                        mimeType = requireNotNull(asset.expectedMimeType),
                    )
                },
            )
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val manifestName = "$prefix.segment.json"
            val manifestInserted = backend.insertPending(pending.target, manifestName, "application/json")
            created += ownedAsset(
                UsbExportAssetKind.MANIFEST,
                pending,
                manifestInserted.uri,
                manifestInserted,
                manifestInserted.sizeBytes ?: 0L,
                "",
                "application/json",
            )
            require(recoveryJournal.addAsset(
                pending.operationId,
                recoveryAsset(UsbExportAssetKind.MANIFEST, manifestInserted, "application/json"),
            )) { "USB_RECOVERY_JOURNAL_MISSING:MANIFEST" }
            require(manifestInserted.displayName == manifestName) { "USB_MANIFEST_NAME_CONFLICT" }
            val manifestWritten = backend.copyBytes(manifestBytes, manifestInserted.uri, { false }) {}
            val manifestReread = backend.hashUri(manifestInserted.uri)
            require(manifestWritten == manifestReread) { "USB_MANIFEST_VERIFY_FAILED" }
            created[created.lastIndex] = ownedAsset(
                UsbExportAssetKind.MANIFEST,
                pending,
                manifestInserted.uri,
                requireNotNull(backend.metadata(manifestInserted.uri)),
                manifestReread.first,
                manifestReread.second,
                "application/json",
            )

            created.sortedBy { if (it.kind == UsbExportAssetKind.MANIFEST) 1 else 0 }.forEach { asset ->
                val uri = Uri.parse(requireNotNull(asset.itemUri))
                require(backend.publish(uri) > 0) { "USB_PUBLISH_FAILED:${asset.requestedName}" }
                val published = backend.metadata(uri) ?: error("USB_PUBLISHED_QUERY_FAILED")
                require(published.pending == 0 && published.sizeBytes == asset.expectedBytes) {
                    "USB_PUBLISHED_METADATA_MISMATCH:${asset.requestedName}"
                }
            }
            UsbFastTrackReportStore.append(
                appContext,
                UsbFastTrackEvent(
                    operationId = pending.operationId,
                    bundleId = pending.bundleId,
                    storageUuid = pending.target.storageUuid,
                    volumeName = pending.target.volumeName,
                    targetDescription = pending.target.description,
                    segmentNumber = pending.segmentNumber,
                    storageKind = "USB_MEDIASTORE",
                    state = if (canaryRequired) "CANARY_PASSED" else "COMMITTED",
                    canaryRequired = canaryRequired,
                    relativePath = UsbExportPolicy.RELATIVE_PATH,
                    requestedName = pending.displayName,
                    observedName = videoMetadata.displayName,
                    itemUri = pending.itemUri,
                    durationMs = track.durationMs,
                    trackWidth = track.width,
                    trackHeight = track.height,
                    trackDurationMs = track.durationMs,
                    published = true,
                    manifestPublishedLast = true,
                    bytes = videoHash.first,
                    sha256 = videoHash.second,
                    message = "manifest published last",
                ),
            )
            UsbSegmentCommitResult(
                bundleId = pending.bundleId,
                contentKey = contentKey,
                videoFile = rawVideo?.takeIf(File::isFile),
                videoBytes = videoHash.first,
                videoSha256 = videoHash.second,
                observedOwnerPackage = videoMetadata.ownerPackage,
                track = track,
            )
        } catch (failure: Throwable) {
            created.asReversed().forEach { backend.deleteOwned(pending.target, it) }
            UsbFastTrackReportStore.append(
                appContext,
                UsbFastTrackEvent(
                    operationId = pending.operationId,
                    bundleId = pending.bundleId,
                    storageUuid = pending.target.storageUuid,
                    volumeName = pending.target.volumeName,
                    targetDescription = pending.target.description,
                    segmentNumber = pending.segmentNumber,
                    storageKind = "USB_MEDIASTORE",
                    state = if (canaryRequired) "CANARY_FAILED" else "FAILED_RECOVERABLE",
                    canaryRequired = canaryRequired,
                    relativePath = UsbExportPolicy.RELATIVE_PATH,
                    requestedName = pending.displayName,
                    itemUri = pending.itemUri,
                    published = false,
                    manifestPublishedLast = false,
                    message = failure.message ?: failure.javaClass.simpleName,
                ),
            )
            throw failure
        }
    }

    private fun recoveryAsset(
        kind: UsbExportAssetKind,
        metadata: UsbMediaStoreBackend.Metadata,
        mimeType: String,
    ) = UsbPendingRecordingAsset(
        kind = kind,
        itemUri = metadata.uri.toString(),
        requestedName = requireNotNull(metadata.displayName),
        expectedMimeType = mimeType,
        observedOwnerPackage = metadata.ownerPackage,
    )

    private fun ownedAsset(
        kind: UsbExportAssetKind,
        pending: UsbPendingVideo,
        uri: Uri,
        metadata: UsbMediaStoreBackend.Metadata,
        bytes: Long,
        sha256: String,
        mimeType: String,
    ) = UsbExportAsset(
        kind = kind,
        segmentNumber = pending.segmentNumber,
        requestedName = requireNotNull(metadata.displayName),
        expectedBytes = bytes,
        expectedSha256 = sha256,
        expectedMimeType = mimeType,
        itemUri = uri.toString(),
        observedName = metadata.displayName,
        observedRelativePath = metadata.relativePath,
        observedVolumeName = metadata.volumeName,
        observedSizeBytes = metadata.sizeBytes,
        observedMimeType = metadata.mimeType,
        observedPending = metadata.pending,
        observedOwnerPackage = metadata.ownerPackage,
        rereadBytes = bytes,
        rereadSha256 = sha256,
        copiedBytes = bytes,
        verified = true,
    )

    private fun normalize(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase()
}

@Serializable
data class UsbFastTrackEvent(
    val occurredAtEpochMs: Long = System.currentTimeMillis(),
    val operationId: String? = null,
    val bundleId: String? = null,
    val storageUuid: String? = null,
    val volumeName: String? = null,
    val targetDescription: String? = null,
    val segmentNumber: Int? = null,
    val storageKind: String,
    val state: String,
    val canaryRequired: Boolean? = null,
    val relativePath: String? = null,
    val requestedName: String? = null,
    val observedName: String? = null,
    val itemUri: String? = null,
    val durationMs: Long? = null,
    val trackWidth: Int? = null,
    val trackHeight: Int? = null,
    val trackDurationMs: Long? = null,
    val published: Boolean? = null,
    val manifestPublishedLast: Boolean? = null,
    val quotaBytes: Long? = null,
    val quotaBeforeBytes: Long? = null,
    val quotaRequiredBytes: Long? = null,
    val quotaAfterBytes: Long? = null,
    val reclaimedBytes: Long? = null,
    val fallbackConsumed: Boolean? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
    val message: String? = null,
)

@Serializable
data class UsbFastTrackReport(
    val schemaVersion: Int = 1,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
    val buildVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    val buildGitSha: String = BuildConfig.GIT_SHA,
    val sentryModeMutationCount: Int = 0,
    val recoveryJournalPendingCount: Int = 0,
    val events: List<UsbFastTrackEvent> = emptyList(),
)

object UsbFastTrackReportStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private const val MAX_EVENTS = 200

    @Synchronized
    fun append(context: Context, event: UsbFastTrackEvent) {
        val file = file(context)
        val current = read(context)
        write(file, current.copy(events = (current.events + event).takeLast(MAX_EVENTS)))
    }

    @Synchronized
    fun reportJson(context: Context): String = json.encodeToString(
        read(context).copy(
            generatedAtEpochMs = System.currentTimeMillis(),
            recoveryJournalPendingCount = UsbRecordingRecoveryJournal(context).entries().size,
        ),
    )

    private fun read(context: Context): UsbFastTrackReport {
        val file = file(context)
        if (!file.isFile) return UsbFastTrackReport()
        return runCatching { json.decodeFromString<UsbFastTrackReport>(file.readText()) }
            .getOrDefault(UsbFastTrackReport())
    }

    private fun write(file: File, report: UsbFastTrackReport) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeText(json.encodeToString(report))
        runCatching {
            Files.move(
                partial.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(context: Context) =
        File(context.applicationContext.filesDir, "recordings/usb-fast-track-report.json")
}
