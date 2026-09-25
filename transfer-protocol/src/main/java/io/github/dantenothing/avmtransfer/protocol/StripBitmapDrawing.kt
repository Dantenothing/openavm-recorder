package io.github.dantenothing.avmtransfer.protocol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** Draw a logical crop from one decoded bitmap, including views spanning encoded strip seams. */
object StripBitmapDrawing {
    fun drawCrop(canvas: Canvas, frame: Bitmap, contract: StripRepackContract,
        crop: PixelRectangle, destination: RectF, paint: Paint) {
        require(destination.width() > 0 && destination.height() > 0)
        val matrix = Matrix()
        for (piece in contract.cropPieces(crop)) {
            val source = RectF(piece.encoded.left.toFloat() * frame.width / contract.encodedWidth,
                piece.encoded.top.toFloat() * frame.height / contract.encodedHeight,
                (piece.encoded.left + piece.encoded.width).toFloat() * frame.width / contract.encodedWidth,
                (piece.encoded.top + piece.encoded.height).toFloat() * frame.height / contract.encodedHeight)
            val target = RectF(destination.left,
                destination.top + destination.height() * piece.destination.top / crop.height,
                destination.right,
                destination.top + destination.height() * (piece.destination.top + piece.destination.height) / crop.height)
            val save = canvas.save()
            canvas.clipRect(target)
            matrix.setRectToRect(source, target, Matrix.ScaleToFit.FILL)
            canvas.drawBitmap(frame, matrix, paint)
            canvas.restoreToCount(save)
        }
    }
}
