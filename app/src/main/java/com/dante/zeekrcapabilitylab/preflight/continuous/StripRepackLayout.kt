package com.dante.zeekrcapabilitylab.preflight.continuous

import kotlinx.serialization.Serializable

internal data class RepackRaster(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val pixels: Long get() = width.toLong() * height
}

internal data class RepackPoint(val x: Int, val y: Int)
internal data class RepackRect(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class RepackRegion(val source: RepackRect, val destination: RepackRect)

/** Logical pixel coordinates, before any SurfaceTexture transform or GL origin conversion. */
@Serializable
internal data class RepackLayoutDescriptor(
    val version: Int = 1,
    val layout: String = "OPENAVM_VERTICAL_STRIPS_TO_COLUMNS",
    val coordinateOrigin: String = "TOP_LEFT",
    val inputWidth: Int,
    val inputHeight: Int,
    val stripHeight: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
)

/**
 * A reversible pixel permutation, not a claim about GL sampling or lossy codec quality.
 * The last strip is top-aligned; unused rows in that column are padding, never source pixels.
 * Diagnostic-only until GPU round-trip, file decoding and product layout readers are verified.
 */
internal class StripRepackLayout(val input: RepackRaster, val stripHeight: Int) {
    val columns: Int
    val encoded: RepackRaster
    val paddingPixels: Long get() = encoded.pixels - input.pixels
    val regions: List<RepackRegion>

    init {
        require(stripHeight in 1..input.height)
        val count = (input.height.toLong() + stripHeight - 1) / stripHeight
        // A descriptor must not allocate an unbounded list of draw regions.
        require(count in 1..16)
        columns = count.toInt()
        val width = input.width.toLong() * columns
        require(width <= Int.MAX_VALUE)
        encoded = RepackRaster(width.toInt(), stripHeight)
        regions = List(columns) { column ->
            val top = column.toLong() * stripHeight
            val height = minOf(stripHeight.toLong(), input.height - top).toInt()
            RepackRegion(
                RepackRect(0, top.toInt(), input.width, height),
                RepackRect(column * input.width, 0, input.width, height),
            )
        }
    }

    fun toEncoded(x: Int, y: Int): RepackPoint {
        require(x in 0 until input.width && y in 0 until input.height)
        return RepackPoint((y / stripHeight) * input.width + x, y % stripHeight)
    }

    /** Null means explicit padding. Out-of-raster coordinates are invalid, not padding. */
    fun toInput(x: Int, y: Int): RepackPoint? {
        require(x in 0 until encoded.width && y in 0 until encoded.height)
        val inputY = (x / input.width).toLong() * stripHeight + y
        return if (inputY < input.height) RepackPoint(x % input.width, inputY.toInt()) else null
    }

    fun descriptor() = RepackLayoutDescriptor(
        inputWidth = input.width, inputHeight = input.height, stripHeight = stripHeight,
        encodedWidth = encoded.width, encodedHeight = encoded.height,
    )

    companion object {
        fun fromDescriptor(descriptor: RepackLayoutDescriptor): StripRepackLayout {
            require(descriptor.version == 1 && descriptor.layout == "OPENAVM_VERTICAL_STRIPS_TO_COLUMNS")
            require(descriptor.coordinateOrigin == "TOP_LEFT")
            val layout = StripRepackLayout(RepackRaster(descriptor.inputWidth, descriptor.inputHeight), descriptor.stripHeight)
            require(descriptor.encodedWidth == layout.encoded.width && descriptor.encodedHeight == layout.encoded.height)
            return layout
        }
    }
}
