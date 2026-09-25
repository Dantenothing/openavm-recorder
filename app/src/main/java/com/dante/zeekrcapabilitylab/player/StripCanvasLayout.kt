package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.PixelRectangle
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract

data class StripCanvasPiece(val encoded: FloatBounds, val logical: FloatBounds)

/** Reconstruct the logical child coordinate space before any viewport/lens/presentation transform. */
object StripCanvasLayout {
    fun plan(layout: StripRepackContract, child: FloatBounds): List<StripCanvasPiece> {
        require(child.width > 0f && child.height > 0f)
        fun bounds(rect: PixelRectangle, width: Int, height: Int) = FloatBounds(
            child.left + child.width * rect.left / width,
            child.top + child.height * rect.top / height,
            child.left + child.width * (rect.left + rect.width) / width,
            child.top + child.height * (rect.top + rect.height) / height,
        )
        return layout.cropPieces(PixelRectangle(0, 0, layout.inputWidth, layout.inputHeight)).map {
            StripCanvasPiece(bounds(it.encoded, layout.encodedWidth, layout.encodedHeight),
                bounds(it.source, layout.inputWidth, layout.inputHeight))
        }
    }
}
