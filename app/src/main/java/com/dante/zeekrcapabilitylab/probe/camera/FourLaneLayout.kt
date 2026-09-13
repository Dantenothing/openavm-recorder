package com.dante.zeekrcapabilitylab.probe.camera

import kotlinx.serialization.Serializable

@Serializable
enum class CompositeAxis { HORIZONTAL, VERTICAL, GRID_2X2 }

/**
 * Pure layout decision for a four-camera composite.
 *
 * The analyzer internally uses the established vertical-stack pipeline. A
 * strongly horizontal 4:1 frame (for example 5120x1280) is therefore rotated
 * 90 degrees before divider detection/cropping. Ordinary 16:9 frames keep the
 * existing vertical-lane assumption because earlier captures may contain four
 * stacked strips inside a conventional frame.
 */
@Serializable
data class FourLaneLayoutDecision(
    val axis: CompositeAxis,
    val normalizationRotationDegrees: Int,
    val normalizedWidth: Int,
    val normalizedHeight: Int,
    val reason: String,
)

object FourLaneLayout {
    private const val STRONG_FOUR_LANE_RATIO = 3.2

    fun infer(width: Int, height: Int): FourLaneLayoutDecision {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        val horizontal = width.toDouble() / height >= STRONG_FOUR_LANE_RATIO
        return if (horizontal) {
            FourLaneLayoutDecision(
                axis = CompositeAxis.HORIZONTAL,
                normalizationRotationDegrees = 90,
                normalizedWidth = height,
                normalizedHeight = width,
                reason = "Strong horizontal composite; rotate 90 degrees for four-lane analysis.",
            )
        } else {
            FourLaneLayoutDecision(
                axis = CompositeAxis.VERTICAL,
                normalizationRotationDegrees = 0,
                normalizedWidth = width,
                normalizedHeight = height,
                reason = "Use existing vertical-stack analysis.",
            )
        }
    }
}
