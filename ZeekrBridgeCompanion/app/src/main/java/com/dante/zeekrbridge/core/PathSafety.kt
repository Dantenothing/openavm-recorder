package com.dante.zeekrbridge.core

import java.io.File

/**
 * Sanitization applied before any remote-controlled value becomes a local path
 * component so fileName/carId/uploadId can never escape their target directory.
 */
object PathSafety {
    private const val MAX_NAME_LENGTH = 200

    fun cleanFileName(raw: String?): String? {
        if (raw == null) return null
        var name = raw.trim()
        name = name.replace('\\', '/').substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.length > MAX_NAME_LENGTH) return null
        if (name.any { it == '\u0000' || it.code < 0x20 }) return null
        return name
    }

    fun cleanCarId(raw: String?): String {
        val cleaned = raw?.trim()
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            ?.take(128)
            ?: return "unknown"
        return cleaned.ifEmpty { "unknown" }
    }

    /** Strict single-component upload-id validation (alphanumerics, `-`, `_` only). */
    fun cleanUploadId(raw: String?): String? {
        val id = raw?.trim() ?: return null
        if (id.isEmpty() || id.length > 128) return null
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return id
    }

    /** Picks a non-colliding target name in [dir], also avoiding stale `.partial` files. */
    fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 1
        while (File(dir, candidate).exists() || File(dir, "$candidate.partial").exists()) {
            candidate = "$base-$n$ext"
            n++
        }
        return File(dir, candidate)
    }
}
