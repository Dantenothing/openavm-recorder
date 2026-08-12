package com.dante.zeekrcapabilitylab.product

/**
 * Pure policy for the product 录像 home page.
 *
 * The old automatic preview crashed because it replaced the working producer
 * with a GL-owned SurfaceTexture. The current path has been verified on the car:
 * Camera2 feeds one ordinary TextureView and its parent redraws that same child
 * into four cells. Auto preview is allowed only for that proven product path.
 */
object ProductHomeCameraPolicy {
    const val TRIGGER_USER_START_RECORDING = "USER_START_RECORDING"
    const val TRIGGER_AUTO_START_RECORDING = "AUTO_START_RECORDING"
    const val TRIGGER_USER_START_PREVIEW = "USER_START_PREVIEW"
    const val TRIGGER_AUTO_PREVIEW = "AUTO_PREVIEW"

    /** The product home restores the proven preview as soon as it is visible. */
    fun shouldAutoStartPreview(elapsedOnPageMs: Long): Boolean = elapsedOnPageMs >= 0L

    /** Re-entering the page restores preview after recording/navigation released it. */
    fun shouldAutoAccessCameraOnRevisit(revisitCount: Int): Boolean = revisitCount >= 0

    /** Product startup and the explicit button may record; preview uses the proven path only. */
    fun cameraAccessAllowed(trigger: String): Boolean =
        trigger == TRIGGER_USER_START_RECORDING ||
            trigger == TRIGGER_AUTO_START_RECORDING ||
            trigger == TRIGGER_USER_START_PREVIEW ||
            trigger == TRIGGER_AUTO_PREVIEW
}
