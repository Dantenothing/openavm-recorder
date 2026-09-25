package com.dante.zeekrcheck.core

import kotlin.math.roundToInt

/** The source is deliberately chroma-separated: magenta paint; neutral glass, lights and trim.
 * Chroma defines the paint mask, its spread carries diffuse shading, green carries neutral reflections.
 * All non-paint pixels pass through unchanged. This is not a whole-image hue/tint filter.
 */
object PaintMaster {
    fun isPaint(argb: Int): Boolean {
        val r = argb ushr 16 and 255; val g = argb ushr 8 and 255; val b = argb and 255
        return argb ushr 24 > 0 && r - g > 5 && b - g > 4 && b > r * .35
    }
    fun recolor(argb: Int, color: Int): Int {
        if (!isPaint(argb)) return argb
        val r = argb ushr 16 and 255; val g = argb ushr 8 and 255
        val diffuse = (r - g) / 185.0
        val reflection = g * .70
        fun channel(shift: Int) = (((color ushr shift and 255) * diffuse) + reflection).roundToInt().coerceIn(0,255)
        return (argb and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
