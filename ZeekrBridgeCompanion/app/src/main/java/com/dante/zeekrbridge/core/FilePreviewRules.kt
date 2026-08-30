package com.dante.zeekrbridge.core

import java.io.File

/**
 * Text preview rules: only small files with previewable extensions, read with a
 * hard byte cap so a multi-hundred-MB file is never loaded into memory.
 */
object FilePreviewRules {
    val PREVIEW_EXTENSIONS = setOf("txt", "json", "csv", "log")
    const val MAX_PREVIEW_BYTES = 256 * 1024

    fun isPreviewable(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in PREVIEW_EXTENSIONS
    }

    fun previewLimitBytes(): Int = MAX_PREVIEW_BYTES

    /** Reads at most [MAX_PREVIEW_BYTES]; never allocates the whole file. */
    fun readPreview(file: File): String {
        val length = minOf(file.length(), MAX_PREVIEW_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)
        var read = 0
        file.inputStream().use { input ->
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n == -1) break
                read += n
            }
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    /** Null when the file is not previewable or exceeds the preview limit. */
    fun readPreviewOrNull(file: File): String? {
        if (!isPreviewable(file.name)) return null
        if (file.length() > MAX_PREVIEW_BYTES) return null
        return readPreview(file)
    }
}
