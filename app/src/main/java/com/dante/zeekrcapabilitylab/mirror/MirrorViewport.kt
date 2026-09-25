package com.dante.zeekrcapabilitylab.mirror

/** Source-plane framing only: never changes a producer buffer or saved video. */
data class MirrorViewport(val zoom: Float = 1f, val centerX: Float = 0f, val centerY: Float = 0f) {
    fun sanitized(cropZoom: Float = 1.25f): MirrorViewport {
        val z = zoom.takeIf { it.isFinite() }?.coerceIn(1f, 3f) ?: 1f
        val limit = 1f - 1f / (z * safeCrop(cropZoom))
        return MirrorViewport(z, centerX.safeCenter(limit), centerY.safeCenter(limit))
    }

    fun gesture(scale: Float, panX: Float, panY: Float, cropZoom: Float): MirrorViewport {
        val current = sanitized(cropZoom)
        if (!scale.isFinite() || scale <= 0 || !panX.isFinite() || !panY.isFinite()) return current
        val z = (current.zoom * scale).coerceIn(1f, 3f)
        val effective = z * safeCrop(cropZoom)
        return MirrorViewport(z, current.centerX - panX / effective, current.centerY - panY / effective)
            .sanitized(cropZoom)
    }

    private fun Float.safeCenter(limit: Float): Float {
        val value = takeIf { it.isFinite() }?.coerceIn(-limit, limit) ?: 0f
        return if (value == 0f) 0f else value // Normalize -0.0 before persisting/comparing framing.
    }
    private fun safeCrop(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(1f, 1.6f) ?: 1.25f
}

/** Undo letterboxing, rotation and mirroring so the image follows the user's finger. */
object MirrorPanTransform {
    fun sourceDelta(width: Float, height: Float, aspect: Float, rotation: Int, mirrored: Boolean,
                    dx: Float, dy: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || !dx.isFinite() || !dy.isFinite()) return 0f to 0f
        val p = MirrorGeometry.corners(width, height, aspect, rotation, mirrored)
        val ax = p[2] - p[0]; val ay = p[3] - p[1]
        val bx = p[6] - p[0]; val by = p[7] - p[1]
        val determinant = ax * by - ay * bx
        return 2f * (dx * by - dy * bx) / determinant to 2f * (dy * ax - dx * ay) / determinant
    }
}
