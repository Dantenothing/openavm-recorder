package com.dante.zeekrcapabilitylab.service.recorder

import android.media.MediaRecorder
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import java.io.File

/**
 * Recorder output boundary shared by internal files and removable MediaStore descriptors.
 * USB-first sessions may fall back to internal storage once when target admission fails.
 */
interface RecordingOutputSink {
    fun openSegment(
        segmentNumber: Int,
        profile: CameraFormatProfile,
        startedAtEpochMs: Long,
    ): RecordingOutputHandle
}

interface RecordingOutputHandle {
    val storage: RecordingStorageIdentity
    val displayName: String
    val localWorkingFile: File?
    /** Native descriptor acknowledgement, separate from a returned/failed durability operation. */
    val nativeReleaseConfirmed: Boolean get() = true

    /** Called after output format and encoder settings, before MediaRecorder.prepare(). */
    fun bind(recorder: MediaRecorder)

    fun createMuxer(): android.media.MediaMuxer = android.media.MediaMuxer(
        requireNotNull(localWorkingFile).absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    fun close() = Unit

    /** Never called while any muxer owns this file. No video copy or internal staging. */
    fun appendFinalMetadata(document: String) {
        check(nativeReleaseConfirmed)
        java.io.RandomAccessFile(requireNotNull(localWorkingFile), "rw").use {
            ContinuousFileMetadata.append(it.channel, document)
            it.channel.force(true)
        }
    }

    /** Removes an uncommitted destination owned by this exact handle. */
    fun abort() = close()
}

class InternalRecordingOutputSink(private val segmentsDir: File, private val filenamePrefix: () -> String = { "" }) : RecordingOutputSink {
    override fun openSegment(
        segmentNumber: Int,
        profile: CameraFormatProfile,
        startedAtEpochMs: Long,
    ): RecordingOutputHandle {
        val named = SegmentNaming.partialFile(
            segmentsDir,
            segmentNumber,
            profile,
            startedAtEpochMs,
        )
        val prefix = filenamePrefix()
        require(prefix.isEmpty() || prefix.matches(Regex("[a-z_]{1,24}-")))
        val partial = File(named.parentFile, prefix + named.name)
        partial.parentFile?.mkdirs()
        check(partial.createNewFile()) { "RECORDING_NAME_ALREADY_OWNED" }
        InternalRecordingOutputOwners.admit(partial)
        return InternalRecordingOutputHandle(partial)
    }
}

private class InternalRecordingOutputHandle(
    override val localWorkingFile: File,
) : RecordingOutputHandle {
    override val storage = RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
    override val displayName: String get() = localWorkingFile.name

    override fun bind(recorder: MediaRecorder) {
        recorder.setOutputFile(localWorkingFile.absolutePath)
    }
    override fun close() { InternalRecordingOutputOwners.release(localWorkingFile) }
}

/** Startup recovery must never quarantine another live channel's native output. */
internal object InternalRecordingOutputOwners {
    private val paths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun admit(file: File) { check(paths.add(file.absolutePath)) }
    fun release(file: File) { paths.remove(file.absolutePath) }
    fun owns(file: File) = file.absolutePath in paths
}
