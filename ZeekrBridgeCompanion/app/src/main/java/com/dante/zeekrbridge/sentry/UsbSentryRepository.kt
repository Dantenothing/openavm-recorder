package com.dante.zeekrbridge.sentry

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import com.dante.zeekrbridge.core.IndexedLayoutKind
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedRecordingMode
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.sound.UsbSaf
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class UsbSentryVideo(
    val documentId: String,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val durationMs: Long = 0L,
    val width: Int? = null,
    val height: Int? = null,
) {
    val isConfirmedFourLane: Boolean
        get() = UsbSentryPolicy.isFourLaneComposite(width, height)
}

data class UsbSentryScanResult(
    val videos: List<UsbSentryVideo>,
    val scannedDocuments: Int,
    val truncated: Boolean,
)

data class MaterializedUsbSentry(
    val file: File,
    val segment: IndexedMediaSegment,
)

object UsbSentryPolicy {
    fun isVideo(name: String, mimeType: String?): Boolean =
        mimeType?.startsWith("video/", ignoreCase = true) == true ||
            name.endsWith(".mp4", ignoreCase = true) ||
            name.endsWith(".mov", ignoreCase = true) ||
            name.endsWith(".m4v", ignoreCase = true)

    fun isFourLaneComposite(width: Int?, height: Int?): Boolean {
        if (width == null || height == null || width <= 0 || height <= 0) return false
        val longSide = maxOf(width, height).toDouble()
        val shortSide = minOf(width, height).toDouble()
        return longSide / shortSide in 3.8..4.2
    }

    fun safeFileName(name: String): String {
        val base = name.substringBeforeLast('.', name).replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', ' ')
            .take(96)
            .ifBlank { "sentry" }
        return "$base.mp4"
    }
}

object UsbSentryRepository {
    suspend fun scan(context: Context, treeUri: Uri): UsbSentryScanResult = withContext(Dispatchers.IO) {
        val root = UsbSaf.rootDocumentUri(treeUri)
            ?: error("USB access is no longer available")
        val rootId = UsbSaf.documentId(root)
            ?: error("Cannot identify the selected USB folder")
        val videos = mutableListOf<UsbSentryVideo>()
        var scanned = 0
        var truncated = false

        suspend fun walk(parentId: String, relativePath: String, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > MAX_DEPTH || scanned >= MAX_DOCUMENTS) {
                truncated = true
                return
            }
            val children = UsbSaf.listChildren(context, treeUri, parentId)
                ?: error("USB was removed or the selected folder is no longer readable")
            for (entry in children) {
                coroutineContext.ensureActive()
                if (scanned++ >= MAX_DOCUMENTS) {
                    truncated = true
                    return
                }
                val path = if (relativePath.isBlank()) entry.name else "$relativePath/${entry.name}"
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(entry.documentId, path, depth + 1)
                } else if (UsbSentryPolicy.isVideo(entry.name, entry.mimeType)) {
                    videos += UsbSentryVideo(
                        documentId = entry.documentId,
                        uri = UsbSaf.documentUri(treeUri, entry.documentId),
                        displayName = entry.name,
                        relativePath = path,
                        sizeBytes = entry.sizeBytes ?: 0L,
                        lastModifiedMs = entry.lastModifiedMs ?: 0L,
                    )
                }
            }
        }

        walk(rootId, "", 0)
        UsbSentryScanResult(
            videos = videos.sortedWith(
                compareByDescending<UsbSentryVideo> { it.lastModifiedMs }.thenBy { it.relativePath.lowercase() },
            ),
            scannedDocuments = scanned,
            truncated = truncated,
        )
    }

    suspend fun probe(context: Context, video: UsbSentryVideo): UsbSentryVideo = withContext(Dispatchers.IO) {
        readMetadata(context, video.uri)?.let { metadata ->
            video.copy(
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
            )
        } ?: video
    }

    suspend fun materialize(
        context: Context,
        video: UsbSentryVideo,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): MaterializedUsbSentry = withContext(Dispatchers.IO) {
        val resolved = if (video.durationMs > 0L && video.width != null && video.height != null) {
            video
        } else {
            probe(context, video)
        }
        val cacheRoot = File(context.filesDir, CACHE_DIRECTORY).apply { mkdirs() }
        val key = shortHash("${video.uri}|${video.sizeBytes}|${video.lastModifiedMs}")
        val destination = File(cacheRoot, "$key-${UsbSentryPolicy.safeFileName(video.displayName)}")
        val expected = video.sizeBytes
        if (!(destination.isFile && expected > 0L && destination.length() == expected)) {
            val available = StatFs(cacheRoot.absolutePath).availableBytes
            if (expected > 0L && available < expected + STORAGE_MARGIN_BYTES) {
                error("Not enough phone storage to prepare this USB video")
            }
            val temporary = File(cacheRoot, destination.name + ".copying")
            runCatching { temporary.delete() }
            try {
                val input = context.contentResolver.openInputStream(video.uri)
                    ?: error("Cannot read the selected USB video")
                var copied = 0L
                temporary.outputStream().use { output ->
                    input.use { source ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                copied += read
                                onProgress(copied, expected)
                            }
                        }
                    }
                }
                if (expected > 0L && temporary.length() != expected) {
                    error("USB video copy was incomplete")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            } catch (t: Throwable) {
                runCatching { temporary.delete() }
                throw t
            }
        }

        val localMetadata = readMetadata(context, Uri.fromFile(destination))
        val duration = localMetadata?.durationMs?.takeIf { it > 0L } ?: resolved.durationMs
        require(duration > 0L) { "Cannot read the duration of this USB video" }
        val width = localMetadata?.width ?: resolved.width ?: 1280
        val height = localMetadata?.height ?: resolved.height ?: 5140
        require(UsbSentryPolicy.isFourLaneComposite(width, height)) {
            "This video is not a supported four-lane 360° composite"
        }
        val startedAt = resolved.lastModifiedMs.takeIf { it > 0L }
            ?.minus(duration)
            ?: destination.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val segment = IndexedMediaSegment(
            id = "usb:$key",
            filePath = destination.absolutePath,
            fileName = resolved.displayName,
            sidecarPath = null,
            sizeBytes = destination.length(),
            startedAtEpochMs = startedAt,
            stoppedAtEpochMs = startedAt + duration,
            durationMs = duration,
            segmentNumber = 1,
            recordingSessionId = "usb:$key",
            eventId = null,
            eventRole = null,
            protected = true,
            sourceRole = IndexedSourceRole.SURROUND,
            layoutKind = IndexedLayoutKind.FOUR_LANE_V1,
            cameraId = null,
            lanes = emptyList(),
            originalWidth = width,
            originalHeight = height,
            recordingMode = IndexedRecordingMode.NORMAL,
        )
        MaterializedUsbSentry(destination, segment)
    }

    suspend fun preparedCopyBytes(context: Context): Long = withContext(Dispatchers.IO) {
        File(context.filesDir, CACHE_DIRECTORY).listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    }

    suspend fun clearPreparedCopies(context: Context): Long = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, CACHE_DIRECTORY)
        var removed = 0L
        root.listFiles()?.filter(File::isFile)?.forEach { file ->
            val length = file.length()
            if (file.delete()) removed += length
        }
        removed
    }

    private data class VideoMetadata(val durationMs: Long, val width: Int, val height: Int)

    private fun readMetadata(context: Context, uri: Uri): VideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) retriever.setDataSource(uri.path)
            else retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            VideoMetadata(duration, width, height).takeIf { duration > 0L && width > 0 && height > 0 }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_DEPTH = 12
    private const val MAX_DOCUMENTS = 20_000
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val STORAGE_MARGIN_BYTES = 32L * 1024L * 1024L
    private const val CACHE_DIRECTORY = "usb-sentry-imports"
}
