package com.dante.zeekrbridge.core

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Rebuilds durable Sentry-library entries from committed received MP4/sidecar pairs. */
object ReceivedSentryRegistrar {
    const val MEDIA_ORIGIN = "FACTORY_SENTRY_USB"
    private val json = Json { ignoreUnknownKeys = true }

    fun reconcile(files: Collection<File>): Int {
        var registered = 0
        files.asSequence()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .forEach { video ->
                val sidecar = MediaIndexScanner.sidecarCandidates(video).firstOrNull(File::isFile)
                    ?: return@forEach
                val metadata = runCatching {
                    json.parseToJsonElement(sidecar.readText()).jsonObject
                }.getOrNull() ?: return@forEach
                if (metadata.string("mediaOrigin") != MEDIA_ORIGIN) return@forEach
                val sourceId = metadata.string("sourceId")
                    ?.takeIf { it.startsWith("sentry:") }
                    ?: return@forEach
                val startedAt = metadata.long("startedAtEpochMs")
                    ?.takeIf { it > 0L }
                    ?: video.lastModified().takeIf { it > 0L }
                    ?: 0L
                val existing = SavedMediaStore.records.value.firstOrNull {
                    it.outputPath == video.absolutePath &&
                        it.sourceId == sourceId &&
                        it.sizeBytes == video.length() &&
                        it.createdAtEpochMs == startedAt &&
                        it.layoutKind == IndexedLayoutKind.FOUR_LANE_GRID_2X2.name
                }
                if (existing != null) return@forEach
                val laneLayout = metadata.obj("laneLayout")
                val width = metadata.long("originalWidth")?.toInt()?.takeIf { it > 0 }
                    ?: laneLayout?.long("originalWidth")?.toInt()?.takeIf { it > 0 }
                    ?: 2560
                val height = metadata.long("originalHeight")?.toInt()?.takeIf { it > 0 }
                    ?: laneLayout?.long("originalHeight")?.toInt()?.takeIf { it > 0 }
                    ?: 2560
                val stableId = UUID.nameUUIDFromBytes(
                    "received-sentry:${video.absolutePath}".toByteArray(Charsets.UTF_8),
                ).toString()
                SavedMediaStore.register(
                    SavedMediaRecord(
                        id = stableId,
                        outputPath = video.absolutePath,
                        displayName = video.name,
                        origin = SavedMediaOrigin.SENTRY,
                        sourceId = sourceId,
                        exportTarget = "VEHICLE_TRANSFER",
                        createdAtEpochMs = startedAt,
                        durationMs = readDuration(video),
                        sizeBytes = video.length(),
                        layoutKind = IndexedLayoutKind.FOUR_LANE_GRID_2X2.name,
                        laneLabels = listOf("Top left", "Top right", "Bottom left", "Bottom right"),
                        laneOrder = listOf(1, 2, 3, 4),
                        originalWidth = width,
                        originalHeight = height,
                    ),
                )
                registered++
            }
        return registered
    }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { get(key)?.jsonObject }.getOrNull()
}
