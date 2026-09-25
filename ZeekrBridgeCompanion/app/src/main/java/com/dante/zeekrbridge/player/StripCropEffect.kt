package com.dante.zeekrbridge.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.dante.zeekrbridge.core.PixelCrop
import io.github.dantenothing.avmtransfer.protocol.PixelRectangle
import io.github.dantenothing.avmtransfer.protocol.StripRasterShader
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract

/** Produces one ordinary view, including both sides of a storage-strip seam, in a single GL pass. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StripCropEffect(private val raster: StripRepackContract, private val crop: PixelCrop) : GlEffect {
    init {
        require(raster.validate().isEmpty())
        require(crop.sourceWidth == raster.inputWidth && crop.sourceHeight == raster.inputHeight)
        raster.cropPieces(PixelRectangle(crop.x0, crop.y0, crop.x1 - crop.x0, crop.y1 - crop.y0))
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Repacked camera exports require SDR input")
        return Program(raster, crop)
    }

    private class Program(private val raster: StripRepackContract, private val crop: PixelCrop) : BaseGlShaderProgram(false, 1) {
        private val program = try {
            GlProgram(VERTEX, FRAGMENT).apply {
                setBufferAttribute("aPosition", floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), 2)
                setFloatsUniform("uRasterSource", floatArrayOf(raster.inputWidth.toFloat(), raster.inputHeight.toFloat()))
                setFloatsUniform("uRasterStorage", floatArrayOf(raster.encodedWidth.toFloat(), raster.encodedHeight.toFloat(), raster.stripHeight.toFloat(), 1f))
                setFloatsUniform("uWindow", floatArrayOf(crop.x0.toFloat() / raster.inputWidth,
                    1f - crop.y1.toFloat() / raster.inputHeight, (crop.x1 - crop.x0).toFloat() / raster.inputWidth,
                    (crop.y1 - crop.y0).toFloat() / raster.inputHeight))
            }
        } catch (error: Exception) { throw VideoFrameProcessingException.from(error) }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (!raster.matchesTrack(inputWidth, inputHeight))
                throw VideoFrameProcessingException("RASTER_TRACK_SIZE_MISMATCH")
            return Size(crop.x1 - crop.x0, crop.y1 - crop.y0)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexture", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (error: Exception) { throw VideoFrameProcessingException.from(error, presentationTimeUs) }
        }

        override fun release() {
            try { super.release() } finally {
                try { program.delete() } catch (error: Exception) { throw VideoFrameProcessingException.from(error) }
            }
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 aPosition;
            varying highp vec2 vUv;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aPosition * 0.5 + 0.5; }
        """
        private val FRAGMENT = """
            precision highp float;
            varying highp vec2 vUv;
            uniform sampler2D uTexture;
            uniform vec4 uWindow;
            vec4 readEncodedRaster(vec2 uv) { return texture2D(uTexture, uv); }
            ${StripRasterShader.sampling}
            void main() { gl_FragColor = sampleLogicalRaster(uWindow.xy + uWindow.zw * vUv); }
        """.trimIndent()
    }
}
