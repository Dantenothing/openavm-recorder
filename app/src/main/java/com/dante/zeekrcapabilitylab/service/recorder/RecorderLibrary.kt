package com.dante.zeekrcapabilitylab.service.recorder

import java.io.File

/**
 * Pure selection of app-owned finalized segments for the UI library.
 *
 * Only finalized mp4 files whose sidecar exists, parses, matches the file path,
 * reports RESULT_SUCCESS, and is not provisional are managed. Partials,
 * quarantined files, failed segments, and unknown files are never listed/queued.
 */
object RecorderLibrary {

    const val DELETE_NOT_MANAGED = "NOT_MANAGED"
    const val DELETE_BOOKMARKED = "BOOKMARKED"
    const val DELETE_UPLOAD_PINNED = "UPLOAD_PINNED"
    const val DELETE_PLAYING = "PLAYING"
    const val DELETE_MEDIA_FAILED = "MEDIA_DELETE_FAILED"
    const val DELETE_SIDECAR_FAILED = "SIDECAR_DELETE_FAILED"

    data class DeleteResult(
        val deleted: Boolean,
        val reason: String? = null,
    )

    data class BulkDeleteResult(
        val deleted: Int,
        val blocked: Int,
        val sidecarCleanupWarnings: Int,
    )

    fun isManaged(file: File): Boolean {
        if (!file.isFile || !SegmentNaming.isFinalMp4(file.name)) return false
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        if (!sidecarFile.isFile) return false
        val sidecar = SegmentSidecarIO.read(sidecarFile) ?: return false
        return sidecar.result == SegmentSidecar.RESULT_SUCCESS &&
            !sidecar.provisional &&
            sidecar.file == file.absolutePath
    }

    fun listFinalized(segmentsDir: File): List<File> =
        segmentsDir.listFiles()
            ?.filter { isManaged(it) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    /** Returns only selected files that are finalized mp4s with a parseable sidecar. */
    fun selectManaged(files: List<File>, selectedNames: Set<String>): List<File> =
        files.filter { it.name in selectedNames && isManaged(it) }

    /**
     * Temporary upload pin (uploadPinned=true), NOT a bookmark (protected stays
     * untouched). Runs under [RecorderStorageLock] so eviction read/select/delete
     * and this write are serialized within the process.
     */
    fun pinForUpload(file: File): Boolean = synchronized(RecorderStorageLock.lock) {
        if (!isManaged(file)) return@synchronized false
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        val sidecar = SegmentSidecarIO.read(sidecarFile) ?: return@synchronized false
        if (sidecar.uploadPinned) return@synchronized true
        try {
            SegmentSidecarIO.writeAtomic(file, sidecar.copy(uploadPinned = true))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Manual release of a temporary upload pin. NEVER clears [protected]
     * (explicit Bookmark). Returns true when the pin was actually removed.
     */
    fun releaseUploadPin(file: File): Boolean = synchronized(RecorderStorageLock.lock) {
        if (!isManaged(file)) return@synchronized false
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        val sidecar = SegmentSidecarIO.read(sidecarFile) ?: return@synchronized false
        if (!sidecar.uploadPinned) return@synchronized false
        try {
            SegmentSidecarIO.writeAtomic(file, sidecar.copy(uploadPinned = false))
            true
        } catch (t: Throwable) {
            false
        }
    }

    data class Protection(val bookmarked: Boolean, val uploadPinned: Boolean)

    fun protectionOf(file: File): Protection? {
        if (!isManaged(file)) return null
        val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file)) ?: return null
        return Protection(bookmarked = sidecar.protected, uploadPinned = sidecar.uploadPinned)
    }

    /** Explicit user bookmark: sets protected=true on a managed segment. */
    fun bookmark(file: File): Boolean {
        if (!isManaged(file)) return false
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        val sidecar = SegmentSidecarIO.read(sidecarFile) ?: return false
        if (sidecar.protected) return true
        synchronized(RecorderStorageLock.lock) {
            return try {
                SegmentSidecarIO.writeAtomic(file, sidecar.copy(protected = true))
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    /** Explicit user un-bookmark: clears protected on a managed segment. */
    fun unbookmark(file: File): Boolean {
        if (!isManaged(file)) return false
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        val sidecar = SegmentSidecarIO.read(sidecarFile) ?: return false
        if (!sidecar.protected) return true
        synchronized(RecorderStorageLock.lock) {
            return try {
                SegmentSidecarIO.writeAtomic(
                    file,
                    sidecar.copy(
                        protected = false,
                        eventId = null,
                        eventRequestedAtEpochMs = null,
                        eventRole = null,
                    ),
                )
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    /**
     * Deletes only an app-owned, finalized, unbookmarked and unpinned segment.
     * This remains the safe entry point for automatic cleanup and bulk cleanup.
     */
    fun deleteManaged(segmentsDir: File, file: File): DeleteResult =
        deleteManagedInternal(segmentsDir, file, allowProtected = false)

    /**
     * Explicit user deletion may remove a protected/bookmarked recording after
     * UI confirmation. Upload and playback pins are still respected.
     */
    fun deleteManagedByUser(segmentsDir: File, file: File): DeleteResult =
        deleteManagedInternal(segmentsDir, file, allowProtected = true)

    /**
     * Explicit destructive action from the gallery. It includes protected
     * recordings after confirmation, but still preserves playback/upload pins
     * and every file not proven to be owned by this app.
     */
    fun deleteAllManagedByUser(segmentsDir: File): BulkDeleteResult {
        val results = listFinalized(segmentsDir).map { file ->
            deleteManagedByUser(segmentsDir, file)
        }
        return BulkDeleteResult(
            deleted = results.count { it.deleted },
            blocked = results.count { !it.deleted },
            sidecarCleanupWarnings = results.count {
                it.reason == DELETE_SIDECAR_FAILED
            },
        )
    }

    /**
     * Safe gallery cleanup: removes only finalized, unbookmarked recordings.
     * Protected recordings, playback/upload pins and unknown files are kept.
     */
    fun deleteAllUnprotected(segmentsDir: File): BulkDeleteResult {
        val results = listFinalized(segmentsDir).map { file ->
            deleteManaged(segmentsDir, file)
        }
        return BulkDeleteResult(
            deleted = results.count { it.deleted },
            blocked = results.count { !it.deleted },
            sidecarCleanupWarnings = results.count {
                it.reason == DELETE_SIDECAR_FAILED
            },
        )
    }

    private fun deleteManagedInternal(
        segmentsDir: File,
        file: File,
        allowProtected: Boolean,
    ): DeleteResult = synchronized(RecorderStorageLock.lock) {
        val insideRecorderDirectory = try {
            file.canonicalFile.parentFile == segmentsDir.canonicalFile
        } catch (t: Throwable) {
            false
        }
        if (!insideRecorderDirectory) {
            return@synchronized DeleteResult(false, DELETE_NOT_MANAGED)
        }
        if (!isManaged(file)) return@synchronized DeleteResult(false, DELETE_NOT_MANAGED)
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
        val sidecar = SegmentSidecarIO.read(sidecarFile)
            ?: return@synchronized DeleteResult(false, DELETE_NOT_MANAGED)
        if (sidecar.protected && !allowProtected) {
            return@synchronized DeleteResult(false, DELETE_BOOKMARKED)
        }
        if (sidecar.uploadPinned) return@synchronized DeleteResult(false, DELETE_UPLOAD_PINNED)
        if (PlaybackPinRegistry.isPinned(file)) return@synchronized DeleteResult(false, DELETE_PLAYING)

        val mediaDeleted = try {
            file.delete()
        } catch (t: Throwable) {
            false
        }
        if (!mediaDeleted) return@synchronized DeleteResult(false, DELETE_MEDIA_FAILED)

        val sidecarDeleted = try {
            !sidecarFile.exists() || sidecarFile.delete()
        } catch (t: Throwable) {
            false
        }
        DeleteResult(
            deleted = true,
            reason = if (sidecarDeleted) null else DELETE_SIDECAR_FAILED,
        )
    }
}

/** In-process playback pin, serialized with storage selection and deletion. */
object PlaybackPinRegistry {
    private val paths = mutableSetOf<String>()

    fun acquire(file: File) = synchronized(RecorderStorageLock.lock) {
        paths += stablePath(file)
    }

    fun release(file: File) = synchronized(RecorderStorageLock.lock) {
        paths -= stablePath(file)
    }

    fun isPinned(file: File): Boolean = isPinned(file.absolutePath)

    fun isPinned(path: String): Boolean = synchronized(RecorderStorageLock.lock) {
        stablePath(File(path)) in paths
    }

    private fun stablePath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
