package com.dante.zeekrbridge.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ReceivedStore {
    private lateinit var appContext: Context

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        ReceivedFiles.cleanupStalePartials(receivedDir())
        refresh()
    }

    fun receivedDir(): File =
        File(appContext.filesDir, "received").apply { mkdirs() }

    fun uploadsDir(uploadId: String): File =
        File(appContext.filesDir, "uploads/$uploadId").apply { mkdirs() }

    fun chunkFile(uploadId: String, index: Int): File =
        File(uploadsDir(uploadId), "chunk-$index")

    fun completeUpload(uploadId: String, requestedSha: String?, fileName: String): MergeOutcome {
        val outcome = UploadCompleter.completeForUpload(
            uploadId,
            requestedSha,
            fileName,
            { UploadSessionStore.get(it) },
            UploadSessionStore.uploadsRoot(),
            receivedDir(),
        )
        if (outcome.file != null) refresh()
        return outcome
    }

    fun deleteUpload(uploadId: String) {
        uploadsDir(uploadId).deleteRecursively()
    }

    fun refresh() {
        _files.value = ReceivedFiles.visibleFiles(receivedDir())
    }

    fun moveToTrash(file: File) {
        moveToTrash(listOf(file))
    }

    fun moveToTrash(files: Collection<File>): Int {
        val trash = File(appContext.filesDir, "trash").apply { mkdirs() }
        var movedVideos = 0
        files.distinctBy { it.absoluteFile.normalize().path }.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.renameTo(uniqueTrashTarget(trash, file.name))) {
                movedVideos++
                ReceivedFiles.relatedMetadataFiles(file).forEach { source ->
                    if (source.isFile) source.renameTo(uniqueTrashTarget(trash, source.name))
                }
            }
        }
        refresh()
        return movedVideos
    }

    private fun uniqueTrashTarget(trash: File, name: String): File {
        val direct = File(trash, name)
        if (!direct.exists()) return direct
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf { it != name }
        var suffix = 2
        while (true) {
            val candidateName = if (extension == null) "$stem ($suffix)" else "$stem ($suffix).$extension"
            val candidate = File(trash, candidateName)
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    fun sha256(file: File): String = StreamingSha256.hashOrUnavailable(file)
}
