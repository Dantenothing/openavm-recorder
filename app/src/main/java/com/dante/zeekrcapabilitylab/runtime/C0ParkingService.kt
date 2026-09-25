package com.dante.zeekrcapabilitylab.runtime

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.*
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.mirror.*
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.cloud.*
import com.dante.zeekrcapabilitylab.preflight.remote.LabWire
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.Executors

/** Explicit eight-minute C0 only. No boot restart, overlay, MP4 or product-control commands. */
class C0ParkingService:Service() {
    private val controlThread=HandlerThread("c0-control").apply {start()}
    private val control=Handler(controlThread.looper)
    private val io=Executors.newSingleThreadExecutor {Thread(it,"c0-network")}
    private var policy:C0Policy?=null
    private var camera:C0Camera?=null
    private var owner:String?=null
    private var config:RecorderConfig?=null
    private var wire:LabWire?=null
    private var wake:PowerManager.WakeLock?=null
    private lateinit var store:C0Store
    private val events=ArrayList<JsonObject>()
    private val attempts=ArrayList<JsonObject>()
    private var phase="PREPARING"
    private var reason:String?=null
    private var cleanup="CONFIRMED"
    private var startEpoch=0L
    private var captureAt=0L
    private var closeAt=0L
    private var stoppingAt=0L
    private var lastSave=0L
    private var nextPoll=0L
    private var networkBusy=false
    private var closed=false
    private var holdingCpu=false
    private var selectedRole="SURROUND"
    private var captureConditions=obj()
    private var captureSawForeground=false
    private var captureSawDisplayOn=false
    private var replies=0
    private var requestSequence=0
    @Volatile private var cancelled=false
    private val process=UUID.randomUUID().toString()
    override fun onCreate(){super.onCreate();instance=this;store=C0Store(this);CaptureCleanupRuntime.initialize(this);RecorderNotification.ensureChannel(this)}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){
            if(policy==null){stopSelf();return START_NOT_STICKY}
            val requestedRun=intent.getStringExtra("runId")
            control.post {if(policy?.cancelRun(requestedRun)==true){cancelled=true;end("USER_CANCELLED")}}
            return START_NOT_STICKY
        }
        if(policy!=null)return START_NOT_STICKY
        if(!BuildConfig.C0_PARKING_EXPERIMENT_ENABLED){stopSelf();return START_NOT_STICKY}
        if(intent?.action!=START || !ZeekrApp.isForeground.value || !consumeLaunch(intent.getStringExtra("ticket"))){stopSelf();return START_NOT_STICKY}
        val role=runCatching{RecordingSourceRole.valueOf(intent.getStringExtra("role") ?: "SURROUND")}.getOrNull()
        if(role !in setOf(RecordingSourceRole.SURROUND,RecordingSourceRole.CABIN)){stopSelf();return START_NOT_STICKY}
        selectedRole=role!!.name
        holdingCpu=intent.getBooleanExtra("holdCpu",false)
        policy=C0Policy(UUID.randomUUID().toString(),process,SystemClock.elapsedRealtime());startEpoch=System.currentTimeMillis()
        val stop=PendingIntent.getService(this,9701,Intent(this,C0ParkingService::class.java).setAction(STOP)
            .setData(Uri.parse("openavm-c0://stop/${policy!!.run}")).putExtra("runId",policy!!.run),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,9702,Intent(this,ParkingDiagnosticsActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,RecorderNotification.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OpenAVM 后台相机实验").setContentText("最多 8 分钟 · 不录像、不传画面 · 可随时停止")
            .setOngoing(true).setContentIntent(open).addAction(0,"停止实验",stop).build()
        try {
            if(Build.VERSION.SDK_INT>=30)startForeground(9700,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(9700,notification)
            if(holdingCpu)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OpenAVM:C0Parking")
                .apply {acquire(C0Policy.DURATION_MS)}
        }catch(t:Throwable){control.post{end("FOREGROUND_${t.javaClass.simpleName}")};return START_NOT_STICKY}
        control.post {
            event("SESSION_STARTED"); if(!save()){end("JOURNAL_UNWRITABLE");return@post}; tick()
            io.execute {
                val result=runCatching {
                    val connection=DiagnosticSettings(this).load()?.takeIf {it.paired} ?: error("PAIRING_REQUIRED")
                    val conf=ProductRecorderConfigFactory.create(this,role!!) ?: error("CAMERA_PROFILE_UNAVAILABLE")
                    val declared=getSystemService(CameraManager::class.java).getCameraCharacteristics(conf.cameraId)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)
                    check(declared?.any {it.width==conf.profile.size.width && it.height==conf.profile.size.height}==true){"C0_OES_SIZE_NOT_DECLARED"}
                    conf to LabWire(connection)
                }
                control.post {
                    if(!valid())return@post
                    result.onSuccess {config=it.first;wire=it.second;phase="WAITING_FOR_IDLE";event("PREPARED")}
                        .onFailure {end(safeCode(it))}
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun valid()=policy?.active(SystemClock.elapsedRealtime())==true && !cancelled && !closed
    private fun environment()=obj("foreground" to ZeekrApp.isForeground.value,
        "interactive" to getSystemService(PowerManager::class.java).isInteractive,
        "defaultDisplayState" to getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state,
        "defaultDisplayId" to Display.DEFAULT_DISPLAY,"uptimeMs" to SystemClock.uptimeMillis(),"elapsedMs" to SystemClock.elapsedRealtime())
    private fun event(name:String){if(events.size<120)events+=obj("event" to name,"atElapsedMs" to SystemClock.elapsedRealtime(),"environment" to environment())}
    private fun tick() {
        if(closed)return
        val now=SystemClock.elapsedRealtime(); val p=policy ?: return
        if((cancelled || !p.active(now)) && stoppingAt==0L)end(if(cancelled)"USER_CANCELLED" else "PERMIT_EXPIRED")
        if(stoppingAt>0){
            if(camera==null){finish();return}
            if(now-stoppingAt>25_000){cleanup="UNCONFIRMED";finish();return}
        } else when(phase) {
            "PREPARING","WAITING_FOR_IDLE" -> {
                if(now-p.started>15_000){end("PREPARATION_OR_OWNERSHIP_TIMEOUT")}
                else if(phase=="WAITING_FOR_IDLE" && !CameraRecordingService.isRunning() && CanaryCameraInterlock.normalIdle() &&
                    CaptureCleanupRuntime.pendingOwners.value==0 && !CameraWorkCoordinator.state.value.active) {
                    owner=CameraWorkCoordinator.claim("C0_PARKING")
                    if(owner!=null)openCapture("FOREGROUND_BASELINE")
                }
            }
            "CAPTURING" -> {
                captureSawForeground=captureSawForeground || ZeekrApp.isForeground.value
                captureSawDisplayOn=captureSawDisplayOn || getSystemService(PowerManager::class.java).isInteractive ||
                    getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state!=Display.STATE_OFF
                val c=camera
                if(c!=null && (c.frames>=C0Policy.MIN_BUFFERS || now-captureAt>=C0Policy.CAPTURE_MS)){
                    if(c.frames<C0Policy.MIN_BUFFERS)reason="INSUFFICIENT_FRESH_BUFFERS"
                    phase="CLOSING";closeAt=now;c.close();event("CLOSE_REQUESTED")
                }
            }
            "CLOSING" -> if(now-closeAt>20_000)end("CAMERA_RELEASE_UNCONFIRMED")
            "STANDBY" -> if(!networkBusy && now>=nextPoll)poll()
        }
        if(!closed){if(now-lastSave>=5_000)save();publish();control.postDelayed(::tick,500)}
    }
    private fun openCapture(trigger:String) {
        if(!valid() || camera!=null || cleanup!="CONFIRMED")return
        val c=config ?: return
        if(CameraRecordingService.isRunning() || !CanaryCameraInterlock.normalIdle()){end("OTHER_CAMERA_OWNER");return}
        captureAt=SystemClock.elapsedRealtime(); captureConditions=obj("trigger" to trigger,"openedAtElapsedMs" to captureAt,"environmentAtOpen" to environment())
        captureSawForeground=ZeekrApp.isForeground.value
        captureSawDisplayOn=getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state!=Display.STATE_OFF
        cleanup="PENDING";phase="CAPTURING"
        if(!save()){cleanup="CONFIRMED";end("JOURNAL_UNWRITABLE");return}
        lateinit var next:C0Camera
        next=C0Camera(this,c.cameraId,c.profile.size.width,c.profile.size.height,authorized={valid()},
            notify={code->control.post {if(camera===next && !closed){event(code);if(code!="CAPTURE_STARTED" && reason==null)reason=code}}},
            ended={safe->control.post {
                if(camera!==next)return@post
                if(!safe){cleanup="UNCONFIRMED";end("CAMERA_RELEASE_UNCONFIRMED");return@post}
                attempts+=JsonObject(captureConditions+obj("frames" to next.frames,"lastBufferTimestampNs" to next.lastTimestamp,
                    "lastFrameElapsedMs" to next.lastFrameAt,"closedAtElapsedMs" to SystemClock.elapsedRealtime(),"cleanup" to "CONFIRMED",
                    "foregroundObservedDuringCapture" to captureSawForeground,"displayOnObservedDuringCapture" to captureSawDisplayOn))
                camera=null;cleanup="CONFIRMED";event("CAMERA_RELEASE_CONFIRMED")
                if(closed){owner?.let(CameraWorkCoordinator::finish);save();publish();controlThread.quitSafely();return@post}
                if(stoppingAt>0 || reason!=null){if(stoppingAt==0L)end(reason!!);else finish()}
                else {policy?.closed(SystemClock.elapsedRealtime());phase="STANDBY";nextPoll=0;save()}
            }})
        camera=next;event("OPEN_REQUESTED");next.start()
    }
    private fun poll() {
        val api=wire ?: return; val p=policy ?: return
        val sent=SystemClock.elapsedRealtime(); val nonce=UUID.randomUUID().toString();val sequence=p.reopens+1
        if(sequence>2)return
        val eligible=p.ready(sent,ZeekrApp.isForeground.value,cleanup=="CONFIRMED" && camera==null)
        val body=obj("runId" to p.run,"processEpoch" to process,"nonce" to nonce,"step" to sequence,"eligible" to eligible,
            "remainingMs" to (p.deadline-sent).coerceAtLeast(0),"versionCode" to BuildConfig.VERSION_CODE)
        networkBusy=true;requestSequence++
        io.execute {
            val result=runCatching{api.post("/v1/c0/pulse",body)}
            control.post {
                networkBusy=false;nextPoll=SystemClock.elapsedRealtime()+10_000
                if(!valid() || phase!="STANDBY")return@post
                result.onSuccess {reply->
                    val fmt=reply["format"]?.jsonPrimitive?.content
                    val replyRun=reply["runId"]?.jsonPrimitive?.content.orEmpty()
                    val replyNonce=reply["nonce"]?.jsonPrimitive?.content.orEmpty()
                    val action=reply["action"]?.jsonPrimitive?.content
                    if(fmt!="OPENAVM_C0_DIRECTIVE" || replyRun!=p.run || replyNonce!=nonce || reply["processEpoch"]!=JsonPrimitive(process) ||
                        reply["ttlMs"]!=JsonPrimitive(C0Policy.REPLY_FRESH_MS) || action !in setOf("WAIT","PROBE")){
                        event("CLOUD_IDENTITY_REJECTED");return@onSuccess}
                    replies++
                    if(action=="PROBE" && p.accept(SystemClock.elapsedRealtime(),sent,process,replyRun,nonce,replyNonce,
                        reply["step"]?.jsonPrimitive?.intOrNull ?: -1,ZeekrApp.isForeground.value,camera==null && cleanup=="CONFIRMED")) {
                        event("FRESH_CLOUD_DIRECTIVE_ACCEPTED");openCapture("FIXED_CLOUD_RECIPE")
                    }
                }.onFailure {event("NETWORK_${safeCode(it)}")}
            }
        }
    }
    private fun end(code:String) {
        if(stoppingAt>0 || closed)return
        policy?.revoke();cancelled=true;stoppingAt=SystemClock.elapsedRealtime();phase="ENDING"
        if(reason==null)reason=code;event("AUTHORITY_REVOKED");wire?.cancelAll()
        wake?.takeIf {it.isHeld}?.release();wake=null
        camera?.close()
        if(camera==null)finish() else {save();publish()}
    }
    private fun report():JsonObject {
        val now=SystemClock.elapsedRealtime(); val p=policy
        val reopens=attempts.filter {it["trigger"]==JsonPrimitive("FIXED_CLOUD_RECIPE")}
        val outcome=C0Verdict.classify(closed,reason,cleanup=="CONFIRMED",reopens.map {
            C0CaptureEvidence(it["frames"]?.jsonPrimitive?.intOrNull ?: 0,
                it["foregroundObservedDuringCapture"]!=JsonPrimitive(false),it["displayOnObservedDuringCapture"]!=JsonPrimitive(false))
        })
        return obj("schemaVersion" to 1,"format" to "OPENAVM_PREFLIGHT","exampleOnly" to false,"productionSwitchAllowed" to false,
            "runId" to (p?.run ?: UUID.randomUUID().toString()),"version" to BuildConfig.VERSION_NAME,"versionCode" to BuildConfig.VERSION_CODE,
            "startEpochMs" to startEpoch,"phase" to phase,"mode" to "C0_PARKING","processEpoch" to process,"bootCount" to store.boot,
            "source" to selectedRole,"requestedWidth" to config?.profile?.size?.width,"requestedHeight" to config?.profile?.size?.height,
            "permitRemainingMs" to if(valid()) (p!!.deadline-now).coerceAtLeast(0) else 0,"cleanup" to cleanup,"reason" to reason,
            "tests" to listOf(obj("name" to "c0_background_reopen","outcome" to outcome)),"attempts" to attempts,
            "cloudReplies" to replies,"cloudRequests" to requestSequence,"holdCpuSelected" to holdingCpu,"wakeLockHeld" to (wake?.isHeld==true),
            "events" to events,"triggerMode" to "PREAUTHORIZED_FIXED_CLOUD_RECIPE_NOT_OPERATOR_CONTROL",
            "unverified" to listOf("PHYSICAL_LOCK_STATE","WHOLE_VEHICLE_SLEEP","COLD_WAKE","WEBRTC","POWER_CONSUMPTION","REMOTE_OPERATOR_CONTROL"),
            "videoIncluded" to false,"filesWritten" to 0,"currentEnvironment" to environment())
    }
    private fun save():Boolean {
        lastSave=SystemClock.elapsedRealtime()
        return runCatching{store.write(report());true}.getOrElse{reason="JOURNAL_UNWRITABLE";false}
    }
    private fun publish(){ mutable.value=C0Ui(!closed,phase,policy?.let{((it.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)/1000).toInt()} ?: 0,reason,cleanup,attempts.size,replies) }
    private fun finish() {
        if(closed)return
        closed=true;phase="FINISHED";policy?.revoke();wire?.cancelAll();wake?.takeIf{it.isHeld}?.release();wake=null
        if(cleanup=="CONFIRMED" && camera==null)owner?.let(CameraWorkCoordinator::finish)
        else owner?.let{CameraWorkCoordinator.publish(it,"相机释放尚未确认，请复制报告后重启车机。","C0_CLEANUP_UNCONFIRMED")}
        save();publish()
        DiagnosticUpload.finished(this,report().toString().toByteArray())
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {
        instance=null;cancelled=true;wire?.cancelAll();wake?.takeIf{it.isHeld}?.release();wake=null
        control.post{if(policy!=null){if(!closed)end("SERVICE_DESTROYED");camera?.close()}}
        io.shutdown()
        // Keep callbacks and retained owners reachable if native cleanup is still unknown.
        if(camera==null)controlThread.quitSafely()
        super.onDestroy()
    }
    companion object {
        private const val START="openavm.c0.START";private const val STOP="openavm.c0.STOP"
        @Volatile private var instance:C0ParkingService?=null
        private var ticket:String?=null
        internal val mutable=MutableStateFlow(C0Ui())
        internal val state=mutable.asStateFlow()
        internal fun ownsRun(run:String)=instance?.let{it.policy?.run==run && !it.closed}==true
        @Synchronized private fun consumeLaunch(value:String?):Boolean {if(value==null || value!=ticket)return false;ticket=null;return true}
        internal fun start(context:Context,role:RecordingSourceRole,holdCpu:Boolean):String? {
            if(!BuildConfig.C0_PARKING_EXPERIMENT_ENABLED)return "本版用于自然离车复测，后台相机实验暂未开放"
            if(instance!=null || mutable.value.active || ticket!=null)return "实验正在运行或启动中"
            if(!ZeekrApp.isForeground.value)return "请在本页开始实验"
            if(CameraRecordingService.isRunning())return "请先停止录像"
            if(ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return "请先在首页允许相机权限"
            if(CameraWorkCoordinator.state.value.active && CameraWorkCoordinator.state.value.kind!="PREVIEW")return "其他相机工作尚未结束"
            return runCatching {
                check(DiagnosticSettings(context).load()?.paired==true){"请先在重构体检页面配对诊断接收器"}
                com.dante.zeekrcapabilitylab.diagnostic.AwayJournal.endForExperiment()
                FloatingMirrorService.close();StandaloneMirrorService.stop()
                val next=UUID.randomUUID().toString();ticket=next
                ContextCompat.startForegroundService(context,Intent(context,C0ParkingService::class.java).setAction(START)
                    .putExtra("ticket",next).putExtra("role",role.name).putExtra("holdCpu",holdCpu))
                null
            }.getOrElse {ticket=null;it.message?.take(100) ?: "实验启动失败"}
        }
        internal fun stop(){instance?.let{it.cancelled=true;it.control.post{it.end("USER_CANCELLED")}}}
        private fun safeCode(t:Throwable)=t.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,60}"))} ?: t.javaClass.simpleName
    }
}
internal data class C0Ui(val active:Boolean=false,val phase:String="READY",val remainingSeconds:Int=0,val reason:String?=null,
                        val cleanup:String="CONFIRMED",val captures:Int=0,val replies:Int=0)
