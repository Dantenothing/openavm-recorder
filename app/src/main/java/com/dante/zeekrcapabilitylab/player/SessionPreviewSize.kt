package com.dante.zeekrcapabilitylab.player

/** Display bounds only: resizing a preview must never change its decoder or source pixels. */
internal data class SessionPreviewSize(val width: Float, val height: Float)

internal fun sessionVideoAspect(width: Int, height: Int, rotationDegrees: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 9f
    val rotated = ((rotationDegrees % 360) + 360) % 360 in setOf(90, 270)
    return if (rotated) height.toFloat() / width else width.toFloat() / height
}

/** Matches the established square surround preview and fits ordinary video without stretching. */
internal fun fitSessionPreview(availableWidth: Float, availableHeight: Float, aspect: Float): SessionPreviewSize {
    if (!availableWidth.isFinite() || !availableHeight.isFinite() || availableWidth <= 0 || availableHeight <= 0)
        return SessionPreviewSize(0f, 0f)
    val ratio = aspect.takeIf { it.isFinite() && it > 0 } ?: 1f
    val width = minOf(availableWidth, availableHeight * ratio)
    return SessionPreviewSize(width, width / ratio)
}
