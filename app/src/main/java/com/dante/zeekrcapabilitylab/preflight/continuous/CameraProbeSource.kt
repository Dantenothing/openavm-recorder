package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exactly one device/session/request for an entire diagnostic run, including all file cuts. */
internal class CameraProbeSource(private val context: Context, private val cameraId: String) {
    private val thread=HandlerThread("p2-camera-owner")
    private lateinit var handler: Handler
    private val ready=CountDownLatch(1)
    private val closed=CountDownLatch(1)
    private val failure=AtomicReference<Throwable?>()
    @Volatile private var device: CameraDevice?=null
    @Volatile private var session: CameraCaptureSession?=null
    @Volatile private var stopping=false
    @Volatile private var started=false
    @Volatile private var openSubmitted=false
    @Volatile var cleanupConfirmed=false; private set
    @Volatile private var opens=0
    @Volatile private var sessions=0
    @Volatile private var requests=0
    private val samples=ArrayList<CameraCaptureSample>()
    private val failedFrames=ArrayList<Long>()
    private val lostFrames=ArrayList<Long>()
    private var captureFailures=0
    private var buffersLost=0
    private var shutdownFailures=0
    private var shutdownBuffersLost=0
    fun start(surface: Surface) {
        val manager=context.getSystemService(CameraManager::class.java)
        val sizes=manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        check(sizes.any { it.width==1280 && it.height==5140 }) { "P2_OES_SIZE_NOT_DECLARED" }
        thread.start();handler=Handler(thread.looper);started=true
        handler.post {
            try {
                openSubmitted=true
                CanaryCameraOpenAdapter.open(manager,cameraId,object:CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device=camera;opens++
                        if(stopping) { camera.close();return }
                        try {
                            camera.createCaptureSession(listOf(surface),object:CameraCaptureSession.StateCallback() {
                                override fun onConfigured(value: CameraCaptureSession) {
                                    session=value;sessions++
                                    if(stopping) { value.close();camera.close();return }
                                    try {
                                        val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }.build()
                                        value.setRepeatingRequest(request,object:CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult) {
                                                val ts=result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                                                synchronized(samples) {
                                                    if(samples.size<25_000)samples+=CameraCaptureSample(result.frameNumber,ts)
                                                    else failure.compareAndSet(null,IllegalStateException("P2_CAPTURE_EVIDENCE_BUDGET"))
                                                }
                                            }
                                            override fun onCaptureFailed(s:CameraCaptureSession,r:CaptureRequest,f:CaptureFailure) {
                                                synchronized(samples) {
                                                    if(stopping)shutdownFailures++ else captureFailures++
                                                    if(failedFrames.size<25_000)failedFrames+=f.frameNumber
                                                    else failure.compareAndSet(null,IllegalStateException("P2_CAPTURE_EVIDENCE_BUDGET"))
                                                }
                                            }
                                            override fun onCaptureBufferLost(s:CameraCaptureSession,r:CaptureRequest,target:Surface,frame:Long) {
                                                synchronized(samples) {
                                                    if(stopping)shutdownBuffersLost++ else buffersLost++
                                                    if(lostFrames.size<25_000)lostFrames+=frame
                                                    else failure.compareAndSet(null,IllegalStateException("P2_CAPTURE_EVIDENCE_BUDGET"))
                                                }
                                            }
                                        },handler)
                                        requests++;ready.countDown()
                                    } catch(t:Throwable) { fail(t) }
                                }
                                override fun onConfigureFailed(value:CameraCaptureSession) { session=value;fail(IllegalStateException("P2_SESSION_CONFIGURE_FAILED")) }
                            },handler)
                        } catch(t:Throwable) { fail(t) }
                    }
                    override fun onDisconnected(camera:CameraDevice) { device=camera;fail(IllegalStateException("P2_CAMERA_DISCONNECTED"));camera.close() }
                    override fun onError(camera:CameraDevice,error:Int) { device=camera;fail(IllegalStateException("P2_CAMERA_ERROR_$error"));camera.close() }
                    override fun onClosed(camera:CameraDevice) { if(device===camera)device=null;closed.countDown() }
                },handler)
            } catch(t:Throwable) { openSubmitted=false;fail(t);closed.countDown() }
        }
        check(ready.await(10,TimeUnit.SECONDS)) { "P2_CAMERA_START_TIMEOUT" };checkHealthy()
    }
    private fun fail(t:Throwable) { failure.compareAndSet(null,t);ready.countDown() }
    fun checkHealthy() { failure.get()?.let { throw it } }
    fun close() {
        stopping=true
        if(!started) { cleanupConfirmed=true;return }
        handler.post {
            // Device onClosed is the producer-release acknowledgement. Session.close alone is insufficient.
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }.onFailure(::fail)
            if(!openSubmitted)closed.countDown()
        }
        check(closed.await(8,TimeUnit.SECONDS)) { "P2_CAMERA_CLOSE_UNCONFIRMED" }
        thread.quitSafely();thread.join(2_000)
        check(!thread.isAlive) { "P2_CAMERA_THREAD_UNCONFIRMED" }
        session=null;cleanupConfirmed=true
    }
    fun evidence(acquiredNs:List<Long> = emptyList())=synchronized(samples) {
        val interval=CameraCaptureInterval.inspect(samples,acquiredNs,failedFrames,lostFrames)
        obj("cameraId" to cameraId,"deviceOpens" to opens,"captureSessions" to sessions,"repeatingRequests" to requests,
            "captureCallbacks" to samples.size,"failureCallbacksBeforeStopRequest" to captureFailures,"lostBufferCallbacksBeforeStopRequest" to buffersLost,
            "failureCallbacksAfterStopRequest" to shutdownFailures,"lostBufferCallbacksAfterStopRequest" to shutdownBuffersLost,
            "failureClassification" to "NATIVE_FRAME_RANGE_OF_ACQUIRED_SOURCE_TIMESTAMPS",
            "acquiredNativeFrameRangeConfirmed" to interval.confirmed,"firstAcquiredNativeFrame" to interval.firstFrame,
            "lastAcquiredNativeFrame" to interval.lastFrame,"failuresWithinAcquiredRange" to interval.failures,
            "lostBuffersWithinAcquiredRange" to interval.lostBuffers,"unattributableFailureCallbacks" to interval.unattributableFailures,
            "maximumSensorGapMsWithinAcquiredRange" to interval.maximumSensorGapMs,
            "callbacksWithinAcquiredRange" to interval.callbacks,"sensorFramesNotAcquired" to interval.sensorFramesNotAcquired,
            "acquiredTimestampsWithoutResult" to interval.acquiredWithoutResult,
            "identityDuringCuts" to if(opens==1 && sessions==1 && requests==1)"UNCHANGED" else "NOT_CONFIRMED",
            "cleanupConfirmed" to cleanupConfirmed)
    }
}
