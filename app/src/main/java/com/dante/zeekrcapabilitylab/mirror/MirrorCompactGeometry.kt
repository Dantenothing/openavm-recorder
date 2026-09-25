package com.dante.zeekrcapabilitylab.mirror

/** In dp; chrome and touch targets are fixed while the image takes the remaining space. */
object MirrorCompactGeometry {
    data class Layout(val width: Int, val bodyHeight: Int, val railWidth: Int, val compact: Boolean, val height: Int)
    fun fit(width: Int, screenWidth: Int, screenHeight: Int, video: Boolean, controls: Boolean): Layout {
        val w = width.coerceIn(minOf(300, screenWidth.coerceAtLeast(1)), minOf(1400, screenWidth.coerceAtLeast(1)))
        val header = 52
        if (!video) return Layout(minOf(w, 340), 0, 0, true, header)
        val available = (screenHeight - header - 4).coerceAtLeast(1)
        val compact = w < 600 || available < 636
        val rail = if (!controls) 0 else if (compact) 48 else 156
        // Leave space for the emergency row above Normal and the bottom resize grip.
        // The logo remains at the vertical centre; short screens use compact controls.
        val minimum = if (!controls) 1 else if (compact) 332 else 636
        val body = maxOf(minimum, w - 8 - rail - if (rail == 0) 0 else 4).coerceAtMost(available)
        return Layout(w, body, rail, compact, header + 4 + body)
    }

    fun controlsInset(bodyHeight: Int, compact: Boolean): Int =
        ((bodyHeight - (if (compact) 44 else 156) - 2 * (if (compact) 96 else 192)) / 2).coerceIn(0, 48)
}
