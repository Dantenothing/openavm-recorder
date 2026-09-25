package com.dante.zeekrcapabilitylab.preflight.remote

import android.content.*
import android.os.*
import android.os.storage.StorageManager
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.preflight.*
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticUpload
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.usbexport.UsbExportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.Executors
import androidx.core.content.ContextCompat

internal object LabMessages { const val REGISTER=1;const val STATUS=2;const val COMMAND=5;const val RESULT=6;const val UI=7;const val EVENT=8 }
internal object LabUi {
    private val mutable=MutableStateFlow(obj("message" to "远程会话未开启"))
    val state=mutable.asStateFlow()
    fun update(value:JsonObject) { mutable.value=value }
}
/** Only retained for old installer callbacks. Protocol 2 exposes no update command. */
internal object LabUpdateReservation {
    private var owner:String?=null
    fun active()=owner!=null
    fun claim():Boolean { if(owner!=null)return false;owner=CameraWorkCoordinator.claim("REMOTE_UPDATE");return owner!=null }
    fun release() {owner?.let(CameraWorkCoordinator::finish);owner=null}
}

/** Main process owns native tests; its actor never waits on network, USB or cleanup. */
internal class LabHarness(private val activity:PreflightActivity) {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val generation=UUID.randomUUID().toString()
    private val buildId=runCatching {activity.assets.open("preflight/build-evidence.json").bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonObject.string("buildId") }}.getOrDefault("UNAVAILABLE")
    private var remote:Messenger?=null
    private var visible=false
    private var jobId:String?=null
    private var simpleRun:String?=null
    private var simpleKind=""
    private var cancelAt=0L
    private var recipe:LabRecipeEngine?=null
    private val cancelCommands=ArrayList<String>()
    private var lastStage=""
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { msg ->
        val raw=msg.data.getString("json").orEmpty()
        if(msg.sendingUid==activity.applicationInfo.uid && raw.length<=65536) runCatching { Json.parseToJsonElement(raw).jsonObject }.onSuccess { value ->
            when(msg.what) {
                LabMessages.UI->{
                    LabUi.update(value)
                    if(visible && value.flag("armed") && !value.flag("running")) runCatching {
                        ContextCompat.startForegroundService(activity,Intent(activity,RemoteLabService::class.java).setAction(RemoteLabService.RESUME))
                    }
                }
                LabMessages.COMMAND->runCatching {execute(value)}.onFailure {
                    reply(value.string("id"),"REJECTED",verdict("REJECTED","NOT_STARTED","INCONCLUSIVE","NOT_APPLICABLE",code(it)))
                }
            }
        }; true
    })
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            remote=Messenger(binder);send(LabMessages.REGISTER,obj());main.removeCallbacks(pulse);pulse.run()
        }
        override fun onServiceDisconnected(name:ComponentName) { remote=null;LabUi.update(obj("message" to "远程连接进程已断开")) }
    }
    fun start() {
        visible=true
        activity.bindService(Intent(activity,RemoteLabService::class.java),connection,Context.BIND_AUTO_CREATE)
    }
    fun stop() {
        visible=false;cancelJob("HARNESS_HIDDEN")
        if(jobId!=null)finishJob("INTERRUPTED",verdict("ACCEPTED","INTERRUPTED","INCONCLUSIVE","UNKNOWN","HARNESS_HIDDEN_NO_REPLAY"))
        send(LabMessages.STATUS,status());main.removeCallbacks(pulse)
        runCatching { activity.unbindService(connection) };remote=null;io.shutdown()
    }
    private fun send(what:Int,value:JsonObject) {
        runCatching { remote?.send(Message.obtain(null,what).apply { replyTo=messenger;data=Bundle().apply { putString("json",value.toString()) } }) }
    }
    private fun reply(id:String,state:String,result:JsonObject) {
        if(LabPolicy.uuid.matches(id))send(LabMessages.RESULT,obj("id" to id,"state" to state,"result" to LabTransport.result(result)))
    }
    private fun event(type:String,data:JsonObject)=send(LabMessages.EVENT,obj("type" to type,"data" to data,"mainGeneration" to generation))
    private fun verdict(command:String,run:String,outcome:String,cleanup:String,reason:String)=obj(
        "commandState" to command,"runState" to run,"outcome" to outcome,"cleanupState" to cleanup,"reason" to reason)
    private fun finishJob(state:String,result:JsonObject) {
        val id=jobId ?: return
        reply(id,state,result)
        cancelCommands.forEach {reply(it,if(result.string("cleanupState")=="CONFIRMED")"SUCCEEDED" else "FAILED",
            JsonObject(result+obj("targetCommandId" to id,"reason" to "TARGET_TERMINATED")))}
        event("COMMAND_FINISHED",obj("commandId" to id,"state" to state,"outcome" to result["outcome"],"cleanupState" to result["cleanupState"]))
        DiagnosticUpload.releaseWorkflow(id);DiagnosticUpload.schedule(activity.applicationContext)
        jobId=null;recipe=null;simpleRun=null;cancelAt=0;cancelCommands.clear();lastStage=""
    }
    private fun online():Boolean {
        val ui=LabUi.state.value
        val age=SystemClock.elapsedRealtime()-ui.number("controlObservedElapsedMs")
        return ui.flag("armed") && ui.flag("networkConnected") && age in 0..10_000 && ui.string("buildId")==buildId
    }
    private val pulse=object:Runnable { override fun run() {
        if(visible) {
            runCatching {advance()}.onFailure { cancelJob("RUNNER_EXCEPTION");event("RUNNER_ERROR",obj("reason" to code(it))) }
            send(LabMessages.STATUS,status());main.postDelayed(this,500)
        }
    } }
    private fun nativeOrUsbBusy()=CameraRecordingService.isRunning() || !CanaryCameraInterlock.normalIdle() ||
        CaptureCleanupRuntime.pendingOwners.value>0 || UsbExportRepository.tasks.value.any {!it.terminal()}
    private fun view():LabRecipeEngine.View {
        val state=PreflightRuntime.state.value;val report=PreflightRuntime.report;val progress=PreflightRuntime.remoteProgress
        val run=if(state.active)progress.string("runId") else report?.string("runId").orEmpty()
        val cleanup=if(state.active)"PENDING" else report?.string("cleanup").orEmpty().ifEmpty {"UNKNOWN"}
        val reason=report?.string("reason").orEmpty()
        val tests=(report?.get("tests") as? JsonArray).orEmpty().map {it.jsonObject}
        val mode=report?.string("mode").orEmpty()
        val passed=when(mode) {
            "P2_CAMERA"->reason.isBlank() && tests.any {it.string("id")=="p2_camera" && it.string("status")=="PASS"}
            "P1_EXPERIMENT"->reason.isBlank() && tests.any {it.string("id")=="p1_experiment" && it.string("status")=="PASS"}
            "DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE"->reason.isBlank() && tests.any {it.string("id")=="diagnostic_evidence" && it.string("status")=="PASS"}
            else->LabPolicy.testPassed(obj("blocked" to state.blocked,"cleanup" to cleanup,"reason" to reason,"results" to state.results),
                if(mode=="P1_INPUT")"P1_INPUT" else if(mode=="P1_BASIC")"P1_BASIC" else "CAPABILITIES")
        }
        val outcome=if(state.active)"PENDING" else if(cleanup!="CONFIRMED")"INCONCLUSIVE" else if(reason=="USER_CANCELLED")"CANCELLED" else if(passed)"PASS" else "FAIL"
        val stages=if(progress.string("runId")==run)(progress["stages"] as? JsonArray).orEmpty().map {it.jsonPrimitive.content}.toSet() else emptySet()
        return LabRecipeEngine.View(run,state.active,cleanup,outcome,stages,obj("runId" to run,"active" to state.active,
            "phase" to state.phase,"cleanupState" to cleanup,"outcome" to outcome,"reason" to reason,"progress" to progress))
    }
    private fun advance() {
        val now=SystemClock.elapsedRealtime();val engine=recipe;val v=view()
        if(jobId!=null && v.runId==(engine?.childRun ?: simpleRun)) {
            val stage=PreflightRuntime.remoteProgress.string("stage")
            if(stage.isNotBlank() && stage!=lastStage) {lastStage=stage;event("TEST_STAGE",obj("commandId" to jobId,"runId" to v.runId,"stage" to stage))}
        }
        if(engine!=null) {
            engine.tick(now,online())
            if(engine.terminal) {
                val result=requireNotNull(engine.result)
                finishJob(if(result.string("outcome") in setOf("PASS","CANCELLED"))"SUCCEEDED" else "FAILED",result)
            }
        } else if(simpleRun!=null) {
            if(v.runId==simpleRun && !v.active) {
                val result=JsonObject(verdict("ACCEPTED","FINISHED",v.outcome,v.cleanup,PreflightRuntime.report?.string("reason").orEmpty())+
                    obj("runId" to simpleRun,"evidence" to PreflightRuntime.report?.get("diagnosticEvidence"),
                        "capabilities" to if(simpleKind=="CAPABILITIES")LabContract.capabilities() else null))
                finishJob(if(v.outcome in setOf("PASS","CANCELLED"))"SUCCEEDED" else "FAILED",result)
            } else if(cancelAt>0 && now-cancelAt>=40_000)
                finishJob("FAILED",verdict("ACCEPTED","INTERRUPTED","INCONCLUSIVE","UNKNOWN","CANCEL_CLEANUP_UNCONFIRMED"))
        }
    }
    private fun status():JsonObject {
        val state=PreflightRuntime.state.value;val v=view()
        return obj("visible" to visible,"versionCode" to BuildConfig.VERSION_CODE,"version" to BuildConfig.VERSION_NAME,
            "buildId" to buildId,"mainGeneration" to generation,"observedElapsedMs" to SystemClock.elapsedRealtime(),
            "cameraOwnerKind" to CameraWorkCoordinator.state.value.kind,
            "removableVolumesMounted" to runCatching {activity.getSystemService(StorageManager::class.java).storageVolumes
                .count {it.isRemovable && it.state==Environment.MEDIA_MOUNTED}}.getOrDefault(-1),
            "active" to (state.active || jobId!=null),"testActive" to state.active,"blocked" to state.blocked,"phase" to state.phase,
            "elapsedSeconds" to state.elapsedSeconds,"message" to state.message,
            "cameraBusy" to (nativeOrUsbBusy() || CameraWorkCoordinator.state.value.owner!=null),
            "pendingNativeOwners" to CaptureCleanupRuntime.pendingOwners.value,"commandId" to jobId,"recipe" to recipe?.snapshot(),
            "runId" to v.runId,"cleanup" to v.cleanup,"outcome" to v.outcome,"progress" to LabTransport.trimProgress(PreflightRuntime.remoteProgress),"results" to state.results)
    }
    private fun cancelJob(reason:String) {
        recipe?.cancel(SystemClock.elapsedRealtime(),reason)
        simpleRun?.let {PreflightService.stopExpected(it);if(cancelAt==0L)cancelAt=SystemClock.elapsedRealtime()}
    }
    private fun execute(c:JsonObject) {
        val id=c.string("id");val kind=c.string("kind")
        if(kind=="SESSION_END") {cancelJob("SESSION_ENDED");return}
        LabContract.validate(c,buildId)
        check(visible && LabUi.state.value.flag("armed")) {"HARNESS_NOT_VISIBLE_OR_ARMED"}
        check(SystemClock.elapsedRealtime()<c.number("executeBeforeElapsed")) {"COMMAND_HANDOFF_EXPIRED"}
        if(kind=="STOP") {
            require(c.getValue("payload").jsonObject.string("commandId")==jobId) {"CANCEL_TARGET_NOT_ACTIVE"}
            if(id !in cancelCommands)cancelCommands+=id
            cancelJob("USER_CANCELLED")
            reply(id,"RUNNING",verdict("ACCEPTED","CANCELLING","PENDING","PENDING","TARGET_CANCELLATION_REQUESTED"));return
        }
        if(kind=="REPORT") {
            io.execute { val result=runCatching { DiagnosticUpload.submit(activity.applicationContext,PreflightStore(activity).latestExport().first) }
                main.post { result.onSuccess {reply(id,"SUCCEEDED",obj("commandState" to "ACCEPTED","runState" to "FINISHED",
                    "outcome" to "PASS","cleanupState" to "NOT_APPLICABLE","reason" to "REPORT_QUEUED","runId" to it.run,"sha256" to it.hash))}
                    .onFailure {reply(id,"FAILED",verdict("ACCEPTED","FINISHED","INCONCLUSIVE","NOT_APPLICABLE",code(it)))} }
            };return
        }
        check(jobId==null && !PreflightRuntime.state.value.active && !nativeOrUsbBusy()) {"ENGINE_BUSY"}
        if(kind=="RUN_RECIPE") {
            check(!PreflightRuntime.state.value.blocked) {"CLEANUP_UNCONFIRMED"}
            val plan=LabRecipe.parse(c.getValue("payload").jsonObject)
            check(DiagnosticUpload.reserveWorkflow(id)) {"UPLOAD_BUSY"}
            jobId=id
            recipe=LabRecipeEngine(id,plan,SystemClock.elapsedRealtime(),object:LabRecipeEngine.Host {
                override fun start(experiment:LabExperiment):String? =
                    if(visible && !nativeOrUsbBusy() && PreflightService.startExperiment(activity,experiment,plan.hash,id))CameraWorkCoordinator.state.value.owner else null
                override fun view()=this@LabHarness.view()
                override fun cancel(runId:String)=PreflightService.stopExpected(runId)
                override fun event(type:String,data:JsonObject)=this@LabHarness.event(type,data)
            })
        } else {
            val started=when(kind) {
                "CAPABILITIES"->PreflightService.start(activity,false)
                "DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE"->PreflightService.startEvidence(activity,kind=="DIAGNOSTICS_RETIRE",c.getValue("payload").jsonObject)
                else->false
            }
            check(started) {"START_GATE_REJECTED"}
            jobId=id;simpleKind=kind;simpleRun=CameraWorkCoordinator.state.value.owner
        }
        reply(id,"RUNNING",JsonObject(verdict("ACCEPTED","RUNNING","PENDING","PENDING","TEST_STARTED")+obj("runId" to simpleRun)))
        advance();send(LabMessages.STATUS,status())
    }
    private fun code(t:Throwable)=t.message?.takeIf {Regex("[A-Z0-9_]{1,80}").matches(it)} ?: t.javaClass.simpleName
}
