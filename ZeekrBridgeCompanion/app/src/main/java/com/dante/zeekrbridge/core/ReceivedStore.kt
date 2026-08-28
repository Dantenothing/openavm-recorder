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
        val movedVideos = TrashStore.moveToTrash(files)
        refresh()
        return movedVideos
    }

    fun sha256(file: File): String = StreamingSha256.hashOrUnavailable(file)
}
