package com.dante.zeekrcapabilitylab.preflight.continuous

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/** Source-agnostic OES receiver. Its owning input context/thread alone may acquire/copy/release.
 * Camera or synthetic EGL producer uses [surface]. Caller must acknowledge producer end first.
 * The SurfaceTexture matrix is applied on EVERY acquired image, before any layout repack.
 */
internal class SharedOesFrameInput(private val width: Int, private val height: Int) {
    private val id = IntArray(1)
    private var texture: SurfaceTexture? = null
    private var ownedSurface: Surface? = null
    private val draw = ProbeGlDraw()
    private var program = 0
    private val transform = FloatArray(16)
    val notifications = AtomicLong()
    val surface get() = checkNotNull(ownedSurface)
    var timestampNs = 0L; private set
    var polls = 0L; private set
    fun initialize() {
        program = draw.program(FRAGMENT)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
        ProbeGlDraw.textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        texture = SurfaceTexture(id[0]).also { value ->
            value.setDefaultBufferSize(width, height)
            value.setOnFrameAvailableListener({ notifications.incrementAndGet() }, Handler(Looper.getMainLooper()))
        }
        ownedSurface = Surface(checkNotNull(texture)); ProbeGlDraw.checkGl()
    }
    fun acquireLatest(): Long {
        val active = checkNotNull(texture)
        polls++; active.updateTexImage()
        timestampNs = active.timestamp
        active.getTransformMatrix(transform)
        return timestampNs
    }
    fun copyTo(target: ProbeGlTarget) {
        check(timestampNs > 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, width, height); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "source"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "size"), width.toFloat(), height.toFloat())
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
        draw.quad(program); ProbeGlDraw.checkGl()
    }
    fun transformSnapshot() = transform.toList()
    fun close(producerEnded: Boolean) {
        check(producerEnded) { "OES_PRODUCER_END_NOT_ACKNOWLEDGED" }
        ownedSurface?.release(); ownedSurface = null
        texture?.setOnFrameAvailableListener(null); texture?.release(); texture = null
        GLES20.glDeleteTextures(1, id, 0); id[0] = 0; draw.close(); ProbeGlDraw.checkGl()
    }
    companion object {
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES source; uniform mat4 transform; uniform vec2 size;
            void main(){ vec2 uv=(transform*vec4(gl_FragCoord.xy/size,0.0,1.0)).xy;
                gl_FragColor=texture2D(source,uv); }
        """
    }
}
