package com.dante.zeekrbridge.player

/** Encoded metadata can supply a provisional size before the decoder reports its display size. */
data class VideoDisplayGeometry(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = 1f,
    val metadataRotationDegrees: Int = 0,
) {
    fun aspectRatio(): Float? {
        if (width <= 0 || height <= 0) return null
        val pixelRatio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ratio = width.toDouble() * pixelRatio / height
        val rotation = ((metadataRotationDegrees % 360) + 360) % 360
        val displayRatio = if (rotation == 90 || rotation == 270) 1.0 / ratio else ratio
        return displayRatio.toFloat().takeIf { it.isFinite() && it > 0f }
    }
}

/** Each playlist item owns its dimensions; an unknown new item must not inherit the old one. */
data class PlaybackDisplaySize(val mediaId: String, val geometry: VideoDisplayGeometry) {
    fun aspectRatioFor(activeMediaId: String): Float? =
        geometry.aspectRatio().takeIf { mediaId == activeMediaId }
}
