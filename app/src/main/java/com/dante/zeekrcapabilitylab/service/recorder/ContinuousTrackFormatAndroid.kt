package com.dante.zeekrcapabilitylab.service.recorder

import android.media.MediaFormat
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeCodecFormat

/** Both production continuous AVC backends require API 29 before they can be selected. */
@androidx.annotation.RequiresApi(29)
internal object ContinuousTrackFormatAndroid {
    data class Snapshot(val spec: ContinuousTrackFormatSpec, val format: MediaFormat)

    fun copyForMuxer(source: MediaFormat): MediaFormat = ProbeCodecFormat.snapshot(source)

    fun capture(source: MediaFormat): Snapshot = try {
        // Copy while still on the codec worker, before addTrack can consume any buffer cursors.
        val spec = ContinuousTrackFormatSpec.capture(object : ContinuousTrackFormatFields {
            override val keys: Set<String> get() = source.keys
            override fun string(key: String) = source.getString(key)
            override fun integer(key: String) = source.getInteger(key)
            override fun buffer(key: String) = source.getByteBuffer(key)
        })
        Snapshot(spec, copyForMuxer(source))
    } catch (error: Exception) {
        if (error is ContinuousTrackFormatException) throw error
        throw ContinuousTrackFormatSpec.incompleteField("FORMAT_COPY", "COPY_FAILED", null, cause = error)
    }
}
