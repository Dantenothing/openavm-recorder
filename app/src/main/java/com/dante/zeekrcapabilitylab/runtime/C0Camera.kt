package com.dante.zeekrcapabilitylab.runtime

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeEglNode
import com.dante.zeekrcapabilitylab.preflight.continuous.SharedOesFrameInput
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.service.recorder.*

/** Bounded offscreen buffer consumer. No encoder, MP4, UI surface or camera retry. */
internal class C0Camera(private val context:Context, private val cameraId:String, private val width:Int, private val height:Int,
    private val authorized:()->Boolean, private val notify:(String)->Unit, private val ended:(Boolean)->Unit) {
    private val cameraThread=HandlerThread("c0-camera").apply {start()}
    private val glThread=HandlerThread("c0-buffer-drain").apply {start()}
    private val native=Handler(cameraThread.looper)
    private val gl=Handler(glThread.looper)
    private val root=ProbeEglNode()
    private val input=SharedOesFrameInput(width,height)
    private var device:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var tx:CaptureCloseTransaction?=null
    private val hold=Any()
    @Volatile private var initializing=true
    @Volatile private var opening=false
    @Volatile private var closing=false
    @Volatile private var inputInitialized=false
    @Volatile var frames=0; private set
    @Volatile var lastTimestamp=0L; private set
    @Volatile var lastFrameAt=0L; private set
    @Volatile var cleanupConfirmed=false; private set
    private var previousStamp=0L
    fun start() {
        gl.post {
            try { root.initialize(); inputInitialized=true; input.initialize() }
            catch(t:Throwable) { closing=true; notify("GL_START_${t.javaClass.simpleName}") }
            finally { initializing=false }
            native.post {
                if(closing || !authorized()) {closing=true;beginClose();return@post}
                opening=true
                try {
                    CanaryCameraOpenAdapter.open(context.getSystemService(CameraManager::class.java),cameraId,
                        object:CameraDevice.StateCallback() {
                            override fun onOpened(camera:CameraDevice) {
                                opening=false;device=camera
                                if(closing || !authorized()){closing=true;beginClose();return}
                                try { camera.createCaptureSession(listOf(input.surface),object:CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(value:CameraCaptureSession) {
                                        session=value
                                        if(closing || !authorized()){close();return}
                                        try {
                                            val req=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {addTarget(input.surface)}.build()
                                            value.setRepeatingRequest(req,null,native);gl.post(::drain)
                                            notify("CAPTURE_STARTED")
                                        }catch(t:Throwable){fail("REQUEST_${t.javaClass.simpleName}")}
                                    }
                                    override fun onConfigureFailed(value:CameraCaptureSession){session=value;fail("CONFIGURE_FAILED")}
                                    override fun onClosed(value:CameraCaptureSession){tx?.sessionClosed()}
                                },native) }catch(t:Throwable){fail("CONFIGURE_${t.javaClass.simpleName}")}
                            }
                            override fun onDisconnected(camera:CameraDevice){opening=false;device=camera;fail("CAMERA_DISCONNECTED")}
                            override fun onError(camera:CameraDevice,error:Int){opening=false;device=camera;fail("CAMERA_ERROR_$error")}
                            override fun onClosed(camera:CameraDevice){tx?.deviceClosed()}
                        },native)
                } catch(t:Throwable){opening=false;fail("OPEN_${t.javaClass.simpleName}")}
            }
        }
    }
    private fun drain() {
        if(closing)return
        if(!authorized()){close();return}
        try {
            val stamp=input.acquireLatest()
            if(stamp>previousStamp && stamp>0){previousStamp=stamp;lastTimestamp=stamp;frames++;lastFrameAt=SystemClock.elapsedRealtime()}
        }catch(t:Throwable){fail("BUFFER_${t.javaClass.simpleName}");return}
        if(!closing)gl.postDelayed(::drain,30)
    }
    private fun fail(code:String){notify(code);close()}
    fun close() {closing=true;native.post(::beginClose)}
    private fun beginClose() {
        if(tx!=null || cleanupConfirmed || initializing || opening)return // A timeout does not acknowledge a native open.
        val camera=device; val capture=session
        val transaction=CaptureCloseTransaction(CaptureCleanupRuntime.control,HandlerCloseDispatcher(native),HandlerCloseDispatcher(gl),
            object:CaptureCloseResources {
                override fun stopRepeating(){capture?.stopRepeating()}
                override fun abortCaptures(){capture?.abortCaptures()}
                override fun closeSession(){capture?.close()}
                override fun closeDevice(){camera?.close()}
                override fun stopRecorder()=Unit
                override fun resetRecorder()=Unit
                override fun releaseRecorder()=Unit
                override fun closeOutput(lost:Boolean){
                    if(root.usable)root.finish()
                    if(inputInitialized)input.close(true)
                    root.close()
                }
            },hasSession=capture!=null,hasDevice=camera!=null,wasRecording=false,sequences=emptySet(),terminal=true,lost=false,
            preferDeviceClose=true,trace={step,detail->CaptureCleanupRuntime.trace("c0-${System.identityHashCode(this)}",step,detail)},
            unconfirmed={notify("CLEANUP_UNCONFIRMED_$it");ended(false)},completed={result->
                if(result.safeToContinue){cleanupConfirmed=true;CaptureCleanupRuntime.settled(hold,true)
                    ended(true);cameraThread.quitSafely();glThread.quitSafely()}
            })
        tx=transaction;CaptureCleanupRuntime.retain(hold,cameraId,camera,transaction);transaction.begin()
    }
}
