package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.*

/** Physical driver position is independent of reading direction. */
class DriverPaneArrangement(private val rightHandDrive: Boolean) : Arrangement.Horizontal {
    override val spacing: Dp = 18.dp
    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        if (sizes.size == 2) {
            outPositions[0] = if(rightHandDrive) 0 else totalSize - sizes[0]
            outPositions[1] = if(rightHandDrive) totalSize - sizes[1] else 0
        } else {
            var position = 0
            sizes.forEachIndexed { index, width -> outPositions[index] = position; position += width + spacing.roundToPx() }
        }
    }
}
