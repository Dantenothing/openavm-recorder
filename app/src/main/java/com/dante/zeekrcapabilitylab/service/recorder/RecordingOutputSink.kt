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

    /** Called after output format and encoder settings, before MediaRecorder.prepare(). */
    fun bind(recorder: MediaRecorder)

    fun close() = Unit

    /** Removes an uncommitted destination owned by this exact handle. */
    fun abort() = close()
}

class InternalRecordingOutputSink(private val segmentsDir: File) : RecordingOutputSink {
    override fun openSegment(
        segmentNumber: Int,
        profile: CameraFormatProfile,
        startedAtEpochMs: Long,
    ): RecordingOutputHandle {
        val partial = SegmentNaming.partialFile(
            segmentsDir,
            segmentNumber,
            profile,
            startedAtEpochMs,
        )
        partial.parentFile?.mkdirs()
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
}
