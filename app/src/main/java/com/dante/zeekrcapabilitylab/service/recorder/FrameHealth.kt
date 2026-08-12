package com.dante.zeekrcapabilitylab.service.recorder

import kotlinx.serialization.Serializable

/** Small downscaled frame delivered to the analyzer (never the full-res frame list). */
data class PixelFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

@Serializable
data class FrameHealthReport(
    val status: String = STATUS_UNAVAILABLE,
    val sampledFrames: Int = 0,
    val averageBrightness: Double? = null,
    val firstAhash: Long? = null,
    val maxHammingDistance: Int? = null,
    val blackSuspected: Boolean = false,
    val frozenSuspected: Boolean = false,
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    }
}

/**
 * Heuristic frame-health math (pure Kotlin, JVM-tested):
 *  - average luma from ARGB pixels;
 *  - 64-bit aHash over an 8x8 downsampled grid;
 *  - blackSuspected when mean brightness is very low;
 *  - frozenSuspected when consecutive sampled frames are nearly identical.
 */
object FrameHealthAnalyzer {
    const val BLACK_BRIGHTNESS_THRESHOLD = 16.0
    const val FROZEN_HAMMING_THRESHOLD = 2
    const val GRID = 8

    fun brightness(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        var sum = 0L
        for (p in argb) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r + g + b) / 3
        }
        return sum.toDouble() / argb.size
    }

    fun ahash64(argb: IntArray, width: Int, height: Int): Long {
        if (argb.isEmpty() || width <= 0 || height <= 0) return 0L
        val cellW = width.toFloat() / GRID
        val cellH = height.toFloat() / GRID
        val grays = IntArray(GRID * GRID)
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val x = ((c + 0.5f) * cellW).toInt().coerceIn(0, width - 1)
                val y = ((r + 0.5f) * cellH).toInt().coerceIn(0, height - 1)
                val p = argb[y * width + x]
                grays[r * GRID + c] = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            }
        }
        val mean = grays.average()
        var hash = 0L
        for (i in grays.indices) {
            if (grays[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun analyze(frames: List<PixelFrame>): FrameHealthReport {
        if (frames.isEmpty()) {
            return FrameHealthReport(status = FrameHealthReport.STATUS_UNAVAILABLE, sampledFrames = 0)
        }
        val brightnesses = frames.map { brightness(it.argb) }
        val meanBrightness = brightnesses.average()
        val hashes = frames.map { ahash64(it.argb, it.width, it.height) }
        val maxDistance = hashes.zipWithNext()
            .maxOfOrNull { hammingDistance(it.first, it.second) }
            ?: 0
        return FrameHealthReport(
            status = FrameHealthReport.STATUS_OK,
            sampledFrames = frames.size,
            averageBrightness = meanBrightness,
            firstAhash = hashes.firstOrNull(),
            maxHammingDistance = maxDistance,
            blackSuspected = meanBrightness < BLACK_BRIGHTNESS_THRESHOLD,
            frozenSuspected = maxDistance <= FROZEN_HAMMING_THRESHOLD,
        )
    }
}
