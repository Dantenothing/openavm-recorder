package com.dante.zeekrcapabilitylab.preflight.continuous

import android.opengl.*
import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One producer context, no OES / camera / display pool. Native references survive failed close(). */
internal class ProbeSyntheticGl(private val layout: StripRepackLayout, private val nonce: Int) : ProbeGlProducer {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var initialized = false
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private val textures = IntArray(2)
    private val fbos = IntArray(2)
    private var generator = 0; private var repacker = 0
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)); position(0) }
    override var producerConfirmed = false; private set
    override var released = false; private set
    var limits: JsonObject = obj(); private set

    override fun initialize() {
        check(display != EGL14.EGL_NO_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display,v,0,v,1)) { "EGL_INITIALIZE_FAILED" }; initialized = true
        val list = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,0x3142,1,EGL14.EGL_NONE),0,list,0,1,count,0) && count[0] > 0) { "RECORDABLE_CONFIG_UNAVAILABLE" }
        config = list[0]
        context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL_CONTEXT_FAILED" }
        pbuffer = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        check(pbuffer != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
        val max = IntArray(1); val viewport = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,max,0); GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS,viewport,0)
        limits = obj("maxTexture" to max[0],"maxViewport" to viewport.toList(),"renderer" to GLES20.glGetString(GLES20.GL_RENDERER))
        for (size in listOf(layout.input,layout.encoded)) check(size.width <= max[0] && size.height <= max[0] &&
            size.width <= viewport[0] && size.height <= viewport[1]) { "FULL_RASTER_GL_LIMIT_REJECTED" }
        GLES20.glGenTextures(2,textures,0); GLES20.glGenFramebuffers(2,fbos,0)
        for ((index,size) in listOf(layout.input,layout.encoded).withIndex()) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[index])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,size.width,size.height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbos[index])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,textures[index],0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "LARGE_FBO_INCOMPLETE" }
        }
        generator = program(GENERATE); repacker = program(REPACK)
        glCheck()
    }
    override fun attach(surface: Surface) {
        window = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
        check(window != EGL14.EGL_NO_SURFACE) { "CODEC_EGL_WINDOW_FAILED" }
    }
    private fun source(frame: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbos[0]); GLES20.glViewport(0,0,layout.input.width,layout.input.height)
        GLES20.glUseProgram(generator)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(generator,"size"),layout.input.width.toFloat(),layout.input.height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(generator,"strip"),layout.stripHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(generator,"cell"),ProbeFramePattern.cellWidth(layout.input.width).toFloat())
        val bytes = (0..2).flatMap { region -> ProbeFramePattern.marker(nonce,frame,region).map { (it.toInt() and 255).toFloat() } }.toFloatArray()
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(generator,"code[0]"),21,bytes,0)
        quad(generator)
    }
    private fun repack(target: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target); GLES20.glViewport(0,0,layout.encoded.width,layout.encoded.height)
        GLES20.glUseProgram(repacker); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(repacker,"source"),0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(repacker,"inputSize"),layout.input.width.toFloat(),layout.input.height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(repacker,"strip"),layout.stripHeight.toFloat())
        quad(repacker)
    }
    override fun geometry(): JsonObject {
        check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
        val frame = 37; source(frame); repack(fbos[1])
        // Offline correctness readback, never inside the paced encode loop.
        val size = layout.encoded; val pixels = ByteBuffer.allocateDirect(size.width * size.height * 4)
        GLES20.glReadPixels(0,0,size.width,size.height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels); glCheck()
        val ys = ((0 until size.height step 31) + listOf(0,7,8,15,23,24,size.height-1,
            layout.input.height - 2 * layout.stripHeight - 21,layout.input.height - 2 * layout.stripHeight - 20,
            layout.input.height - 2 * layout.stripHeight - 1,layout.input.height - 2 * layout.stripHeight)).filter { it in 0 until size.height }.distinct()
        val xs = ((0 until size.width step 29) + listOf(0,layout.input.width-1,layout.input.width,
            2*layout.input.width-1,2*layout.input.width,size.width-1) + (0..2).flatMap { col -> (0 until 56).map {
                col*layout.input.width + 16 + it*ProbeFramePattern.cellWidth(layout.input.width) + ProbeFramePattern.cellWidth(layout.input.width)/2 } }).distinct()
        var tested = 0
        for (y in ys) for (x in xs) {
            val expected = ProbeFramePattern.rgbAtEncoded(x,y,layout,nonce,frame)
            val offset = ((size.height - 1 - y) * size.width + x)*4
            for (channel in 0..2) check(kotlin.math.abs((pixels[offset+channel].toInt() and 255) -
                (expected shr ((2-channel)*8) and 255)) <= 2) { "GPU_REPACK_ORACLE_MISMATCH" }
            tested++
        }
        return obj("status" to "PASS","samples" to tested,"fullRasterReadback" to true,"oracle" to "INDEPENDENT_CPU_INTEGER",
            "paddingAndTail" to "CHECKED","source" to listOf(layout.input.width,layout.input.height),
            "output" to listOf(size.width,size.height),"limits" to limits)
    }
    override fun submit(frame: Int, ptsUs: Long) {
        check(!producerConfirmed && window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display,window,window,context))
        source(frame); repack(0); glCheck()
        check(EGLExt.eglPresentationTimeANDROID(display,window,ptsUs * 1000)) { "PRESENTATION_TIME_FAILED" }
        check(EGL14.eglSwapBuffers(display,window)) { "CODEC_SWAP_FAILED" }
    }
    /** Only a terminal fence, not per-frame glFinish. Codec drain remains running throughout. */
    override fun endProducer() {
        if (producerConfirmed) return
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
            GLES20.glFinish(); glCheck()
        }
        if (window != EGL14.EGL_NO_SURFACE) {
            check(EGL14.eglDestroySurface(display,window)); window = EGL14.EGL_NO_SURFACE
        }
        producerConfirmed = true
    }
    override fun close() {
        endProducer()
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
            GLES20.glDeleteFramebuffers(2,fbos,0); GLES20.glDeleteTextures(2,textures,0)
            if(generator != 0) GLES20.glDeleteProgram(generator)
            if(repacker != 0) GLES20.glDeleteProgram(repacker)
        }
        if (initialized) {
            check(EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT))
            if(pbuffer != EGL14.EGL_NO_SURFACE) { check(EGL14.eglDestroySurface(display,pbuffer)); pbuffer = EGL14.EGL_NO_SURFACE }
            if(context != EGL14.EGL_NO_CONTEXT) { check(EGL14.eglDestroyContext(display,context)); context = EGL14.EGL_NO_CONTEXT }
            check(EGL14.eglTerminate(display)); initialized = false
        }
        check(EGL14.eglReleaseThread()); released = true
    }
    private fun quad(program: Int) {
        val attr = GLES20.glGetAttribLocation(program,"position")
        vertices.position(0); GLES20.glEnableVertexAttribArray(attr)
        GLES20.glVertexAttribPointer(attr,2,GLES20.GL_FLOAT,false,0,vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GLES20.glDisableVertexAttribArray(attr)
    }
    private fun program(fragment: String): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position; void main(){gl_Position=vec4(position,0.0,1.0);}")
        var fs = 0; var p = 0
        try {
            fs = shader(GLES20.GL_FRAGMENT_SHADER,fragment); p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p)
            val status=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,status,0)
            check(status[0] != 0) { "GL_PROGRAM_LINK_FAILED" }; return p
        } catch(t:Throwable) { if(p!=0)GLES20.glDeleteProgram(p); throw t }
        finally { GLES20.glDeleteShader(vs); if(fs!=0)GLES20.glDeleteShader(fs) }
    }
    private fun shader(type:Int,text:String):Int {
        val shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,text); GLES20.glCompileShader(shader)
        val status=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
        if(status[0]==0) { GLES20.glDeleteShader(shader); error("GL_SHADER_COMPILE_FAILED") }; return shader
    }
    private fun glCheck() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GL_OPERATION_FAILED" } }
    companion object {
        internal const val GENERATE = """
            precision highp float;
            uniform vec2 size; uniform float strip; uniform float cell; uniform float code[21];
            uniform float dynamicFrame; uniform float pressure;
            void main(){
                float x=floor(gl_FragCoord.x); float y=size.y-1.0-floor(gl_FragCoord.y);
                float region=floor(y/strip); float local=y-region*strip;
                vec3 rgb=vec3(32.0+mod(floor(x/32.0),6.0)*32.0,32.0+mod(floor(y/32.0),6.0)*32.0,64.0+region*64.0);
                // Preserve barcode and fixed color patches. The rest changes every source frame.
                if(pressure>0.5 && !(x<128.0 && local<224.0)) {
                    vec2 tile=floor(vec2(x,y)/8.0);
                    float seed=dot(tile,vec2(12.9898,78.233))+dynamicFrame*17.713;
                    rgb=vec3(fract(sin(seed)*43758.5453),fract(sin(seed+2.7)*22578.1459),fract(sin(seed+5.3)*19642.349))*224.0+16.0;
                }
                if(y>=size.y-20.0) rgb=vec3(16.0,220.0,220.0);
                if(local>=8.0 && local<24.0 && x>=16.0 && x<16.0+56.0*cell){
                    float bit=floor((x-16.0)/cell); float wanted=region*7.0+floor(bit/8.0);
                    float value=0.0;
                    for(int i=0;i<21;i++){ if(abs(float(i)-wanted)<0.1) value=code[i]; }
                    float white=mod(floor(value/pow(2.0,7.0-mod(bit,8.0))),2.0);
                    rgb=vec3(white*255.0);
                }
                gl_FragColor=vec4(rgb/255.0,1.0);
            }
        """
        internal const val REPACK = """
            precision highp float;
            uniform sampler2D source; uniform vec2 inputSize; uniform float strip;
            void main(){
                float x=floor(gl_FragCoord.x); float y=strip-1.0-floor(gl_FragCoord.y);
                float column=floor(x/inputSize.x); float sx=x-column*inputSize.x; float sy=column*strip+y;
                if(sy>=inputSize.y) gl_FragColor=vec4(0.0,0.0,0.0,1.0);
                else gl_FragColor=texture2D(source,vec2((sx+0.5)/inputSize.x,1.0-(sy+0.5)/inputSize.y));
            }
        """
    }
}
