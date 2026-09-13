package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class FactorySentrySourceSnapshot(
    val file: File,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val sidecarJson: String,
    val sourceId: String,
)

internal sealed interface FactorySentrySourceResolution {
    data class Resolved(val file: File) : FactorySentrySourceResolution
    data class WaitingForUsb(val reason: String) : FactorySentrySourceResolution
    data class Invalid(val reason: String) : FactorySentrySourceResolution
}

/** Resolves a factory SentryMode file by removable-volume identity, never by a stale raw path alone. */
internal object FactorySentryTransferSource {
    const val MEDIA_ORIGIN = "FACTORY_SENTRY_USB"
    private val eventIdPattern = Regex("""^\d{4}-\d{2}-\d{2}_\d{2}_\d{2}_\d{2}$""")
    private val json = Json { encodeDefaults = true }

    fun snapshotForQueue(
        context: Context,
        storageUuid: String,
        eventId: String,
        startedAtEpochMs: Long,
        selectedFile: File,
    ): Result<FactorySentrySourceSnapshot> = runCatching {
        require(eventIdPattern.matches(eventId)) { "Invalid Sentry event directory" }
        val relativePath = expectedRelativePath(eventId)
        val resolved = resolveExpected(context, storageUuid, eventId)
        val file = when (resolved) {
            is FactorySentrySourceResolution.Resolved -> resolved.file
            is FactorySentrySourceResolution.WaitingForUsb -> error(resolved.reason)
            is FactorySentrySourceResolution.Invalid -> error(resolved.reason)
        }
        require(file.canonicalFile == selectedFile.canonicalFile) {
            "Selected video is outside the expected Sentry event"
        }
        val size = file.length()
        require(size > 0L) { "Sentry video is empty" }
        val sourceId = "sentry:$storageUuid:$eventId"
        FactorySentrySourceSnapshot(
            file = file,
            relativePath = relativePath,
            sizeBytes = size,
            lastModifiedEpochMs = file.lastModified(),
            sidecarJson = json.encodeToString(
                FactorySentryTransferSidecar(
                    file = file.name,
                    sourceId = sourceId,
                    eventId = eventId,
                    recordingSessionId = sourceId,
                    startedAtEpochMs = startedAtEpochMs,
                ),
            ),
            sourceId = sourceId,
        )
    }

    fun resolve(
        context: Context,
        storageUuid: String?,
        eventId: String?,
        relativePath: String?,
        expectedBytes: Long,
        expectedLastModifiedEpochMs: Long?,
    ): FactorySentrySourceResolution {
        if (storageUuid.isNullOrBlank() || eventId.isNullOrBlank() || relativePath.isNullOrBlank()) {
            return FactorySentrySourceResolution.Invalid("Sentry transfer identity is incomplete")
        }
        val expectedRelative = expectedRelativePath(eventId)
        if (relativePath != expectedRelative) {
            return FactorySentrySourceResolution.Invalid("Sentry transfer path is invalid")
        }
        val resolved = resolveExpected(context, storageUuid, eventId)
        if (resolved !is FactorySentrySourceResolution.Resolved) return resolved
        val file = resolved.file
        if (file.length() != expectedBytes) {
            return FactorySentrySourceResolution.Invalid("Sentry source changed since it was queued")
        }
        val observedModified = file.lastModified()
        if (
            expectedLastModifiedEpochMs != null && expectedLastModifiedEpochMs > 0L &&
            observedModified > 0L && observedModified != expectedLastModifiedEpochMs
        ) {
            return FactorySentrySourceResolution.Invalid("Sentry source changed since it was queued")
        }
        return resolved
    }

    private fun resolveExpected(
        context: Context,
        storageUuid: String,
        eventId: String,
    ): FactorySentrySourceResolution {
        if (!eventIdPattern.matches(eventId)) {
            return FactorySentrySourceResolution.Invalid("Invalid Sentry event directory")
        }
        val target = UsbExportVolumeResolver.mountedTargets(context.applicationContext)
            .firstOrNull { it.storageUuid.equals(storageUuid, ignoreCase = true) }
            ?: return FactorySentrySourceResolution.WaitingForUsb("Reconnect the same USB to send this Sentry event")
        val rootPath = target.directoryPath
            ?: return FactorySentrySourceResolution.WaitingForUsb("The mounted USB path is unavailable")
        val root = runCatching { File(rootPath).canonicalFile }.getOrElse {
            return FactorySentrySourceResolution.Invalid("Unable to resolve the mounted USB root")
        }
        if (!root.isDirectory || !root.canRead()) {
            return FactorySentrySourceResolution.WaitingForUsb("Reconnect the same USB to send this Sentry event")
        }
        val expected = runCatching {
            File(root, expectedRelativePath(eventId).replace('/', File.separatorChar)).canonicalFile
        }.getOrElse {
            return FactorySentrySourceResolution.Invalid("Unable to resolve the Sentry video path")
        }
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!expected.path.startsWith(rootPrefix, ignoreCase = true)) {
            return FactorySentrySourceResolution.Invalid("Sentry video escaped the mounted USB root")
        }
        if (!expected.isFile || !expected.canRead()) {
            return FactorySentrySourceResolution.Invalid("Sentry event is no longer present on this USB")
        }
        return FactorySentrySourceResolution.Resolved(expected)
    }

    private fun expectedRelativePath(eventId: String): String =
        "SentryMode/$eventId/${eventId}_alert_360.mp4"
}

@Serializable
private data class FactorySentryTransferSidecar(
    val schemaVersion: Int = 1,
    val mediaOrigin: String = FactorySentryTransferSource.MEDIA_ORIGIN,
    val file: String,
    val sourceId: String,
    val eventId: String,
    val sourceRole: String = "SURROUND",
    val layoutKind: String = "FOUR_LANE_GRID_2X2",
    val recordingMode: String = "NORMAL",
    val recordingSessionId: String,
    val segmentNumber: Int = 1,
    val startedAtEpochMs: Long,
    val protected: Boolean = false,
    val result: String = "SUCCESS",
    val originalWidth: Int = 2560,
    val originalHeight: Int = 2560,
    val laneLayout: FactorySentryLaneLayout = FactorySentryLaneLayout(),
)

@Serializable
private data class FactorySentryLaneLayout(
    val originalWidth: Int = 2560,
    val originalHeight: Int = 2560,
    val layoutType: String = "GRID_2X2",
    val lanes: List<FactorySentryLane> = listOf(
        FactorySentryLane(1, 0, 1280, 0, 1280, "Top left", 1),
        FactorySentryLane(2, 1280, 2560, 0, 1280, "Top right", 2),
        FactorySentryLane(3, 0, 1280, 1280, 2560, "Bottom left", 3),
        FactorySentryLane(4, 1280, 2560, 1280, 2560, "Bottom right", 4),
    ),
)

@Serializable
private data class FactorySentryLane(
    val lane: Int,
    val x0: Int,
    val x1: Int,
    val y0: Int,
    val y1: Int,
    val label: String,
    val displayOrder: Int,
    val rotationDegrees: Int = 0,
)
