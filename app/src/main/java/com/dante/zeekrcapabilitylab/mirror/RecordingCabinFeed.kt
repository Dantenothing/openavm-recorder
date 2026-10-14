package com.dante.zeekrcapabilitylab.mirror

import android.media.MediaFormat
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/** Local-only compressed video from the already running cabin encoder. No camera or stop authority. */
internal object RecordingCabinFeed {
    private val current = AtomicReference<CabinPreviewBuffer?>()
    fun open(sessionId: String): CabinPreviewBuffer = CabinPreviewBuffer(sessionId).also {
        current.getAndSet(it)?.close()
    }
    fun find(sessionId: String): CabinPreviewBuffer? = current.get()?.takeIf {
        it.sessionId == sessionId && !it.state().closed
    }
    fun close(stream: CabinPreviewBuffer?) {
        if (stream == null) return
        stream.close(); current.compareAndSet(stream, null)
    }
    fun format(stream: CabinPreviewBuffer?, format: MediaFormat) {
        if (stream == null) return
        // Preview extraction is optional. Malformed display metadata cannot fail the recorder.
        runCatching {
            val csd = (0..2).mapNotNull { index -> format.getByteBuffer("csd-$index")?.duplicate()?.let {
                require(it.remaining() <= 128 * 1024)
                ByteArray(it.remaining()).also(it::get)
            } }
            stream.format(CabinPreviewFormat(requireNotNull(format.getString(MediaFormat.KEY_MIME)),
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), csd))
        }.onFailure { close(stream) }
    }
    fun frame(stream: CabinPreviewBuffer?, bytes: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (stream == null) return
        runCatching { stream.frame(bytes, ptsUs, keyframe, SystemClock.elapsedRealtime()) }.onFailure { close(stream) }
    }
}
