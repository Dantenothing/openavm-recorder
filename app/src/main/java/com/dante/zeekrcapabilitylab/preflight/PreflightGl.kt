package com.dante.zeekrcapabilitylab.preflight

import android.opengl.*
import java.nio.ByteBuffer
import kotlinx.serialization.json.JsonObject

internal class PreflightCleanupUnconfirmed : IllegalStateException("CLEANUP_UNCONFIRMED")

internal object PreflightGl {
    // Strong references are retained on failed native cleanup. The run's lease remains blocked.
    private var retained: List<Any>? = null
    fun inspect(): JsonObject {
        check(retained == null) { "PREVIOUS_GL_CLEANUP_UNCONFIRMED" }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "NO_EGL_DISPLAY" }
        var context = EGL14.EGL_NO_CONTEXT; var surface = EGL14.EGL_NO_SURFACE
        var initialized = false
        try {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display,version,0,version,1)) { "EGL_INITIALIZE_FAILED" }; initialized = true
            val attributes = intArrayOf(EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0) { "NO_PBUFFER_CONFIG" }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL_CONTEXT_FAILED" }
            surface = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE),0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,surface,surface,context)) { "EGL_CURRENT_FAILED" }
            fun limit(which: Int, size: Int = 1): List<Int> = IntArray(size).also { GLES20.glGetIntegerv(which,it,0) }.toList()
            GLES20.glClearColor(1f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val pixel = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR && (pixel[0].toInt() and 255) >= 250 && (pixel[1].toInt() and 255) <= 5) { "SMALL_DRAW_READBACK_FAILED" }
            val glExt = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            val eglExt = EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS).orEmpty()
            val recordable = intArrayOf(0x3142,1,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE)
            val recordableCount = IntArray(1)
            val declaredRecordable = EGL14.eglChooseConfig(display,recordable,0,null,0,0,recordableCount,0) && recordableCount[0] > 0
            return obj("eglVersion" to version.toList(),"glesVersion" to GLES20.glGetString(GLES20.GL_VERSION),
                "vendor" to GLES20.glGetString(GLES20.GL_VENDOR),"renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
                "maxTextureSize" to limit(GLES20.GL_MAX_TEXTURE_SIZE)[0],"maxRenderbufferSize" to limit(GLES20.GL_MAX_RENDERBUFFER_SIZE)[0],
                "maxViewportDims" to limit(GLES20.GL_MAX_VIEWPORT_DIMS,2),"oesDeclared" to glExt.contains("GL_OES_EGL_image_external"),
                "presentationTimeDeclared" to eglExt.contains("EGL_ANDROID_presentation_time"),"fenceDeclared" to eglExt.contains("EGL_KHR_fence_sync"),
                "recordableConfigDeclared" to declaredRecordable, "healthCheck" to "16x16_CLEAR_ONE_PIXEL_READBACK",
                "sharedContextRuntime" to "NOT_IMPLEMENTED", "largeTextureRuntime" to "NOT_RUN", "perFrameReadback" to false)
        } finally {
            var safe = true
            if (initialized) {
                safe = EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT) && safe
                if (surface != EGL14.EGL_NO_SURFACE) safe = EGL14.eglDestroySurface(display,surface) && safe
                if (context != EGL14.EGL_NO_CONTEXT) safe = EGL14.eglDestroyContext(display,context) && safe
                safe = EGL14.eglTerminate(display) && safe
            }
            safe = EGL14.eglReleaseThread() && safe
            if (!safe) { retained = listOf(display,context,surface); throw PreflightCleanupUnconfirmed() }
        }
    }
}
