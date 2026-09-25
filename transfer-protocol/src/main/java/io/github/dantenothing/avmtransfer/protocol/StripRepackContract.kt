package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

data class RasterPixel(val x: Int, val y: Int)

/** A crop may cross a strip boundary and therefore require multiple encoded rectangles. */
data class StripCropPiece(
    val source: PixelRectangle,
    val encoded: PixelRectangle,
    /** Position in the reconstructed crop, before rotation, mirroring or lens correction. */
    val destination: PixelRectangle,
)

/**
 * Versioned storage geometry for continuous surround video. This describes a reversible pixel
 * arrangement, not camera identities, four-grid presentation, codec quality or runtime support.
 * A reader must obtain the descriptor from explicit file metadata and validate the actual track
 * dimensions. Dimensions alone must never be used to infer this layout.
 * Field names match the descriptor already exercised by the P1/P2 diagnostics.
 */
@Serializable
data class StripRepackContract(
    @Required val version: Int = 1,
    @Required val layout: String = FORMAT,
    @Required val coordinateOrigin: String = "TOP_LEFT",
    val inputWidth: Int,
    val inputHeight: Int,
    val stripHeight: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
) {
    fun validate(): List<String> = buildList {
        if (version != 1 || layout != FORMAT) add("UNSUPPORTED_STRIP_LAYOUT")
        if (coordinateOrigin != "TOP_LEFT") add("UNSUPPORTED_COORDINATE_ORIGIN")
        if (inputWidth !in 1..16384 || inputHeight !in 1..16384) add("INVALID_INPUT_SIZE")
        if (encodedWidth !in 1..16384 || encodedHeight !in 1..16384) add("INVALID_ENCODED_SIZE")
        if (stripHeight !in 1..inputHeight) add("INVALID_STRIP_HEIGHT")
        if (inputWidth > 0 && inputHeight > 0 && stripHeight > 0) {
            val columns = (inputHeight.toLong() + stripHeight - 1) / stripHeight
            if (columns !in 1..16) add("STRIP_COUNT_OUT_OF_RANGE")
            if (encodedWidth.toLong() != inputWidth.toLong() * columns || encodedHeight != stripHeight)
                add("ENCODED_GEOMETRY_MISMATCH")
        }
    }.distinct()

    fun matchesTrack(width: Int, height: Int): Boolean =
        validate().isEmpty() && width == encodedWidth && height == encodedHeight

    fun toEncoded(x: Int, y: Int): RasterPixel {
        requireValid()
        require(x in 0 until inputWidth && y in 0 until inputHeight)
        return RasterPixel((y / stripHeight) * inputWidth + x, y % stripHeight)
    }

    /** Padding is explicitly absent source data; an invalid coordinate is an error. */
    fun toSource(x: Int, y: Int): RasterPixel? {
        requireValid()
        require(x in 0 until encodedWidth && y in 0 until encodedHeight)
        val sourceY = (x / inputWidth) * stripHeight + y
        return if (sourceY < inputHeight) RasterPixel(x % inputWidth, sourceY) else null
    }

    /** Split a logical view without dropping or stretching its pixels at encoded strip seams. */
    fun cropPieces(crop: PixelRectangle): List<StripCropPiece> {
        requireValid()
        require(crop.within(inputWidth, inputHeight)) { "SOURCE_CROP_OUT_OF_BOUNDS" }
        val bottom = crop.top + crop.height
        val first = crop.top / stripHeight
        val last = (bottom - 1) / stripHeight
        return (first..last).map { column ->
            val top = maxOf(crop.top, column * stripHeight)
            val height = minOf(bottom, (column + 1) * stripHeight) - top
            StripCropPiece(
                PixelRectangle(crop.left, top, crop.width, height),
                PixelRectangle(column * inputWidth + crop.left, top % stripHeight, crop.width, height),
                PixelRectangle(0, top - crop.top, crop.width, height),
            )
        }
    }

    private fun requireValid() { require(validate().isEmpty()) { "INVALID_STRIP_LAYOUT" } }

    companion object {
        const val FORMAT = "OPENAVM_VERTICAL_STRIPS_TO_COLUMNS"
        const val TRANSFER_CAPABILITY = "OPENAVM_VERTICAL_STRIPS_TO_COLUMNS_V1"
    }
}
