package com.dante.zeekrcapabilitylab.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** Small disk cache; thumbnail extraction is serialized to protect the car decoder. */
class RecordingThumbnailCache(private val root: File) {

    init {
        root.mkdirs()
    }

    @Synchronized
    fun loadOrCreate(video: File, laneSizePx: Int = 160): Bitmap? {
        if (!video.isFile) return null
        val target = fileFor(video)
        BitmapFactory.decodeFile(target.absolutePath)?.let { return it }
        if (target.exists()) target.delete()

        val cover = FourLaneThumbs.extractCover(video, laneSizePx) ?: return null
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

    @Synchronized
    fun remove(video: File) {
        runCatching { fileFor(video).delete() }
    }

    private fun fileFor(video: File): File {
        val identity = "${video.absolutePath}|${video.length()}|${video.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.jpg")
    }

    private fun prune(maxFiles: Int = 300, maxBytes: Long = 25L * 1024L * 1024L) {
        val files = root.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
        var retainedBytes = 0L
        files.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            retainedBytes += file.length()
            if (index >= maxFiles || retainedBytes > maxBytes) file.delete()
        }
    }
}
