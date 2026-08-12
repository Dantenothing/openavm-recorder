package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import kotlin.math.abs

/**
 * Surface selection for the product preview. The original tester's small
 * 640x480-compatible hint remains the stable fallback. A 1280x5140 buffer may
 * only be requested when SurfaceTexture explicitly declares that exact size.
 */
object CompositePreviewSizePolicy {
    private const val HIGH_RES_WIDTH = 1280
    private const val HIGH_RES_HEIGHT = 5140
    private const val PREFERRED_WIDTH = 640
    private const val PREFERRED_HEIGHT = 480
    private const val MAX_SMALL_WIDTH = 1280
    private const val MAX_SMALL_HEIGHT = 720

    /**
     * Mirrors CameraScreen.CameraPreviewController.chooseSize(): prefer the
     * ordinary declared surface nearest 640x480, then fall back to the first
     * declared surface. No undeclared size is invented.
     */
    fun choose(declared: Collection<ProfileSize>): ProfileSize? {
        val valid = declared.filter { it.width > 0 && it.height > 0 }
        return valid
            .filter { it.width <= MAX_SMALL_WIDTH && it.height <= MAX_SMALL_HEIGHT }
            .minByOrNull {
                abs(it.width - PREFERRED_WIDTH) + abs(it.height - PREFERRED_HEIGHT)
            }
            ?: valid.firstOrNull()
    }

    fun chooseDeclaredHighResolution(declared: Collection<ProfileSize>): ProfileSize? =
        declared.firstOrNull {
            it.width == HIGH_RES_WIDTH && it.height == HIGH_RES_HEIGHT
        }
}
