package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.opengl.*
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.mirror.MirrorPresentation
import com.dante.zeekrcapabilitylab.player.FourLaneTextureLayout
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.continuous.SharedInputFramePool.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real camera adapter using the same P1 OES receiver, four-slot pool, repacker and encoder sink. */
internal class CameraSharedInputGl(context:Context,private val layout:StripRepackLayout,private val nonce:Int,
    maxFrames:Int,private val cancelled:()->Boolean,private val submitted:(Long)->Unit) {
    private val root=ProbeEglNode()
    private val receiver=SharedOesFrameInput(layout.input.width,layout.input.height)
    private val draw=ProbeGlDraw()
    private val pool=SharedInputFramePool()
    private val slots=Array(pool.capacity) {ProbeGlTarget(layout.input.width,layout.input.height)}
    private val fences=LongArray(pool.capacity)
    private val failure=AtomicReference<Throwable?>()
    private val readers=ArrayList<Consumer>()
    private val settings=SettingsStore.get(context)
    private val presentation=MirrorPresentation(context)
    private val rear=settings.mirrorRearLane
    private val ledger=CameraFrameLedger(maxFrames)
    private val acquiredNs=ArrayList<Long>()
    private var stamp=0
    private var writing:SharedInputFramePool.Write?=null
    private var ending=false
    private var lastSource=0L
    private var firstSource=0L
    @Volatile private var encodedSubmissions=0
    @Volatile private var lastEncodedPts=0L
    var producerConfirmed=false;private set
    var released=false;private set
    val cameraSurface get()=receiver.surface
    val frameCount get()=ledger.size
    val lastPtsUs get()=ledger.lastPts
    fun pts()=ledger.pts()
    fun sourceTimes()=acquiredNs.toList()
    fun actualEncodedEndPtsUs()=lastEncodedPts+33_334L
    fun initialize() {
        check(rear in 1..4) {"P2_REAR_VIEW_NOT_CONFIGURED"}
        root.initialize()
        val limit=IntArray(1);GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,limit,0)
        check(limit[0]>=maxOf(layout.input.height,layout.encoded.width)) {"P2_GL_RASTER_LIMIT"}
        slots.forEach {it.allocate()};receiver.initialize();stamp=draw.program(ProbeSyntheticGl.GENERATE)
    }
    fun attach(surface:Surface) {
        listOf(Reader.ENCODER,Reader.DISPLAY_A,Reader.DISPLAY_B).forEach {kind ->
            val reader=Consumer(kind,if(kind==Reader.ENCODER)surface else null)
            readers+=reader;reader.start()
            check(reader.ready.await(4,TimeUnit.SECONDS)) {"P2_READER_START_TIMEOUT"};checkHealthy()
        }
    }
    /** Polling is independent of UI frame callbacks; repeated timestamps do not count as progress. */
    fun acquire():Boolean {
        checkHealthy();root.current()
        val timestamp=receiver.acquireLatest()
        if(timestamp<=0 || timestamp==lastSource)return false
        check(timestamp>lastSource) {"CAMERA_TIMESTAMP_REGRESSED"}
        val start=SystemClock.elapsedRealtime()
        var write=pool.reserve()
        while(write==null) {
            checkHealthy();check(SystemClock.elapsedRealtime()-start<500) {"P2_ENCODER_BACKPRESSURE"}
            Thread.sleep(1);write=pool.reserve()
        }
        writing=write
        if(fences[write.slot]!=0L) {ProbeGlDraw.waitFence(fences[write.slot]);GLES30.glDeleteSync(fences[write.slot]);fences[write.slot]=0}
        val id=ledger.size
        val pts=requireNotNull(ledger.admit(timestamp))
        receiver.copyTo(slots[write.slot])
        stampIdentity(id)
        fences[write.slot]=ProbeGlDraw.fence()
        if(firstSource==0L)firstSource=timestamp
        pool.publish(write,id,pts*1000+1_000_000_000L);writing=null
        lastSource=timestamp;acquiredNs+=timestamp;return true
    }
    private fun stampIdentity(frame:Int) {
        // Only narrow diagnostic barcode bands change pixels. Production will not use this overlay.
        GLES20.glUseProgram(stamp)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(stamp,"size"),layout.input.width.toFloat(),layout.input.height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stamp,"strip"),layout.stripHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stamp,"cell"),ProbeFramePattern.cellWidth(layout.input.width).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stamp,"dynamicFrame"),frame.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stamp,"pressure"),0f)
        val bytes=(0..2).flatMap {r->ProbeFramePattern.marker(nonce,frame,r).map {(it.toInt() and 255).toFloat()}}.toFloatArray()
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(stamp,"code[0]"),21,bytes,0)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        try {for(r in 0..2) {
            GLES20.glScissor(16,layout.input.height-(r*layout.stripHeight+24),56*ProbeFramePattern.cellWidth(layout.input.width),16)
            draw.quad(stamp)
        }} finally {GLES20.glDisable(GLES20.GL_SCISSOR_TEST)}
        ProbeGlDraw.checkGl()
    }
    fun checkHealthy() {check(!cancelled()) {"TEST_CANCELLED"};failure.get()?.let {throw it}}
    private inner class Consumer(private val kind:Reader,private val codecSurface:Surface?):Thread("p2-${kind.name.lowercase()}") {
        val ready=CountDownLatch(1)
        private val node=ProbeEglNode(root)
        private val painter=ProbeGlDraw()
        private var read:SharedInputFramePool.Read?=null
        private var fence=0L
        private var window:CameraProbeWindows.Target?=null
        @Volatile var stopRequested=false
        @Volatile var closed=false
        @Volatile var frames=0
        @Volatile var maxGapMs=0L
        private var lastAt=0L
        override fun run() {
            try {
                node.initialize(codecSurface)
                val shader=painter.program(if(kind==Reader.ENCODER)ProbeSyntheticGl.REPACK else DISPLAY)
                ready.countDown();var last=-1
                while(!stopRequested || kind==Reader.ENCODER && pool.pendingEncoder()>0) {
                    if(failure.get()!=null)break
                    if(kind!=Reader.ENCODER) {
                        val candidate=CameraProbeWindows.current(if(kind==Reader.DISPLAY_A)CameraProbeWindows.Kind.PAGE else CameraProbeWindows.Kind.MIRROR)
                        if(candidate!==window) {
                            node.detachWindow();window?.releaseReader();window=null
                            if(candidate!=null && candidate.take()) {window=candidate;node.attachWindow(candidate.surface);lastAt=0}
                        }
                        if(window==null) {Thread.sleep(5);continue}
                    }
                    val fresh=pool.acquire(kind,last)
                    if(fresh==null) {if(stopRequested)break;Thread.sleep(1);continue}
                    read=fresh;ProbeGlDraw.waitFence(fences[fresh.slot])
                    val texture=slots[fresh.slot].texture
                    if(kind==Reader.ENCODER) {
                        painter.repack(shader,layout,texture,0);ProbeGlDraw.checkGl()
                        val pts=(fresh.timestampNs-1_000_000_000L)/1000
                        node.swap(pts*1000);submitted(pts);lastEncodedPts=pts;encodedSubmissions++
                    } else {
                        drawDisplay(shader,texture,requireNotNull(window))
                        node.swap(fresh.timestampNs);window?.drawn(fresh.frame)
                    }
                    fence=ProbeGlDraw.fence();ProbeGlDraw.waitFence(fence);GLES30.glDeleteSync(fence);fence=0
                    pool.release(fresh,true);read=null;last=fresh.frame;frames++
                    val now=SystemClock.elapsedRealtime();if(lastAt>0)maxGapMs=maxOf(maxGapMs,now-lastAt);lastAt=now
                }
            } catch(t:Throwable) {failure.compareAndSet(null,t)}
            finally {
                ready.countDown()
                try {
                    node.finish()
                    read?.let {pool.release(it,true);read=null}
                    if(fence!=0L){GLES30.glDeleteSync(fence);fence=0}
                    if(node.usable)painter.close()
                    node.close();window?.releaseReader();window=null;closed=true
                } catch(t:Throwable){failure.compareAndSet(null,t)}
            }
        }
        private fun drawDisplay(shader:Int,texture:Int,target:CameraProbeWindows.Target) {
            val panel=presentation.panel(rear);val correction=settings.fisheyeCorrection
            val lane=FourLaneTextureLayout.windowForLane(1280,5140,rear)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glViewport(0,0,target.width,target.height);GLES20.glUseProgram(shader)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(shader,"source"),0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(shader,"viewSize"),target.width.toFloat(),target.height.toFloat())
            GLES20.glUniform4f(GLES20.glGetUniformLocation(shader,"lane"),lane.u,lane.v,lane.width,lane.height)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(shader,"viewport"),panel.viewport.zoom*correction.cropZoom,
                panel.viewport.centerX,panel.viewport.centerY,kotlin.math.tan(Math.toRadians(panel.fovDegrees.toDouble())/2).toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(shader,"center"),correction.centerX,correction.centerY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(shader,"rotation"),panel.rotation.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(shader,"mirrored"),if(panel.mirrored)1f else 0f)
            painter.quad(shader);ProbeGlDraw.checkGl()
        }
        fun evidence()=obj("reader" to kind.name,"frames" to frames,"maximumCompleteGapMs" to maxGapMs,"closed" to closed)
    }
    fun endProducer(cameraClosed:Boolean) {
        if(producerConfirmed)return
        check(cameraClosed) {"P2_CAMERA_PRODUCER_NOT_CLOSED"}
        ending=true;root.finish()
        writing?.let {pool.cancelWrite(it,true);writing=null}
        pool.stopPublishing();readers.forEach {it.stopRequested=true};readers.forEach {it.join(5_000)}
        check(readers.all {!it.isAlive && it.closed} && pool.outstanding()==0) {"P2_READERS_CLOSE_UNCONFIRMED"}
        producerConfirmed=true
    }
    fun close() {
        check(producerConfirmed)
        if(root.usable) {
            root.current();receiver.close(true)
            slots.indices.forEach {i->if(fences[i]!=0L){ProbeGlDraw.waitFence(fences[i]);GLES30.glDeleteSync(fences[i]);fences[i]=0};slots[i].close()}
            draw.close()
        }
        root.close();released=true
    }
    fun evidence()=obj("scope" to "REAL_SURROUND_OES_SHARED_INPUT","cameraSize" to listOf(1280,5140),
        "cameraInputSurfaceCount" to 1,"cameraInputReplacements" to 0,"inputFrames" to ledger.size,"encoderSubmissions" to encodedSubmissions,
        "maximumInputGapMs" to ledger.maximumGapNs/1e6,"sourceMatrixApplied" to true,"sourceTransform" to receiver.transformSnapshot(),
        "firstSourceTimestampNs" to firstSource.toString(),"lastSourceTimestampNs" to lastSource.toString(),
        "oesPolls" to receiver.polls,"oesNotifications" to receiver.notifications.get(),"pollingIndependentOfUi" to true,
        "inputPtsSha256" to com.dante.zeekrcapabilitylab.preflight.sha(pts().joinToString(",").toByteArray()),
        "poolSlots" to 4,"poolRgbaBytes" to 1280L*5140*4*4,"producerConfirmed" to producerConfirmed,"released" to released,
        "readers" to readers.map {it.evidence()},"failure" to failure.get()?.javaClass?.simpleName,
        "status" to if(failure.get()==null && ledger.size==encodedSubmissions && ledger.size>0 && ledger.maximumGapNs<100_000_000L &&
            readers.firstOrNull()?.maxGapMs?.let {it<100}==true && producerConfirmed && released)"PASS" else "FAIL")
    companion object {
        private const val DISPLAY="""
            precision highp float; uniform sampler2D source; uniform vec2 viewSize; uniform vec4 lane;
            uniform vec4 viewport; uniform vec2 center; uniform float rotation; uniform float mirrored;
            void main(){
                vec2 p=vec2(gl_FragCoord.x/viewSize.x,1.0-gl_FragCoord.y/viewSize.y)*2.0-1.0;
                p.y*=viewSize.y/viewSize.x;
                if(mirrored>0.5)p.x=-p.x;
                if(rotation>269.0)p=vec2(-p.y,p.x);else if(rotation>179.0)p=-p;else if(rotation>89.0)p=vec2(p.y,-p.x);
                p=(p/viewport.x+viewport.yz)*viewport.w;
                float r=length(p); vec2 uv=center+(r>0.00001?p/r:vec2(0.0))*(atan(r)/1.570796327)*0.5;
                uv=clamp(uv,0.0,1.0);vec2 inputUv=lane.xy+uv*lane.zw;
                gl_FragColor=vec4(texture2D(source,vec2(inputUv.x,1.0-inputUv.y)).rgb,1.0);
            }
        """
    }
}
