package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.diagnostic.UsbSentryEvent
import com.dante.zeekrcapabilitylab.diagnostic.ZeekrSentryInventory
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAssetKind
import com.dante.zeekrcapabilitylab.usbexport.OpenAvmOwnedUnitKind
import com.dante.zeekrcapabilitylab.usbexport.OpenAvmOwnedUnitRef
import com.dante.zeekrcapabilitylab.usbexport.UsbExportCatalog
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import com.dante.zeekrcapabilitylab.usbexport.UsbPortableManifest
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCatalog
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentSourceKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class VehicleMediaCategory { ALL, NORMAL, TIME_LAPSE, EVENTS, OPENAVM_SENTRY, SENTRY }

enum class VehicleMediaOrigin { INTERNAL, OPENAVM_USB, FACTORY_SENTRY_USB }

enum class MediaAvailability { ONLINE, OFFLINE }

enum class SentryTimestampSource { EVENT_DIRECTORY, INFO_METADATA, FILE_MODIFIED }

enum class CompositeLayoutKind {
    SINGLE,
    FOUR_LANE_HORIZONTAL,
    FOUR_LANE_VERTICAL,
    FOUR_LANE_GRID_2X2,
}

data class VehicleUsbRecording(
    val stableKey: String,
    val logicalId: String,
    val storageUuid: String,
    val storageDescription: String,
    val category: VehicleMediaCategory,
    val origin: VehicleMediaOrigin = VehicleMediaOrigin.OPENAVM_USB,
    val availability: MediaAvailability,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val totalBytes: Long,
    val files: List<File>,
    val segmentDurationsMs: Map<String, Long> = emptyMap(),
    val sourceRole: RecordingSourceRole,
    val layoutKind: RecordingLayoutKind,
    val timeLapseMultiplier: Int,
    val containsDirectRecording: Boolean = false,
    val protectedSegments: Int = 0,
    val eventTimes: List<Long> = emptyList(),
    val ownedUnits: List<OpenAvmOwnedUnitRef> = emptyList(),
)

data class VehicleSentryRecording(
    val stableKey: String,
    val storageUuid: String,
    val storageDescription: String,
    val eventId: String,
    val origin: VehicleMediaOrigin = VehicleMediaOrigin.FACTORY_SENTRY_USB,
    val availability: MediaAvailability,
    val startedAtEpochMs: Long,
    val timestampSource: SentryTimestampSource,
    val timestampMismatch: Boolean = false,
    val totalBytes: Long,
    val videoFile: File?,
    val thumbnailFile: File?,
    val complete: Boolean,
    val layout: CompositeLayoutKind = CompositeLayoutKind.FOUR_LANE_GRID_2X2,
)

data class VehicleUsbMediaSnapshot(
    val openAvmRecordings: List<VehicleUsbRecording> = emptyList(),
    val sentryRecordings: List<VehicleSentryRecording> = emptyList(),
    val mountedVolumeCount: Int = 0,
    val scanErrors: List<String> = emptyList(),
)

/**
 * Read-only vehicle library for removable OpenAVM exports and factory SentryMode events.
 * Existing videos/metadata are read-only. Durable bookmark intents may finish publication on USB reconnect.
 */
object VehicleUsbMediaLibrary {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun refresh(context: Context): VehicleUsbMediaSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val previous = readCache(appContext).volumes.associateBy { it.storageUuid }.toMutableMap()
        val targets = UsbExportVolumeResolver.mountedTargets(appContext)
        val errors = mutableListOf<String>()

        targets.forEach { target ->
            if (!com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active) {
                runCatching { com.dante.zeekrcapabilitylab.usbexport.UsbIncidentMarkers(appContext).synchronizePending(target) }
            }
            val old = previous[target.storageUuid]
            val root = target.directoryPath?.let(::File)
            val sentryEvents = if (root != null) {
                runCatching { ZeekrSentryInventory.scan(root, target.storageUuid) }
                    .onFailure { errors += "${target.description}:SENTRY:${describe(it)}" }
                    .getOrNull()
                    ?.events
                    ?: old?.sentryEvents.orEmpty()
            } else {
                old?.sentryEvents.orEmpty()
            }
            val openAvm = runCatching { scanOpenAvm(appContext, target) }
                .onFailure { errors += "${target.description}:OPENAVM:${describe(it)}" }
                .getOrNull()
                ?: old?.openAvmRecordings.orEmpty()

            previous[target.storageUuid] = CachedUsbVolume(
                storageUuid = target.storageUuid,
                description = target.description,
                sentryEvents = sentryEvents,
                openAvmRecordings = openAvm,
            )
        }

        val cache = VehicleUsbMediaCache(volumes = previous.values.sortedBy { it.storageUuid })
        writeCache(appContext, cache)
        snapshot(cache, targets, errors)
    }

    suspend fun loadCached(context: Context): VehicleUsbMediaSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        snapshot(
            cache = readCache(appContext),
            targets = UsbExportVolumeResolver.mountedTargets(appContext),
            errors = emptyList(),
        )
    }

    private fun scanOpenAvm(
        context: Context,
        target: UsbExportTarget,
    ): List<CachedOpenAvmRecording> {
        val backend = UsbMediaStoreBackend(context)
        val legacy = UsbExportCatalog(context, backend).snapshot(target).completeExports.mapNotNull { export ->
            val manifest = runCatching {
                json.decodeFromString<UsbPortableManifest>(
                    backend.readBytes(export.manifest.uri).toString(Charsets.UTF_8),
                )
            }.getOrNull() ?: return@mapNotNull null
            val sidecar = export.assets.firstOrNull {
                it.displayName?.endsWith(".sidecar.json", ignoreCase = true) == true
            }?.let { metadata ->
                runCatching {
                    json.decodeFromString<SegmentSidecar>(
                        backend.readBytes(metadata.uri).toString(Charsets.UTF_8),
                    )
                }.getOrNull()
            }
            val videos = export.assets.filter { it.mimeType == "video/mp4" }
                .sortedBy { it.displayName }
                .mapNotNull { metadata ->
                    metadata.displayName?.let { relativeFile(metadata.relativePath, it) }
                }
            if (videos.isEmpty()) return@mapNotNull null
            CachedOpenAvmRecording(
                exportKey = export.exportKey,
                logicalId = manifest.logicalId,
                recordingMode = manifest.recordingMode,
                startedAtEpochMs = manifest.startedAtEpochMs,
                stoppedAtEpochMs = manifest.stoppedAtEpochMs,
                totalBytes = export.existingBytes,
                videoRelativePaths = videos,
                videoDurationsMs = List(videos.size) { 0L },
                sourceRole = sidecar?.sourceRole?.name ?: RecordingSourceRole.SURROUND.name,
                layoutKind = sidecar?.layoutKind?.name ?: RecordingLayoutKind.FOUR_LANE_V1.name,
                timeLapseMultiplier = sidecar?.timeLapseMultiplier ?: 1,
                containsDirectRecording = false,
                ownedUnits = listOf(OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.LEGACY_EXPORT, export.exportKey)),
            )
        }
        val segmented = UsbSegmentCatalog(context, backend).snapshot(target).segments.mapNotNull { bundle ->
            val manifest = bundle.manifestData
            val sidecar = runCatching {
                json.decodeFromString<SegmentSidecar>(
                    backend.readBytes(bundle.sidecar.uri).toString(Charsets.UTF_8),
                )
            }.getOrNull()
            val videoName = bundle.video.displayName ?: return@mapNotNull null
            CachedOpenAvmRecording(
                exportKey = manifest.bundleId,
                logicalId = manifest.logicalId,
                recordingMode = manifest.recordingMode,
                startedAtEpochMs = manifest.startedAtEpochMs,
                stoppedAtEpochMs = manifest.stoppedAtEpochMs,
                totalBytes = bundle.existingBytes,
                videoRelativePaths = listOf(relativeFile(bundle.video.relativePath, videoName)),
                videoDurationsMs = listOf(
                    playbackDurationMs(
                        sidecar = sidecar,
                        startedAtEpochMs = manifest.startedAtEpochMs,
                        stoppedAtEpochMs = manifest.stoppedAtEpochMs,
                    ),
                ),
                sourceRole = sidecar?.sourceRole?.name ?: RecordingSourceRole.SURROUND.name,
                layoutKind = sidecar?.layoutKind?.name ?: RecordingLayoutKind.FOUR_LANE_V1.name,
                timeLapseMultiplier = sidecar?.timeLapseMultiplier ?: 1,
                containsDirectRecording = manifest.sourceKind == UsbSegmentSourceKind.DIRECT_RECORDING,
                protectedSegments = if (manifest.protected) 1 else 0,
                eventTimes = listOfNotNull(bundle.incident?.requestedAtEpochMs ?: sidecar?.eventRequestedAtEpochMs),
                ownedUnits = listOf(OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.SEGMENT_BUNDLE, manifest.bundleId)),
            )
        }
        return (legacy + segmented)
            .groupBy { "${it.logicalId}|${it.recordingMode}" }
            .values
            .map { units ->
                val ordered = units.sortedBy { it.startedAtEpochMs }
                val videos = ordered.flatMap { unit ->
                    unit.videoRelativePaths.mapIndexed { index, path ->
                        path to unit.videoDurationsMs.getOrElse(index) { 0L }
                    }
                }.distinctBy { it.first }
                ordered.first().copy(
                    exportKey = if (ordered.size == 1) ordered.first().exportKey
                    else "session:${ordered.first().logicalId}:${ordered.first().startedAtEpochMs}",
                    startedAtEpochMs = ordered.minOf { it.startedAtEpochMs },
                    stoppedAtEpochMs = ordered.maxOf { it.stoppedAtEpochMs },
                    totalBytes = ordered.sumOf { it.totalBytes },
                    videoRelativePaths = videos.map { it.first },
                    videoDurationsMs = videos.map { it.second },
                    containsDirectRecording = ordered.any { it.containsDirectRecording },
                    protectedSegments = ordered.sumOf { it.protectedSegments },
                    eventTimes = ordered.flatMap { it.eventTimes }.distinct().sorted(),
                    ownedUnits = ordered.flatMap { it.ownedUnits }.distinct(),
                )
            }
    }

    private fun snapshot(
        cache: VehicleUsbMediaCache,
        targets: List<UsbExportTarget>,
        errors: List<String>,
    ): VehicleUsbMediaSnapshot {
        val mounted = targets.associateBy { it.storageUuid }
        val openAvm = mutableListOf<VehicleUsbRecording>()
        val sentry = mutableListOf<VehicleSentryRecording>()

        cache.volumes.forEach { volume ->
            val target = mounted[volume.storageUuid]
            val root = target?.directoryPath?.let(::File)
            volume.openAvmRecordings.forEach { recording ->
                val resolvedVideos = recording.videoRelativePaths.mapIndexedNotNull { index, path ->
                    root?.let { resolveRelative(it, path) }
                        ?.takeIf(File::isFile)
                        ?.let { it to recording.videoDurationsMs.getOrElse(index) { 0L } }
                }
                val files = resolvedVideos.map { it.first }
                val online = target != null && files.size == recording.videoRelativePaths.size
                openAvm += VehicleUsbRecording(
                    stableKey = "openavm:${volume.storageUuid}:${recording.exportKey}",
                    logicalId = recording.logicalId,
                    storageUuid = volume.storageUuid,
                    storageDescription = volume.description,
                    category = categoryOf(recording.logicalId, recording.recordingMode),
                    availability = if (online) MediaAvailability.ONLINE else MediaAvailability.OFFLINE,
                    startedAtEpochMs = recording.startedAtEpochMs,
                    stoppedAtEpochMs = recording.stoppedAtEpochMs,
                    totalBytes = recording.totalBytes,
                    files = files,
                    segmentDurationsMs = resolvedVideos
                        .filter { it.second > 0L }
                        .associate { (file, durationMs) -> file.absolutePath to durationMs },
                    sourceRole = enumValueOrDefault(recording.sourceRole, RecordingSourceRole.SURROUND),
                    layoutKind = enumValueOrDefault(recording.layoutKind, RecordingLayoutKind.FOUR_LANE_V1),
                    timeLapseMultiplier = recording.timeLapseMultiplier.coerceAtLeast(1),
                    containsDirectRecording = recording.containsDirectRecording,
                    protectedSegments = recording.protectedSegments,
                    eventTimes = recording.eventTimes,
                    ownedUnits = recording.ownedUnits,
                )
            }
            volume.sentryEvents.forEach { event ->
                val eventTime = eventTime(event)
                val video = event.videoRelativePath
                    ?.let { path -> root?.let { resolveRelative(it, path) } }
                    ?.takeIf(File::isFile)
                val thumbnail = event.thumbnailRelativePath
                    ?.let { path -> root?.let { resolveRelative(it, path) } }
                    ?.takeIf(File::isFile)
                sentry += VehicleSentryRecording(
                    stableKey = event.stableKey,
                    storageUuid = volume.storageUuid,
                    storageDescription = volume.description,
                    eventId = event.eventId,
                    availability = if (target != null && video != null) {
                        MediaAvailability.ONLINE
                    } else {
                        MediaAvailability.OFFLINE
                    },
                    startedAtEpochMs = eventTime.epochMs,
                    timestampSource = eventTime.source,
                    timestampMismatch = event.info?.timestampMatchesDirectory == false,
                    totalBytes = event.totalBytes,
                    videoFile = video,
                    thumbnailFile = thumbnail,
                    complete = event.complete,
                )
            }
        }

        return VehicleUsbMediaSnapshot(
            openAvmRecordings = openAvm.sortedByDescending { it.startedAtEpochMs },
            sentryRecordings = sentry.sortedByDescending { it.startedAtEpochMs },
            mountedVolumeCount = targets.size,
            scanErrors = errors,
        )
    }

    internal fun categoryOf(logicalId: String, recordingMode: String): VehicleMediaCategory = when {
        recordingMode == "SENTRY" -> VehicleMediaCategory.OPENAVM_SENTRY
        logicalId.startsWith("event:") -> VehicleMediaCategory.EVENTS
        recordingMode == RecordingMode.TIME_LAPSE.name -> VehicleMediaCategory.TIME_LAPSE
        else -> VehicleMediaCategory.NORMAL
    }

    private fun eventTime(event: UsbSentryEvent): SentryEventTime {
        parseEventEpoch(event.eventId)?.let {
            return SentryEventTime(it, SentryTimestampSource.EVENT_DIRECTORY)
        }
        parseEventEpoch(event.info?.timestamp)?.let {
            return SentryEventTime(it, SentryTimestampSource.INFO_METADATA)
        }
        return SentryEventTime(
            epochMs = event.modifiedEpochMs.takeIf { it > 0L } ?: 0L,
            source = SentryTimestampSource.FILE_MODIFIED,
        )
    }

    private fun playbackDurationMs(
        sidecar: SegmentSidecar?,
        startedAtEpochMs: Long,
        stoppedAtEpochMs: Long,
    ): Long = sidecar?.actualTrack?.durationMs?.takeIf { it > 0L }
        ?: sidecar?.realDurationMs?.takeIf { it > 0L }?.let { realDuration ->
            if (sidecar.timeLapseMultiplier > 1) realDuration / sidecar.timeLapseMultiplier
            else realDuration
        }
        ?: (stoppedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)

    private fun parseEventEpoch(value: String?): Long? {
        if (value == null || !SENTRY_EVENT_TIME.matches(value)) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }.parse(value)?.time
        }.getOrNull()
    }

    private fun relativeFile(relativePath: String?, name: String): String =
        listOfNotNull(relativePath?.trim()?.trim('/', '\\')?.takeIf(String::isNotEmpty), name)
            .joinToString("/")

    private fun resolveRelative(root: File, relativePath: String): File =
        File(root, relativePath.replace('/', File.separatorChar))

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun cacheFile(context: Context): File =
        File(context.filesDir, "vehicle-media/usb-library-cache.json")

    private fun readCache(context: Context): VehicleUsbMediaCache {
        val file = cacheFile(context)
        if (!file.isFile) return VehicleUsbMediaCache()
        return runCatching { json.decodeFromString<VehicleUsbMediaCache>(file.readText()) }
            .getOrDefault(VehicleUsbMediaCache())
    }

    private fun writeCache(context: Context, cache: VehicleUsbMediaCache) {
        val file = cacheFile(context)
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeText(json.encodeToString(cache))
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}:${t.message.orEmpty().take(120)}"

    private data class SentryEventTime(
        val epochMs: Long,
        val source: SentryTimestampSource,
    )

    @Serializable
    private data class VehicleUsbMediaCache(
        val schemaVersion: Int = 2,
        val volumes: List<CachedUsbVolume> = emptyList(),
    )

    @Serializable
    private data class CachedUsbVolume(
        val storageUuid: String,
        val description: String,
        val sentryEvents: List<UsbSentryEvent> = emptyList(),
        val openAvmRecordings: List<CachedOpenAvmRecording> = emptyList(),
    )

    @Serializable
    private data class CachedOpenAvmRecording(
        val exportKey: String,
        val logicalId: String,
        val recordingMode: String,
        val startedAtEpochMs: Long,
        val stoppedAtEpochMs: Long,
        val totalBytes: Long,
        val videoRelativePaths: List<String>,
        val videoDurationsMs: List<Long> = emptyList(),
        val sourceRole: String,
        val layoutKind: String,
        val timeLapseMultiplier: Int,
        val containsDirectRecording: Boolean = false,
        val protectedSegments: Int = 0,
        val eventTimes: List<Long> = emptyList(),
        val ownedUnits: List<OpenAvmOwnedUnitRef> = emptyList(),
    )

    private val SENTRY_EVENT_TIME = Regex("""^\d{4}-\d{2}-\d{2}_\d{2}_\d{2}_\d{2}$""")
}
