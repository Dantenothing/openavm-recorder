package com.dante.zeekrcapabilitylab.product

/** Display-only inverse-projection parameters; original camera/MP4 data is untouched. */
data class FisheyeCorrectionConfig(
    val targetFovDegrees: Float = DEFAULT_FOV_DEGREES,
    val cropZoom: Float = DEFAULT_CROP_ZOOM,
    val centerX: Float = DEFAULT_CENTER_X,
    val centerY: Float = DEFAULT_CENTER_Y,
) {
    fun sanitized(): FisheyeCorrectionConfig = copy(
        targetFovDegrees = targetFovDegrees.coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES),
        cropZoom = cropZoom.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM),
        centerX = centerX.coerceIn(MIN_CENTER, MAX_CENTER),
        centerY = centerY.coerceIn(MIN_CENTER, MAX_CENTER),
    )

    companion object {
        const val DEFAULT_FOV_DEGREES = 110f
        const val DEFAULT_CROP_ZOOM = 1.25f
        const val DEFAULT_CENTER_X = 0.50f
        const val DEFAULT_CENTER_Y = 0.47f
        const val MIN_FOV_DEGREES = 90f
        const val MAX_FOV_DEGREES = 140f
        const val MIN_CROP_ZOOM = 1f
        const val MAX_CROP_ZOOM = 1.6f
        const val MIN_CENTER = 0.40f
        const val MAX_CENTER = 0.60f
    }
}
