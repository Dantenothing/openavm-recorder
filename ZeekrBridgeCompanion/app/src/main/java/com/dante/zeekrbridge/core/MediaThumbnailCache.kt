package com.dante.zeekrbridge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.StripBitmapDrawing
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import io.github.dantenothing.avmtransfer.protocol.PixelRectangle
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Small, disposable cover cache. No thumbnail work runs on the UI thread. */
object MediaThumbnailCache {
    private const val COVER_WIDTH = 640
    private const val COVER_HEIGHT = 360
    private const val MAX_DISK_COVERS = 300
    private val generationMutex = Mutex()
    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun loadOrCreate(context: Context, segment: IndexedMediaSegment): Bitmap? = loadOrCreate(
        context = context,
        key = cacheKey(segment),
    ) {
        if (ContinuousRasterSupport.error(segment) != null) return@loadOrCreate null
        createCover(
            context = context,
            uri = Uri.fromFile(segment.file),
            layoutKind = segment.layoutKind,
            lanes = segment.lanes,
            expectedWidth = segment.originalWidth,
            expectedHeight = segment.originalHeight,
            raster = segment.raster,
        )
    }

    suspend fun loadOrCreate(context: Context, record: SavedMediaRecord): Bitmap? {
        val uri = record.uri ?: return null
        val key = hashKey(
            listOf(
                uri.toString(),
                record.sizeBytes.toString(),
                record.layoutKind,
                record.laneLabels.joinToString("|"),
            ).joinToString("|"),
        )
        return loadOrCreate(context, key) {
            createCover(
                context = context,
                uri = uri,
                layoutKind = record.indexedLayoutKind,
                lanes = emptyList(),
                expectedWidth = record.originalWidth,
                expectedHeight = record.originalHeight,
            )
        }
    }

    private suspend fun loadOrCreate(
        context: Context,
        key: String,
        create: () -> Bitmap?,
    ): Bitmap? = withContext(Dispatchers.IO) {
            memory.get(key)?.let { return@withContext it }
            generationMutex.withLock {
                memory.get(key)?.let { return@withLock it }
                val target = File(cacheDir(context), "$key.jpg")
                BitmapFactory.decodeFile(target.absolutePath)?.let {
                    target.setLastModified(System.currentTimeMillis())
                    memory.put(key, it)
                    return@withLock it
                }
                val created = create() ?: return@withLock null
                runCatching {
                    FileOutputStream(target).use { out -> created.compress(Bitmap.CompressFormat.JPEG, 84, out) }
                    target.parentFile?.let(::trimDiskCache)
                }
                memory.put(key, created)
                created
            }
        }

    fun clear(context: Context) {
        memory.evictAll()
        cacheDir(context).listFiles().orEmpty().forEach(File::delete)
    }

    private fun createCover(
        context: Context,
        uri: Uri,
        layoutKind: IndexedLayoutKind,
        lanes: List<IndexedLane>,
        expectedWidth: Int?,
        expectedHeight: Int?,
        raster: RecordingRasterMetadata = RecordingRasterMetadata.Original,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: expectedWidth ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: expectedHeight ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (ContinuousRasterSupport.trackError(raster, layoutKind, width, height, rotation) != null) return null
            val fourLane = layoutKind.isFourLane ||
                (layoutKind == IndexedLayoutKind.UNKNOWN && strongFourLane(width, height))
            val sampleSize = sampleSize(width, height, fourLane)
            val frame = if (Build.VERSION.SDK_INT >= 27 && sampleSize.first > 0 && sampleSize.second > 0) {
                retriever.getScaledFrameAtTime(
                    500_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    sampleSize.first,
                    sampleSize.second,
                )
            } else {
                retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            try {
                val repacked = (raster as? RecordingRasterMetadata.Repacked)?.contract
                if (repacked != null) repackedCover(frame, lanes, repacked)
                else if (fourLane) fourLaneCover(frame, lanes, width, height) else singleCover(frame)
            } finally {
                frame.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fourLaneCover(
        frame: Bitmap,
        lanes: List<IndexedLane>,
        originalWidth: Int,
        originalHeight: Int,
    ): Bitmap {
        val output = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output).apply { drawColor(Color.BLACK) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val sources = sourceRects(frame, lanes, originalWidth, originalHeight)
        sources.take(4).forEachIndexed { index, source ->
            val left = (index % 2) * (COVER_WIDTH / 2)
            val top = (index / 2) * (COVER_HEIGHT / 2)
            canvas.drawBitmap(
                frame,
                centerCrop(source, COVER_WIDTH / 2, COVER_HEIGHT / 2),
                Rect(left, top, left + COVER_WIDTH / 2, top + COVER_HEIGHT / 2),
                paint,
            )
        }
        return output
    }

    private fun repackedCover(frame: Bitmap, lanes: List<IndexedLane>, contract: StripRepackContract): Bitmap {
        val output = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output).apply { drawColor(Color.BLACK) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        lanes.sortedBy { it.displayOrder }.forEachIndexed { index, lane ->
            val source = centerCrop(Rect(lane.x0, lane.y0, lane.x1, lane.y1), COVER_WIDTH / 2, COVER_HEIGHT / 2)
            val left = (index % 2) * COVER_WIDTH / 2f
            val top = (index / 2) * COVER_HEIGHT / 2f
            StripBitmapDrawing.drawCrop(canvas, frame, contract,
                PixelRectangle(source.left, source.top, source.width(), source.height()),
                RectF(left, top, left + COVER_WIDTH / 2f, top + COVER_HEIGHT / 2f), paint)
        }
        return output
    }

    private fun sourceRects(
        frame: Bitmap,
        lanes: List<IndexedLane>,
        originalWidth: Int,
        originalHeight: Int,
    ): List<Rect> {
        val frozenLanes = lanes.takeIf { it.size == 4 }
            ?: FourLaneLayoutClassifier.classify(originalWidth, originalHeight)?.lanes?.takeIf { it.size == 4 }
        if (frozenLanes != null && originalWidth > 0 && originalHeight > 0) {
            val sx = frame.width.toFloat() / originalWidth
            val sy = frame.height.toFloat() / originalHeight
            return frozenLanes.sortedBy { it.displayOrder }.map { lane ->
                Rect(
                    (lane.x0 * sx).roundToInt().coerceIn(0, frame.width - 1),
                    (lane.y0 * sy).roundToInt().coerceIn(0, frame.height - 1),
                    (lane.x1 * sx).roundToInt().coerceIn(1, frame.width),
                    (lane.y1 * sy).roundToInt().coerceIn(1, frame.height),
                )
            }
        }
        return if (frame.height >= frame.width) {
            (0..3).map { i ->
                val top = i * frame.height / 4
                Rect(0, top, frame.width, (i + 1) * frame.height / 4)
            }
        } else {
            (0..3).map { i ->
                val left = i * frame.width / 4
                Rect(left, 0, (i + 1) * frame.width / 4, frame.height)
            }
        }
    }

    private fun singleCover(frame: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            frame,
            centerCrop(Rect(0, 0, frame.width, frame.height), COVER_WIDTH, COVER_HEIGHT),
            Rect(0, 0, COVER_WIDTH, COVER_HEIGHT),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private fun centerCrop(source: Rect, targetWidth: Int, targetHeight: Int): Rect {
        val sourceRatio = source.width().toFloat() / source.height().coerceAtLeast(1)
        val targetRatio = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
        return if (sourceRatio > targetRatio) {
            val width = (source.height() * targetRatio).roundToInt().coerceAtMost(source.width())
            val left = source.left + (source.width() - width) / 2
            Rect(left, source.top, left + width, source.bottom)
        } else {
            val height = (source.width() / targetRatio).roundToInt().coerceAtMost(source.height())
            val top = source.top + (source.height() - height) / 2
            Rect(source.left, top, source.right, top + height)
        }
    }

    private fun sampleSize(width: Int, height: Int, fourLane: Boolean): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longEdge = if (fourLane) 1_280 else 640
        val scale = longEdge.toFloat() / maxOf(width, height)
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun strongFourLane(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return maxOf(width, height).toFloat() / minOf(width, height) >= 3.2f
    }

    private fun cacheKey(segment: IndexedMediaSegment): String {
        val material = buildString {
            append(segment.filePath).append('|')
            append(segment.sizeBytes).append('|')
            append(segment.file.lastModified()).append('|')
            append(segment.layoutKind).append('|')
            append(segment.raster).append('|')
            segment.lanes.forEach { append(it).append('|') }
        }
        return hashKey(material)
    }

    private fun hashKey(material: String): String = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun cacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, "media-covers-v1").apply { mkdirs() }

    private fun trimDiskCache(dir: File) {
        dir.listFiles().orEmpty().filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_DISK_COVERS)
            .forEach(File::delete)
    }
}
