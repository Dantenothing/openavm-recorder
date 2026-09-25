package com.dante.zeekrcapabilitylab.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind

/** Small disk cache; thumbnail extraction is serialized to protect the car decoder. */
class RecordingThumbnailCache(private val root: File) {

    init {
        root.mkdirs()
    }

    /** Cache-only lookup, for callers which cannot schedule extraction. */
    @Synchronized
    fun loadCached(video: File, layoutKind: RecordingLayoutKind): Bitmap? =
        runCatching { fileFor(video, layoutKind)?.let { BitmapFactory.decodeFile(it.absolutePath) } }.getOrNull()

    fun loadOrCreate(
        video: File,
        layoutKind: RecordingLayoutKind = RecordingLayoutKind.FOUR_LANE_V1,
        laneSizePx: Int = 160,
    ): Bitmap? = synchronized(extractionLock) { createLocked(video, layoutKind, laneSizePx) }

    private fun createLocked(video: File, layoutKind: RecordingLayoutKind, laneSizePx: Int): Bitmap? {
        if (!video.isFile) return null
        val target = fileFor(video, layoutKind) ?: return null
        BitmapFactory.decodeFile(target.absolutePath)?.let { return it }
        if (target.exists()) target.delete()

        val cover = when (layoutKind) {
            RecordingLayoutKind.FOUR_LANE_V1 -> FourLaneThumbs.extractCover(video, laneSizePx)
            RecordingLayoutKind.SINGLE_V1 -> SingleFrameThumbs.extractCover(
                video,
                widthPx = laneSizePx * 2,
                heightPx = laneSizePx * 2,
            )
        } ?: return null
        val temp = File(target.absolutePath + ".tmp")
        return try {
            val written = temp.outputStream().buffered().use { output ->
                cover.compress(Bitmap.CompressFormat.JPEG, 84, output)
            }
            if (!written) {
                temp.delete()
                cover.recycle()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            prune()
            cover
        } catch (_: Throwable) {
            temp.delete()
            cover.recycle()
            null
        }
    }

    companion object {
        // List covers and segment rails create different cache instances. An instance
        // monitor did not serialize their native retrievers; this lock does.
        private val extractionLock = Any()
    }

    @Synchronized
    fun remove(video: File) {
        RecordingLayoutKind.entries.forEach { layout ->
            runCatching { fileFor(video, layout)?.delete() }
        }
    }

    private fun fileFor(video: File, layoutKind: RecordingLayoutKind): File? {
        // Keep the established four-lane identity so upgrading does not force
        // every existing cover to be decoded again. Only single-view covers
        // need a distinct key because their composition differs.
        val raster = RecordingRasterReader.read(video)
        if (raster is io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Rejected ||
            layoutKind == RecordingLayoutKind.SINGLE_V1 &&
            raster is io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Repacked) return null
        val identity = "${video.absolutePath}|${video.length()}|${video.lastModified()}" +
            (if (layoutKind == RecordingLayoutKind.SINGLE_V1) "|SINGLE_V1" else "") +
            (if (raster is io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Original) "" else "|RASTER:$raster")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.jpg")
    }

    private fun prune(maxFiles: Int = 300) {
        val files = root.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxFiles)
            .forEach { it.delete() }
    }
}
