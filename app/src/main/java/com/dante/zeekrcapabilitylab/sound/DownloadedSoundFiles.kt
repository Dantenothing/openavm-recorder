package com.dante.zeekrcapabilitylab.sound

import android.os.Environment
import java.io.File

internal data class DownloadedSoundEntry(val file: File, val folder: Boolean, val bytes: Long)
internal data class DownloadedSoundListing(val root: File, val directory: File, val entries: List<DownloadedSoundEntry>, val readable: Boolean)

internal object DownloadedSoundFiles {
    private val extensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts")
    fun list(requested: File? = null): DownloadedSoundListing {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val directory = (requested ?: root).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "Outside Downloads" }
        val files = directory.listFiles()
        val entries = files.orEmpty().asSequence().filter { !it.name.startsWith('.') }
            .mapNotNull { file -> runCatching { file.canonicalFile }.getOrNull() }
            .filter { it.parentFile == directory && (it.isDirectory || it.extension.lowercase() in extensions || it.extension.isBlank()) }
            .map { DownloadedSoundEntry(it, it.isDirectory, if (it.isFile) it.length() else 0) }
            .sortedWith(compareByDescending<DownloadedSoundEntry> { it.folder }.thenBy { it.file.name.lowercase() })
            .take(500).toList()
        return DownloadedSoundListing(root, directory, entries, files != null)
    }
}
