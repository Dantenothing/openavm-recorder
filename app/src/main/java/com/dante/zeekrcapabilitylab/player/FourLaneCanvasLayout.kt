package com.dante.zeekrcapabilitylab.player

/** Android-free rectangle used by both the renderer and local unit tests. */
data class FloatBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class FourLaneCanvasDraw(
    val lane: Int,
    val source: FloatBounds,
    val destination: FloatBounds,
)

/**
 * Maps one already-visible composite TextureView into four 2x2 draw operations.
 * It never creates, owns, or changes the camera producer SurfaceTexture.
 */
object FourLaneCanvasLayout {
    fun plan(
        videoWidth: Int,
        videoHeight: Int,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        contentHeight: Float,
        destinationWidth: Float,
        destinationHeight: Float,
    ): List<FourLaneCanvasDraw> {
        require(contentWidth > 0f && contentHeight > 0f) { "content bounds must be positive" }
        require(destinationWidth > 0f && destinationHeight > 0f) {
            "destination bounds must be positive"
        }

        val cellWidth = destinationWidth / 2f
        val cellHeight = destinationHeight / 2f
        return (1..4).map { lane ->
            val window = FourLaneTextureLayout.windowForLane(videoWidth, videoHeight, lane)
            val index = lane - 1
            val column = index % 2
            val row = index / 2
            FourLaneCanvasDraw(
                lane = lane,
                source = FloatBounds(
                    left = contentLeft + contentWidth * window.u,
                    top = contentTop + contentHeight * window.v,
                    right = contentLeft + contentWidth * (window.u + window.width),
                    bottom = contentTop + contentHeight * (window.v + window.height),
                ),
                destination = FloatBounds(
                    left = column * cellWidth,
                    top = row * cellHeight,
                    right = (column + 1) * cellWidth,
                    bottom = (row + 1) * cellHeight,
                ),
            )
        }
    }

    fun planSingle(
        videoWidth: Int,
        videoHeight: Int,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        contentHeight: Float,
        destinationWidth: Float,
        destinationHeight: Float,
        lane: Int,
    ): FourLaneCanvasDraw {
        require(lane in 1..4) { "lane must be in 1..4" }
        val selected = plan(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            contentLeft = contentLeft,
            contentTop = contentTop,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            destinationWidth = destinationWidth,
            destinationHeight = destinationHeight,
        ).first { it.lane == lane }
        return selected.copy(
            destination = FloatBounds(0f, 0f, destinationWidth, destinationHeight),
        )
    }
}
