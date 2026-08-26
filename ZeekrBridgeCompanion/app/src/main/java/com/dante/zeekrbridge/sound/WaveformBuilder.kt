package com.dante.zeekrbridge.sound

import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Per-bucket positive magnitudes for drawing a symmetric waveform. */
data class WaveformPeaks(
    val min: FloatArray,
    val max: FloatArray,
) {
    val buckets: Int get() = min.size
}

/** One streaming pass over the decoded PCM file, constant memory. */
object WaveformBuilder {
    fun build(file: File, meta: PcmMeta, buckets: Int = 900): WaveformPeaks {
        require(buckets in 1..4096) { "buckets out of range" }
        val min = FloatArray(buckets) { 1f }
        val max = FloatArray(buckets) { -1f }
        if (meta.frameCount == 0L) return WaveformPeaks(min, max)
        FilePcmInput(file, meta.sampleRate, meta.channels, meta.frameCount).use { input ->
            val chunkFrames = 8192
            val buf = FloatArray(chunkFrames * meta.channels)
            var frame = 0L
            while (frame < meta.frameCount) {
                val count = min(chunkFrames.toLong(), meta.frameCount - frame).toInt()
                val read = input.readFrames(frame, count, buf)
                for (f in 0 until read) {
                    var peak = 0f
                    for (c in 0 until meta.channels) {
                        val v = buf[f * meta.channels + c]
                        peak = max(peak, if (v < 0f) -v else v)
                    }
                    val bucket = ((frame + f) * buckets / meta.frameCount).toInt().coerceIn(0, buckets - 1)
                    if (peak < min[bucket]) min[bucket] = peak
                    if (peak > max[bucket]) max[bucket] = peak
                }
                frame += read
            }
        }
        return WaveformPeaks(min, max)
    }
}
