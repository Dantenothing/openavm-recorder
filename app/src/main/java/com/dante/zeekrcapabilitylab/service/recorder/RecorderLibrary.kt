package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.usbexport.UsbExportPinRegistry
import java.io.File

/**
 * Pure selection of app-owned finalized segments for the UI library.
 *
 * Only finalized mp4 files whose sidecar exists, parses, matches the file path,
 * reports RESULT_SUCCESS, and is not provisional are managed. Partials,
 * quarantined files, failed segments, and unknown files are never listed/queued.
 */
object RecorderLibrary {

    /** Product-facing recording: one manual Session, or one legacy standalone file. */
    data class Recording(
        val id: String,
        val segments: List<Pair<File, SegmentSidecar>>,
    ) {
        val firstFile: File get() = segments.first().first
        val files: List<File> get() = segments.map { it.first }
        val sidecar: SegmentSidecar get() = segments.first().second
        val isTimeLapse: Boolean get() = sidecar.recordingMode == RecordingMode.TIME_LAPSE
        val speedMultiplier: Int get() = if (isTimeLapse) sidecar.timeLapseMultiplier else 1
        val protected: Boolean get() = segments.all { it.second.protected }
        val protectedCount: Int get() = segments.count { it.second.protected }
        val realDurationMs: Long get() = segments.sumOf { (_, metadata) ->
            metadata.realDurationMs ?: if (
                metadata.startedAtElapsedRealtimeMs != null &&
                metadata.stoppedAtElapsedRealtimeMs != null
            ) {
                (metadata.stoppedAtElapsedRealtimeMs - metadata.startedAtElapsedRealtimeMs)
                    .coerceAtLeast(0L)
            } else {
                0L
            }
        }
        val encodedDurationMs: Long get() = segments.sumOf { it.second.actualTrack?.durationMs ?: 0L }
        val totalBytes: Long get() = files.sumOf(File::length)
        val startedAtEpochMs: Long get() = segments.minOf { (file, metadata) ->
            metadata.startedAtEpochMs ?: metadata.requestedAtEpochMs ?: file.lastModified()
        }
        val stoppedAtEpochMs: Long get() = segments.maxOf { (file, metadata) ->
            metadata.stoppedAtEpochMs ?: file.lastModified()
        }
    }

    const val DELETE_NOT_MANAGED = "NOT_MANAGED"
    const val DELETE_BOOKMARKED = "BOOKMARKED"
    const val DELETE_UPLOAD_PINNED = "UPLOAD_PINNED"
    const val DELETE_USB_EXPORT_PINNED = "USB_EXPORT_PINNED"
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

    /** Every modern manual Session collapses by ID; legacy files without an ID stay separate. */
    fun listRecordings(segmentsDir: File): List<Recording> {
        val entries = listFinalized(segmentsDir).mapNotNull { file ->
            SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.let { file to it }
        }
        val groupedSessions = entries
            .filter { (_, sidecar) -> !sidecar.recordingSessionId.isNullOrBlank() }
            .groupBy { it.second.recordingSessionId!! }
            .map { (sessionId, chunks) ->
                Recording(
                    id = "session:$sessionId",
                    segments = chunks.sortedWith(
                        compareBy<Pair<File, SegmentSidecar>> {
                            it.second.startedAtElapsedRealtimeMs ?: Long.MAX_VALUE
                        }.thenBy { it.second.segmentNumber },
                    ),
                )
            }
        val legacy = entries
            .filter { (_, sidecar) -> sidecar.recordingSessionId.isNullOrBlank() }
            .map { (file, sidecar) -> Recording("file:${file.name}", listOf(file to sidecar)) }
        return (legacy + groupedSessions).sortedBy(Recording::startedAtEpochMs)
    }

    fun setRecordingProtected(recording: Recording, protected: Boolean): Int =
        recording.files.count { file -> if (protected) bookmark(file) else unbookmark(file) }

    fun deleteRecordingByUser(segmentsDir: File, recording: Recording): BulkDeleteResult {
        val results = recording.files.map { deleteManagedByUser(segmentsDir, it) }
        return BulkDeleteResult(
            deleted = results.count { it.deleted },
            blocked = results.count { !it.deleted },
            sidecarCleanupWarnings = results.count { it.reason == DELETE_SIDECAR_FAILED },
        )
    }

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
        if (UsbExportPinRegistry.isPinned(file)) {
            return@synchronized DeleteResult(false, DELETE_USB_EXPORT_PINNED)
        }
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
    // Playback and browser downloads may hold independent leases on the same file.
    private val paths = mutableMapOf<String, Int>()

    fun acquire(file: File) = synchronized(RecorderStorageLock.lock) {
        val path = stablePath(file)
        paths[path] = (paths[path] ?: 0) + 1
    }

    fun release(file: File) = synchronized(RecorderStorageLock.lock) {
        val path = stablePath(file)
        val remaining = (paths[path] ?: 0) - 1
        if (remaining > 0) paths[path] = remaining else paths.remove(path)
    }

    fun isPinned(file: File): Boolean = isPinned(file.absolutePath)

    fun isPinned(path: String): Boolean = synchronized(RecorderStorageLock.lock) {
        stablePath(File(path)) in paths
    }

    private fun stablePath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
