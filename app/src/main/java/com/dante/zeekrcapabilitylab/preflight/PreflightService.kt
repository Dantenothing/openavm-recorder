package com.dante.zeekrcapabilitylab.preflight

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.mirror.*
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeBasicRunner
import com.dante.zeekrcapabilitylab.preflight.continuous.CameraProbeRunner
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticUpload
import com.dante.zeekrcapabilitylab.preflight.remote.LabExperiment
import com.dante.zeekrcapabilitylab.preflight.remote.payloadHash
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeRetainedDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

data class PreflightUi(val active: Boolean = false, val blocked: Boolean = false, val phase: String = "READY",
    val elapsedSeconds: Int = 0, val message: String = "", val reportReady: Boolean = false, val results: List<String> = emptyList())
internal object PreflightRuntime {
    val mutable = MutableStateFlow(PreflightUi())
    val state = mutable.asStateFlow()
    @Volatile var report: JsonObject? = null
    @Volatile var remoteProgress:JsonObject = obj()
    @Volatile var remoteCancelRun:String? = null
    // Never let uncertain native ownership disappear through garbage collection.
    val retained = mutableListOf<Any>()
}

class PreflightService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val controlThread = HandlerThread("preflight-watchdog")
    private lateinit var control: Handler
    private val worker = Executors.newSingleThreadExecutor { Thread(it,"preflight-inspect") }
    private lateinit var store: PreflightStore
    private var runId: String? = null
    private var mode = "CAPABILITIES_ONLY"
    private var experiment:LabExperiment?=null
    private var remotePayload:JsonObject=obj()
    private var phase = "WAITING_FOR_IDLE"
    private var reason: String? = null
    private var startAt = 0L
    private var startEpoch = 0L
    private var baselineAt = 0L
    private var stopAt = 0L
    private var stageAt = 0L
    private var deadline = 0L
    private var activeTest: String? = null
    private var nativeWorkerActive = false
    private var gate = PreflightGate()
    @Volatile private var stopping = false
    @Volatile private var finished = false
    @Volatile private var recorder: RecorderSession? = null
    @Volatile private var recorderState = RecorderState()
    @Volatile private var recorderReleased = true
    @Volatile private var mirror: MirrorPreviewController? = null
    @Volatile private var mirrorReleased = true
    @Volatile private var mirrorEverCreated = false
    private var config: RecorderConfig? = null
    private var usb: PreflightUsbScope? = null
    private var baselineToken = 0L
    private var baselineStopping = false
    private var journalHealthy = true
    private var ownsBarrier = false
    private var lastSaveAt = 0L
    private var recordingSince = 0L
    private var sampleTicks = 0
    private var maxInputAge = 0L
    private var maxDisplayAge = 0L
    private var freshSamples = 0
    private val progressClock=PreflightProgressClock()
    private val queue = ArrayBlockingQueue<JsonObject>(256)
    private val droppedEvents = java.util.concurrent.atomic.AtomicInteger()
    private val resourcesPending = java.util.concurrent.atomic.AtomicBoolean()
    private val events = ArrayDeque<JsonObject>()
    private val samples = ArrayDeque<JsonObject>()
    private val tests = linkedMapOf<String,JsonObject>()
    private val details = linkedMapOf<String,JsonElement>()
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) control.post { stopRun("SCREEN_OFF") } }
    }
    override fun onCreate() {
        super.onCreate(); instance = this; store = PreflightStore(this)
        controlThread.start(); control = Handler(controlThread.looper)
        CaptureCleanupRuntime.initialize(this); RecorderNotification.ensureChannel(this)
        ContextCompat.registerReceiver(this,screen,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { control.post { stopRun("USER_CANCELLED") }; return START_NOT_STICKY }
        val owner = intent?.getStringExtra("owner")
        if (runId != null) return START_NOT_STICKY
        if (owner == null || CameraWorkCoordinator.state.value.owner != owner) { stopSelf(); return START_NOT_STICKY }
        runId = owner; mode = intent.getStringExtra("mode") ?: "CAPABILITIES_ONLY"
        remotePayload=runCatching { Json.parseToJsonElement(intent.getStringExtra("remotePayload") ?: "{}").jsonObject }.getOrDefault(obj())
        experiment=runCatching {(remotePayload["experiment"] as? JsonObject)?.let(LabExperiment::parse)}.getOrNull()
        if(mode in setOf("P1_EXPERIMENT","P2_CAMERA") && experiment==null) {
            recordStartFailure(this,owner,mode,"EXPERIMENT_INVALID")
            CameraWorkCoordinator.finish(owner);stopSelf();return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this,411,Intent(this,PreflightService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this,412,Intent(this,PreflightActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this,RecorderNotification.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OpenAVM Preflight").setContentText(if(mode=="P2_CAMERA") "真实相机连续录像与后视镜测试" else if(mode.startsWith("P1_")) "连续分段输入诊断 · 不打开相机" else "硬件能力 + 当前链路基线体检 / P0")
            .setOngoing(true).setContentIntent(open).addAction(0,"停止 / Stop",stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(0x5E60,notification,
                if (mode in setOf("FULL_SAFE","P2_CAMERA") && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(0x5E60,notification)
        } catch (_: Throwable) {
            recordStartFailure(this,owner,mode,"FOREGROUND_SERVICE_START_FAILED")
            CameraWorkCoordinator.finish(owner); stopSelf(); return START_NOT_STICKY
        }
        control.post {
            startAt=SystemClock.elapsedRealtime(); startEpoch=System.currentTimeMillis(); deadline=startAt+15_000
            PreflightPlan.cases.forEach { tests[it]=PreflightReport.test(it,"NOT_RUN") }
            deferredCases().forEach { tests[it]=PreflightReport.test(it,"NOT_IMPLEMENTED","OUTSIDE_DELIVERED_COVERAGE") }
            if(mode=="P1_EXPERIMENT") tests["p1_experiment"]=PreflightReport.test("p1_experiment","NOT_RUN")
            if(mode=="P2_CAMERA") tests["p2_camera"]=PreflightReport.test("p2_camera","NOT_RUN")
            if(mode=="P1_BASIC") tests["p1_basic"]=PreflightReport.test("p1_basic","NOT_RUN")
            if(mode=="P1_INPUT") listOf("p1_input","oes_input","shared_pool_pressure","encoded_load").forEach { tests[it]=PreflightReport.test(it,"NOT_RUN") }
            try {
                details["previousRun"] = store.read()?.let { obj("runId" to it["runId"],"phase" to it["phase"],"cleanup" to it["cleanup"],"reason" to it["reason"]) } ?: JsonNull
                details["priorJournal"] = store.read("active.json") ?: JsonNull
                store.archivePrevious()
                val block = if (mode !in setOf("CAPABILITIES_ONLY","DIAGNOSTICS_LIST")) store.blockingReason() else null
                if (block != null) { reason=block; finishRun(true); return@post }
                save()
                if(PreflightRuntime.remoteCancelRun==owner)stopRun("USER_CANCELLED")
                tick()
            } catch (_: Throwable) { journalHealthy=false; reason="JOURNAL_UNWRITABLE"; finishRun(true) }
        }
        return START_NOT_STICKY
    }
    private fun emit(event: JsonObject) {
        val timestamped = JsonObject(event + ("atElapsedMs" to JsonPrimitive(SystemClock.elapsedRealtime())))
        if (!queue.offer(timestamped)) droppedEvents.incrementAndGet()
    }
    /** Always on the independent coordinator, never on camera/GL/USB workers. */
    private fun tick() {
        try { tickOnce() } catch (failure: Exception) {
            if(!finished) {
                details["coordinatorFailure"]=obj("type" to failure.javaClass.simpleName)
                stopRun("COORDINATOR_EXCEPTION")
                control.postDelayed(::tick,1_000)
            }
        }
    }
    private fun tickOnce() {
        if (finished) return
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val event = queue.poll() ?: break; events.addLast(event); if (events.size>600) events.removeFirst()
            val stage=event["stage"]?.jsonPrimitive?.contentOrNull
            if(stage!=null) {
                val seen=(PreflightRuntime.remoteProgress["stages"] as? JsonArray).orEmpty().map {it.jsonPrimitive.content}.toMutableSet()
                seen+=stage
                PreflightRuntime.remoteProgress=obj("runId" to runId,"stage" to stage,"stages" to seen.toList().takeLast(64),
                    "observedAtElapsedMs" to now,"statistics" to event["statistics"])
            }
        }
        if (phase == "WAITING_FOR_IDLE") {
            if (CameraRecordingService.isRunning()) { stopRun("USER_RECORDING_ACTIVE") }
            else if (CanaryCameraInterlock.normalIdle() && CaptureCleanupRuntime.pendingOwners.value==0) beginInventory()
            else if (now>deadline) stopRun("CAMERA_OWNERSHIP_NOT_IDLE")
        } else if (nativeWorkerActive && now>deadline && !stopping) {
            stopRun("${activeTest?.uppercase()}_WATCHDOG_TIMEOUT")
        }
        if (phase == "BASELINE" && !stopping) {
            if (now>deadline) stopRun("BASELINE_START_TIMEOUT")
            else observe(now)
        }
        if (stopping && baselineStopping && !nativeWorkerActive) {
            main.post { mirrorReleased = mirror?.cleanupConfirmed() ?: true }
            if (recorderReleased && mirrorReleased && CanaryCameraInterlock.normalIdle() && CaptureCleanupRuntime.pendingOwners.value==0) finishBaseline()
            else if (now-stopAt>25_000) { gate.finish(baselineToken,false); finishRun(false) }
        } else if (stopping && nativeWorkerActive && now-stopAt>25_000) {
            tests[activeTest.orEmpty()] = PreflightReport.test(activeTest.orEmpty(),"INCOMPLETE",reason,cleanup="UNCONFIRMED",start=stageAt,end=now)
            finishRun(false)
        }
        if (!finished) {
            if (now-lastSaveAt>=5_000) saveOrStop()
            publish(); control.postDelayed(::tick,1_000)
        }
    }
    private fun beginInventory() {
        perform("public_capabilities",45_000,false,{
            obj("device" to PreflightInventory.device(this),"cameras" to PreflightInventory.cameras(this),
                "codecs" to PreflightInventory.codecs(),"build" to PreflightInventory.build(this),"historicalExits" to PreflightInventory.exits(this))
        }) { value ->
            details["device"]=value["device"]!!; details["cameraCapabilities"]=value["cameras"]!!
            details["codecCapabilities"]=value["codecs"]!!; details["build"]=value["build"]!!; details["historicalExits"]=value["historicalExits"]!!
            tests["public_capabilities"]=PreflightReport.test("public_capabilities","PASS",evidence="DECLARED",data=obj("cameraOpened" to false,"codecCreated" to false),start=stageAt,end=SystemClock.elapsedRealtime())
            tests["build"]=PreflightReport.test("build",if (value["build"]?.jsonObject?.containsKey("buildId")==true) "PASS" else "UNAVAILABLE",evidence="BUILD_ARTIFACT")
            if(mode in setOf("DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE")) {
                if(Build.VERSION.SDK_INT>=30)beginEvidence() else stopRun("DIAGNOSTICS_REQUIRE_ANDROID_11")
                return@perform
            }
            if (mode == "CAPABILITIES_ONLY") {
                listOf("egl_small","usb_fd","closed_file","baseline").forEach { tests[it]=PreflightReport.test(it,"SKIP","CAPABILITIES_ONLY") }
                finishRun(true)
            } else perform("egl_small",15_000,true,{ PreflightGl.inspect() }) { gl ->
                tests["egl_small"]=PreflightReport.test("egl_small","PASS",evidence="CONFIGURED",data=gl,start=stageAt,end=SystemClock.elapsedRealtime())
                beginUsb()
            }
        }
    }
    private fun beginUsb() {
        perform("usb_fd",30_000,true,{
            check(UsbExportRepository.tasks.value.none { !it.terminal() }) { "USB_EXPORT_ACTIVE" }
            val target = UsbExportVolumeResolver.mountedTargets(this).singleOrNull() ?: error("ONE_USB_REQUIRED")
            check(PreflightPlan.budgetReason(0,0,8*1024*1024,target.freeBytes)==null) { "USB_FREE_SPACE_LOW" }
            if(mode=="FULL_SAFE") {
                val cfg = ProductRecorderConfigFactory.create(this,RecordingSourceRole.SURROUND) ?: error("SURROUND_SOURCE_UNRESOLVED")
                config=cfg.copy(mirrorPreviewEnabled=true,storagePreference=RecordingStoragePreference.USB_PREFERRED)
            }
            usb=PreflightUsbScope(this,target,requireNotNull(runId),store,{stopping},::emit)
            requireNotNull(usb).probe()
        }) { value ->
            tests["usb_fd"]=PreflightReport.test("usb_fd","PASS",evidence="CONFIGURED",actualRoute="USB_MEDIASTORE",data=value,start=stageAt,end=SystemClock.elapsedRealtime())
            perform("closed_file",15_000,true,{
                val target=requireNotNull(usb).target
                val previous=UsbSegmentCatalog(this).snapshot(target).segments.lastOrNull()
                if (previous==null) obj("available" to false)
                else obj("available" to true,"analysis" to PreflightFiles.inspect(this,previous.video.uri,previous.video.sizeBytes ?: 0) {stopping})
            }) { old ->
                tests["closed_file"]=PreflightReport.test("closed_file",if(old["available"]==JsonPrimitive(true))
                    old["analysis"]?.jsonObject?.get("status")?.jsonPrimitive?.content ?: "INCOMPLETE" else "SKIP",
                    if(old["available"]==JsonPrimitive(true)) null else "NO_PREVIOUS_COMPLETED_FILE",evidence=if(old["available"]==JsonPrimitive(true)) "ENCODED" else "NONE",data=old,start=stageAt,end=SystemClock.elapsedRealtime())
                if(mode=="P2_CAMERA") {
                    if(Build.VERSION.SDK_INT>=30)beginCameraProbe() else stopRun("P2_REQUIRES_ANDROID_11")
                } else if(mode=="P1_BASIC" || mode=="P1_INPUT" || mode=="P1_EXPERIMENT") {
                    if(Build.VERSION.SDK_INT>=30) beginSynthetic() else stopRun("P1_REQUIRES_ANDROID_11")
                } else beginBaseline()
            }
        }
    }
    @androidx.annotation.RequiresApi(30)
    private fun beginCameraProbe() {
        val plan=requireNotNull(experiment).cameraPlan()
        tests["baseline"]=PreflightReport.test("baseline","SKIP","P2_USES_CONTINUOUS_CAMERA_ROUTE")
        perform("p2_camera",plan.runTimeoutMs,true,{
            CameraProbeRunner(this,requireNotNull(usb).target,requireNotNull(runId),store,plan,{stopping},::emit).run()
        }) {value->
            val status=value.getValue("status").jsonPrimitive.content
            details["p2Summary"]=JsonObject(value.filterKeys {it !in setOf("decodedFiles","declarations","sourcePtsUs","codecPtsUs")})
            tests["p2_camera"]=PreflightReport.test("p2_camera",status,value["reason"]?.jsonPrimitive?.contentOrNull,
                evidence="REAL_CAMERA_REAL_WINDOWS_RUNTIME",actualRoute="USB_MEDIASTORE",data=value,start=stageAt,end=SystemClock.elapsedRealtime())
            if(status!="PASS")reason=value["reason"]?.jsonPrimitive?.contentOrNull ?: "P2_REVIEW_REQUIRED"
            finishRun(true)
        }
    }
    @androidx.annotation.RequiresApi(29)
    private fun beginSynthetic() {
        tests["baseline"]=PreflightReport.test("baseline","SKIP","P1_DOES_NOT_REQUIRE_CAMERA_BASELINE")
        val exploratory=mode=="P1_EXPERIMENT"
        val inputMode = if(exploratory)requireNotNull(experiment).shared else mode == "P1_INPUT"
        val testId = if(exploratory)"p1_experiment" else if(inputMode) "p1_input" else "p1_basic"
        perform(testId,300_000,true,{
            ProbeBasicRunner(this,requireNotNull(usb).target,requireNotNull(runId),store,{stopping},::emit,inputMode,experiment.takeIf {exploratory}).run()
        }) { value ->
            val status=value.getValue("status").jsonPrimitive.content
            details["p1Summary"]=PreflightReport.syntheticSummary(value)
            tests[testId]=PreflightReport.test(testId,status,
                value["reason"]?.jsonPrimitive?.contentOrNull,evidence="SYNTHETIC_RUNTIME",actualRoute="USB_MEDIASTORE",
                data=value,start=stageAt,end=SystemClock.elapsedRealtime())
            if(inputMode && !exploratory) {
                val inputPassed = listOf("healthInput","fullInput","stressInput").all { value[it]?.jsonObject?.get("status") == JsonPrimitive("PASS") }
                listOf("oes_input","shared_pool_pressure").forEach { id ->
                    tests[id]=PreflightReport.test(id,if(inputPassed) "PASS" else "INCOMPLETE",
                        if(inputPassed)null else "SEE_P1_INPUT_DETAILS",evidence="SYNTHETIC_RUNTIME",
                        data=obj("scope" to "SYNTHETIC_OFFSCREEN_ONLY","cameraOpened" to false,"realWindowTested" to false))
                }
                val groups=value["loadGroups"]?.jsonObject
                val groupStatuses=groups?.values?.mapNotNull { it.jsonObject["load"]?.takeUnless { e -> e==JsonNull }?.jsonObject?.get("status")?.jsonPrimitive?.content }.orEmpty()
                val loadStatus=when {
                    groupStatuses.size==2 && groupStatuses.all { it=="PASS" } -> "PASS"
                    groupStatuses.isEmpty() -> "NOT_RUN"
                    groupStatuses.size<2 || "INCOMPLETE" in groupStatuses -> "INCOMPLETE"
                    else -> "WARN"
                }
                tests["encoded_load"]=PreflightReport.test("encoded_load",loadStatus,
                    evidence=if(groupStatuses.isEmpty())"NONE" else "ENCODED",data=groups ?: obj())
            }
            if(status!="PASS") reason=value["reason"]?.jsonPrimitive?.contentOrNull ?: "P1_REVIEW_REQUIRED"
            finishRun(true)
        }
    }
    @androidx.annotation.RequiresApi(29)
    private fun beginEvidence() {
        perform("diagnostic_evidence",60_000,mode=="DIAGNOSTICS_RETIRE",{
            val target=UsbExportVolumeResolver.mountedTargets(this).singleOrNull() ?: error("ONE_USB_REQUIRED")
            val evidence=ProbeRetainedDiagnostics(this,target,requireNotNull(runId),store,{stopping})
            if(mode=="DIAGNOSTICS_LIST")evidence.inventory() else evidence.retire(remotePayload)
        }) { value ->
            details["diagnosticEvidence"]=value
            val status=value["status"]?.jsonPrimitive?.content ?: "INCOMPLETE"
            tests["diagnostic_evidence"]=PreflightReport.test("diagnostic_evidence",status,evidence="EXACT_DIAGNOSTIC_LEDGER",data=value)
            if(status!="PASS")reason="EVIDENCE_REVIEW_REQUIRED"
            finishRun(true)
        }
    }
    private fun perform(id: String, timeout: Long, active: Boolean, operation: () -> JsonObject, next: (JsonObject) -> Unit) {
        if(stopping || finished || !journalHealthy) return
        phase=id.uppercase(); activeTest=id; stageAt=SystemClock.elapsedRealtime(); deadline=stageAt+timeout
        var token=0L
        try {
            token=gate.begin(id) { if (mode !in setOf("CAPABILITIES_ONLY","DIAGNOSTICS_LIST")) { store.checkpoint(requireNotNull(runId),"$id:INTENT"); ownsBarrier=true } }
            tests[id]=PreflightReport.test(id,"INCOMPLETE","RUNNING",cleanup="PENDING",start=stageAt); save()
        } catch (_: Throwable) { gate.finish(token,true); journalHealthy=false; stopRun("CHECKPOINT_FAILED"); return }
        nativeWorkerActive=true
        worker.execute {
            val outcome=runCatching(operation)
            control.post {
                if (finished) return@post
                nativeWorkerActive=false
                val clean=outcome.exceptionOrNull() !is PreflightCleanupUnconfirmed
                gate.finish(token,clean)
                if(!clean) {
                    if(id=="p2_camera")details["p2InterruptedDetail"]=store.read("p2-last-detail.json")
                        ?.takeIf {it["runId"]==JsonPrimitive(runId)} ?: JsonNull
                    if(id.startsWith("p1_")) details["p1InterruptedDetail"]=store.read("p1-last-detail.json")
                        ?.takeIf { it["runId"]==JsonPrimitive(runId) } ?: JsonNull
                    reason="CLEANUP_UNCONFIRMED"; finishRun(false); return@post
                }
                if(stopping) {
                    tests[id]=PreflightReport.test(id,"CANCELLED",reason,cleanup="CONFIRMED",
                        data=if(id.startsWith("p1_") || id=="p2_camera" || id=="diagnostic_evidence")outcome.getOrNull() ?: obj() else obj(),
                        start=stageAt,end=SystemClock.elapsedRealtime()); finishRun(true)
                } else if(outcome.isFailure) {
                    val error=outcome.exceptionOrNull()!!
                    val code=error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) } ?: error.javaClass.simpleName
                    tests[id]=PreflightReport.test(id,"FAIL",code,start=stageAt,end=SystemClock.elapsedRealtime()); reason=code; finishRun(true)
                } else next(outcome.getOrThrow())
            }
        }
    }
    private fun beginBaseline() {
        if(stopping || finished) return
        val cfg=requireNotNull(config)
        phase="BASELINE"; activeTest="baseline"; stageAt=SystemClock.elapsedRealtime(); deadline=stageAt+45_000
        baselineToken=gate.begin("baseline")
        tests["baseline"]=PreflightReport.test("baseline","INCOMPLETE","RUNNING",cleanup="PENDING",start=stageAt)
        details["sourceSpec"]=obj("cameraId" to cfg.cameraId,"role" to cfg.source.sourceRole.name,"width" to cfg.profile.size.width,
            "height" to cfg.profile.size.height,"bitrateBps" to cfg.profile.bitrateBps,"requestedFps" to cfg.requestedFrameRate,
            "mappingRevision" to cfg.source.mappingRevision,"layout" to cfg.source.layoutKind.name,
            "mappingEvidence" to "CURRENT_PRODUCT_RESOLVER_NOT_CONTENT_REVALIDATED")
        details["adapter"]=obj("recorder" to "RecorderSession/LegacyRecordingEncoder","preview" to "Beta16/MirrorPreviewController/MirrorGlPreview/MirrorInputPump",
            "storage" to "UsbMediaStoreRecordingOutputSink/UsbSegmentCommitEngine","selection" to "EXPLICIT_EXISTING_MEDIA_RECORDER_BASELINE",
            "automaticCameraRecovery" to false,"internalFallback" to false,"userFileRetention" to false,"nativeFileRotation" to false)
        try { store.checkpoint(requireNotNull(runId),"baseline:BEFORE_RECORDER_START"); save() }
        catch (_:Throwable) { gate.finish(baselineToken,true); journalHealthy=false; stopRun("CHECKPOINT_FAILED"); return }
        recorderReleased=false
        main.post {
            if(stopping || finished) { recorderReleased=true; return@post }
            try {
                val created=RecorderSession(this,{ s -> recorderState=s; control.post { onRecorderState(s) } },
                    { control.post { if(!stopping && !finished) stopRun("RECORDER_STOPPED") } },externallyManagedPresence=true,diagnosticScope=usb)
                recorder=created
                if(stopping || finished) created.releaseForModeHandoff { recorderReleased=true }
                else created.start(cfg)
            } catch (_: Exception) { recorderReleased=true; control.post { stopRun("RECORDER_CONSTRUCTION_FAILED") } }
        }
    }
    private fun onRecorderState(s: RecorderState) {
        if(finished || stopping) return
        if(s.status==RecorderStatus.RECORDING) {
            if(baselineAt==0L) { baselineAt=SystemClock.elapsedRealtime(); deadline=baselineAt+PreflightPlan.BASELINE_MS+30_000 }
            if(recordingSince==0L) recordingSince=SystemClock.elapsedRealtime()
        } else recordingSince=0L
        if(s.lastError!=null || s.cleanupUnconfirmed || s.status in setOf(RecorderStatus.ERROR,RecorderStatus.CAMERA_UNAVAILABLE,RecorderStatus.WAITING_CAMERA)) {
            details["recorderStopEvidence"]=obj("status" to s.status,
                "errorCode" to s.lastError?.substringBefore(':')?.substringBefore(' ')?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) },
                "previewRequested" to s.previewRequested,"previewActive" to s.previewActive,"cleanupUnconfirmed" to s.cleanupUnconfirmed)
            stopRun(if(s.cleanupUnconfirmed) "RECORDER_CLEANUP_UNCONFIRMED" else "RECORDER_REPORTED_ERROR"); return
        }
        val key=MirrorPreviewPolicy.cameraKey(s) ?: return
        if(!mirrorEverCreated) {
            mirrorEverCreated=true; mirrorReleased=false
            main.post {
                if(stopping || finished) { mirrorReleased=true; return@post }
                val owner=requireNotNull(recorder)
                val source=object : MirrorCameraSource {
                    override val state get()=recorderState
                    override fun enable(enabled:Boolean,runId:String,generation:Long)=owner.setPreviewOutputEnabled(enabled,runId,generation)
                    override fun replace(surface:Surface,runId:String,generation:Long,released:()->Unit)=owner.replacePreviewSurface(surface,runId,generation,released)
                    override fun stop() { control.post { stopRun("USER_CANCELLED") } }
                    override fun bookmark() = Unit
                }
                runCatching { MirrorPreviewController(this,requireNotNull(config),source,key.sessionId,key.cameraGeneration) }
                    .onSuccess { mirror=it }.onFailure { mirrorReleased=true; control.post { stopRun("PREVIEW_CONSTRUCTION_FAILED") } }
            }
        }
    }
    private fun observe(now: Long) {
        val state=recorderState
        val power=getSystemService(PowerManager::class.java)
        if(!power.isInteractive || !PreflightActivity.visible) { stopRun("PAGE_HIDDEN_OR_SCREEN_OFF"); return }
        if(Build.VERSION.SDK_INT>=29 && power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE) { stopRun("THERMAL_SEVERE"); return }
        if(usb?.target?.let { !UsbExportVolumeResolver.isRemovableVolumeMounted(this,it.storageUuid) } == true) { stopRun("USB_REMOVED"); return }
        val m=MirrorPreviewRuntime.evidence; val c=RecorderCaptureEvidence.latest
        val matches=c.sessionId==state.recordingSessionId && c.segment==state.segmentNumber
        val gl=m.gl
        val (observedInputAge,observedDisplayAge)=progressClock.observe(now,gl.inputFrames,m.frames)
        if(recordingSince>0 && now-recordingSince>15_000 && mirrorEverCreated) {
            maxInputAge=maxOf(maxInputAge,gl.inputFrameAgeMs ?: 0); maxDisplayAge=maxOf(maxDisplayAge,m.lastFrameAgeMs ?: 0)
            if(gl.inputFrameAgeMs==null || gl.inputFrameAgeMs>8_000 || m.lastFrameAgeMs==null || m.lastFrameAgeMs>8_000 || observedInputAge>8_000 || observedDisplayAge>8_000) {
                details["firstAnomaly"]=JsonObject(sample(now,state,m,c,matches)+obj("observedInputCounterAgeMs" to observedInputAge,"observedDisplayCounterAgeMs" to observedDisplayAge))
                stopRun("PREVIEW_FRESHNESS_STALL"); return
            }
            freshSamples++
        }
        if(sampleTicks++%5==0) {
            val observation=sample(now,state,m,c,matches)
            samples.addLast(observation); if(samples.size>310) samples.removeFirst()
            // Resource reads run on the inspection executor, independently of the camera/GL workers.
            if(resourcesPending.compareAndSet(false,true)) worker.execute {
                val resources=runCatching { PreflightInventory.resources(this) }.getOrNull(); resourcesPending.set(false)
                control.post { if(!finished && resources!=null) details["lastResources"]=resources }
            }
        }
        if(baselineAt>0 && now-baselineAt>=PreflightPlan.BASELINE_MS) stopRun("PLAN_DURATION_REACHED")
    }
    private fun sample(now:Long,s:RecorderState,m:MirrorEvidence,c:CaptureEvidence,matches:Boolean)=obj(
        "atElapsedMs" to now,"status" to s.status,"segment" to s.segmentNumber,"cameraResourceId" to "camera-${s.cameraGeneration}",
        "captureResourceId" to "capture-${s.cameraGeneration}-${s.captureSessionRevision}","actualRoute" to s.recordingBackend,
        "captureMatchesRun" to matches,"captureCallbacks" to if(matches)c.frames.count else null,
        "previewRequested" to s.previewRequested,"previewActive" to s.previewActive,"mirrorReason" to m.reason,
        "captureWithPreviewTarget" to if(matches)c.preview.completedWithPreviewTarget else null,
        "captureWithoutPreviewTarget" to if(matches)c.preview.completedWithoutPreviewTarget else null,
        "lastCaptureHadPreviewTarget" to if(matches)c.preview.lastCompletedHadPreviewTarget else null,
        "captureMaxGapNs" to if(matches)c.frames.maxGapNs?.toString() else null,
        "lastCaptureAgeMs" to if(matches)c.lastReceivedElapsedMs?.let { (now-it).coerceAtLeast(0) } else null,
        "sensorTimestampNs" to if(matches)c.frames.lastTimestampNs?.toString() else null,
        "frameAvailable" to m.gl.inputNotifications,"updateTexImageFreshFrames" to m.gl.inputFrames,
        "inputPollAttempts" to m.gl.inputPollAttempts,"inputPolledFrames" to m.gl.inputPolledFrames,
        "lastInputNotificationAgeMs" to m.gl.lastInputNotificationAgeMs,"lastInputPollAgeMs" to m.gl.lastInputPollAgeMs,
        "inputTimestampNs" to m.gl.inputTimestampNs?.toString(),"inputAgeMs" to m.gl.inputFrameAgeMs,
        "inputHeartbeatAgeMs" to m.gl.inputHeartbeatAgeMs,"displaySubmissions" to m.gl.displaySubmissions,
        "visibleViewFreshFrames" to m.frames,"visibleViewAgeMs" to m.lastFrameAgeMs,
        "displayDroppedFrames" to m.gl.droppedDisplayFrames,"poolBytes" to m.gl.poolBytes,"poolSlots" to m.gl.poolSlots,
        "displayWorkers" to m.gl.displayWorkers,"inputFailure" to m.gl.inputFailure,"displayFailure" to m.gl.displayFailure,
        "displayRecoveryAttempts" to m.displayRecoveryAttempts,"previewFallbackUsed" to s.previewFallbackUsed,"wakeLockHeld" to s.wakeLockHeld,
        "previewLostBuffers" to if(matches)c.preview.previewBuffersLost else null,
        "encoderLostBuffers" to if(matches)c.preview.encoderBuffersLost else null,
        "encodedLiveFrames" to null,"encodedLiveReason" to "MEDIA_RECORDER_NOT_EXPOSED",
        "publishedSourceSerial" to m.gl.publishedSourceSerial.toString(),
        "lastDisplaySourceSerial" to m.gl.lastDisplaySourceSerial.toString(),
        "lastDisplaySourceTimestampNs" to m.gl.lastDisplaySourceTimestampNs?.toString(),
        "visibleViewTimestampNs" to m.lastTextureTimestampNs?.toString(),
        "correlationMethod" to "COUNTERS_SAMPLED_NOT_PER_FRAME_PROOF",
        "clockDomains" to "Age=elapsedRealtime; sensor and SurfaceTexture timestamps not cross-subtracted")
    private fun stopRun(code:String) {
        if(stopping || finished) return
        stopping=true; reason=code; stopAt=SystemClock.elapsedRealtime(); gate.cancel()
        emit(obj("event" to "STOP_REQUESTED","reason" to code))
        if(phase=="BASELINE" || recorder!=null || !recorderReleased) {
            baselineStopping=true; phase="CLEANUP"
            // RecorderSession posts to its own worker. A stuck UI must not prevent this request.
            recorder?.releaseForModeHandoff { recorderReleased=true }
            // If construction is still queued, its before/after checks acknowledge cancellation.
            main.post { mirror?.close() }
        } else if(!nativeWorkerActive) finishRun(true)
        saveOrStop(); publish()
    }
    private fun finishBaseline() {
        baselineStopping=false; gate.finish(baselineToken,true)
        val elapsed=if(baselineAt>0) (stopAt-baselineAt).coerceAtLeast(0) else 0
        val status=if(reason=="USER_CANCELLED") "CANCELLED" else "INCOMPLETE"
        tests["baseline"]=PreflightReport.test("baseline",status,reason,if(baselineAt>0) "CAMERA_SMOKE" else "NONE","MEDIA_RECORDER",
            obj("plannedDurationMs" to PreflightPlan.BASELINE_MS,"observedDurationMs" to elapsed,"freshObservationSamples" to freshSamples,
                "maxInputAgeOutsideTransitionMs" to maxInputAge,"maxVisibleAgeOutsideTransitionMs" to maxDisplayAge,
                "continuousCameraSession" to false,"gaplessRecording" to "NOT_CLAIMED","displayRecoveryOwnedBy" to "EXISTING_BETA14_CONTROLLER"),
            start=baselineAt.takeIf { it>0 },end=SystemClock.elapsedRealtime())
        phase="FILES_CLEANUP"; nativeWorkerActive=true; activeTest="test_files_cleanup"; stageAt=SystemClock.elapsedRealtime(); deadline=stageAt+20_000; stopAt=stageAt
        worker.execute {
            val outcome=runCatching { usb?.finishFiles(); usb?.summary() }
            control.post {
                if(finished) return@post
                nativeWorkerActive=false; details["usbTestFiles"]=outcome.getOrNull() ?: JsonNull
                val usbEvidence=outcome.getOrNull()
                val resultStatus=PreflightPlan.baselineStatus(reason,elapsed,freshSamples,
                    usbEvidence?.get("completedFiles")?.jsonPrimitive?.intOrNull ?: 0,
                    outcome.isFailure || usbEvidence?.get("failed")==JsonPrimitive(true),recorderState.recordingBackend)
                tests["baseline"]=JsonObject(requireNotNull(tests["baseline"])+("status" to JsonPrimitive(resultStatus)))
                tests["test_files_cleanup"]=PreflightReport.test("test_files_cleanup",if(outcome.isSuccess) "PASS" else "WARN",
                    if(outcome.isSuccess)null else "TEST_FILES_RETAINED",evidence="FILE_OWNERSHIP",start=stageAt,end=SystemClock.elapsedRealtime())
                finishRun(true)
            }
        }
    }
    private fun report(cleanup: String)=obj("schemaVersion" to 1,"exampleOnly" to false,"format" to "OPENAVM_PREFLIGHT",
        "runId" to runId,"reportId" to runId,"version" to BuildConfig.VERSION_NAME,"planVersion" to PreflightPlan.VERSION,
        "mode" to mode,"actualCoverage" to when(mode) { "P2_CAMERA"->"P2_REAL_CAMERA_REAL_WINDOWS_DIAGNOSTIC"; "P1_INPUT"->"P1_INPUT_SYNTHETIC_OES_SHARED_OFFSCREEN"; "P1_BASIC"->"P1_BASIC_SYNTHETIC_DIRECT_GL"; "P1_EXPERIMENT"->"EXPLORATORY_SYNTHETIC_ONLY"; "DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE"->"EXACT_DIAGNOSTIC_LEDGER_ONLY"; else->"P0_HARDWARE_AND_CURRENT_BASELINE" },"phase" to phase,"reason" to reason,
        "remoteConfiguration" to remotePayload,"remoteConfigurationHash" to payloadHash(remotePayload),
        "startEpochMs" to startEpoch,"startElapsedMs" to startAt,"savedElapsedMs" to SystemClock.elapsedRealtime(),"bootCount" to store.bootCount,
        "cleanup" to cleanup,"journalHealthy" to journalHealthy,"productionSwitchAllowed" to false,
        "budget" to if(mode=="P2_CAMERA")requireNotNull(experiment).cameraPlan().let {plan->obj(
            "currentRunEncodedByteBudget" to plan.encodedByteBudget,"minimumUsbFreeBytes" to plan.minimumFreeBytes,
            "reserveBytes" to PreflightPlan.RESERVE,"filesPerRun" to 4,"maximumRetainedDiagnosticFiles" to 8,
            "encodedQueueBytes" to 32L*1024*1024,"encodedQueueItems" to 300,"runtimeTimeoutMs" to plan.runTimeoutMs,"eventLimit" to 600)
        } else obj("maxWrittenBytes" to PreflightPlan.MAX_WRITE,"maxRetainedBytes" to PreflightPlan.MAX_RETAINED,"reserveBytes" to PreflightPlan.RESERVE,
            "plannedBaselineMs" to PreflightPlan.BASELINE_MS,"eventLimit" to 600,"sampleLimit" to 310),
        "tests" to tests.values.toList(),"events" to events.toList(),"droppedQueuedEvents" to droppedEvents.get(),"samples" to samples.toList(),
        "untested" to deferredCases(),"manualRequired" to listOf("PARKED_CONFIRMED_BY_USER","OEM_RECLAIM","AWAY_SLEEP","USB_UNPLUG","REAL_CANDIDATES"),
        "findings" to listOf(obj("kind" to "OBSERVATION","statement" to "Declarations, synthetic tests, real camera tests and production behavior have separate coverage.","evidenceRefs" to listOf("tests")),
            obj("kind" to "PROPOSAL","statement" to if(mode=="P2_CAMERA")"P2 tests real camera and diagnostic page/overlay windows. Production routing and playback compatibility remain separate acceptance steps." else "P1 is synthetic diagnostics only. Real camera, actual UI windows and production integration require later verification.","evidenceRefs" to listOf(if(mode=="P2_CAMERA")"tests.p2_camera" else if(mode=="P1_INPUT")"tests.p1_input" else "tests.p1_basic"))))
        .let { JsonObject(it+details) }
    private fun save() {
        val report=report(if(finished) if(reason=="CLEANUP_UNCONFIRMED") "UNCONFIRMED" else "CONFIRMED" else "PENDING")
        PreflightRuntime.report=report; store.save(report); lastSaveAt=SystemClock.elapsedRealtime()
    }
    private fun saveOrStop() {
        if(!journalHealthy) return
        try { save() } catch (_:Throwable) { journalHealthy=false; if(!stopping) stopRun("JOURNAL_WRITE_FAILED") }
    }
    private fun finishRun(confirmed:Boolean) {
        if(finished) return
        finished=true; phase=if(confirmed) when(mode) { "P2_CAMERA"->"COMPLETE_P2_CAMERA"; "P1_INPUT"->"COMPLETE_P1_INPUT"; "P1_BASIC"->"COMPLETE_P1_BASIC"; "P1_EXPERIMENT"->"COMPLETE_EXPERIMENT"; "DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE"->"COMPLETE_EVIDENCE"; else->"COMPLETE_P0" } else "CLEANUP_UNCONFIRMED"
        if(!confirmed) {
            details["interruptionReason"]=j(reason); reason="CLEANUP_UNCONFIRMED"
            activeTest?.let { tests[it]=PreflightReport.test(it,"INCOMPLETE",reason,cleanup="UNCONFIRMED",start=stageAt,end=SystemClock.elapsedRealtime()) }
        }
        if(ownsBarrier) runCatching { store.checkpoint(requireNotNull(runId),phase,if(confirmed) "CONFIRMED" else "UNCONFIRMED") }
            .onFailure { journalHealthy=false }
        val value=report(if(confirmed) "CONFIRMED" else "UNCONFIRMED"); PreflightRuntime.report=value
        runCatching { store.save(value) }.onFailure { journalHealthy=false }
        DiagnosticUpload.finished(applicationContext,value.toString().toByteArray())
        if(confirmed) runId?.let(CameraWorkCoordinator::finish) else synchronized(PreflightRuntime.retained) { PreflightRuntime.retained+=this }
        PreflightRuntime.mutable.value=PreflightUi(false,!confirmed,phase,((SystemClock.elapsedRealtime()-startAt)/1000).toInt(),reason.orEmpty(),true,results())
        main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun results()=tests.map { (id,result) -> "$id: ${result["status"]?.jsonPrimitive?.content}" }
    private fun publish() {
        if(finished) return
        PreflightRuntime.mutable.value=PreflightUi(true,false,phase,((SystemClock.elapsedRealtime()-(baselineAt.takeIf{it>0} ?: startAt))/1000).toInt(),reason.orEmpty(),true,results())
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }; if(instance===this)instance=null
        if(!finished) { stopping=true; synchronized(PreflightRuntime.retained) { PreflightRuntime.retained+=this }; main.post { mirror?.close(); recorder?.release() } }
        if(finished) { worker.shutdown(); controlThread.quitSafely() }
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun deferredCases()=when(mode) {
        "P2_CAMERA"->listOf("production_routing","production_playback_and_phone_export","oem_camera_reclaim","real_source_overload")
        "P1_INPUT" -> listOf("candidate_camera_smoke","candidate_soak","window_scripts","real_source_overload")
        "P1_BASIC" -> listOf("oes_input","shared_pool_pressure","candidate_camera_smoke","candidate_soak","window_scripts")
        else -> PreflightPlan.deferred
    }
    companion object {
        private const val START="openavm.preflight.START"; private const val STOP="openavm.preflight.STOP"
        @Volatile private var instance:PreflightService?=null
        fun start(context:Context,full:Boolean):Boolean = startMode(context,if(full) "FULL_SAFE" else "CAPABILITIES_ONLY")
        fun startSynthetic(context:Context):Boolean = startMode(context,"P1_BASIC")
        fun startSharedInput(context:Context):Boolean = startMode(context,"P1_INPUT")
        fun startCameraProbe(context:Context):Boolean = startMode(context,"P2_CAMERA",
            obj("experiment" to LabExperiment.parse(obj("profile" to "CAMERA_SMOKE")).json()))
        internal fun startExperiment(context:Context,experiment:LabExperiment,recipeHash:String,owner:String):Boolean = startMode(context,
            if(experiment.realCamera)"P2_CAMERA" else if(experiment.reference)"P1_INPUT" else "P1_EXPERIMENT",obj("experiment" to experiment.json(),"recipeHash" to recipeHash,"commandId" to owner),owner)
        internal fun startEvidence(context:Context,retire:Boolean,payload:JsonObject):Boolean = startMode(context,if(retire)"DIAGNOSTICS_RETIRE" else "DIAGNOSTICS_LIST",payload)
        private fun startMode(context:Context,mode:String,payload:JsonObject=obj(),owner:String?=null):Boolean = DiagnosticUpload.startTestWhenIdle(owner) { startWhenUploadIdle(context,mode,payload) }
        private fun startWhenUploadIdle(context:Context,mode:String,payload:JsonObject):Boolean {
            if(!ZeekrApp.isForeground.value || CameraRecordingService.isRunning() || instance!=null) return false
            if(mode!="CAPABILITIES_ONLY" && Build.VERSION.SDK_INT<30) return false
            if(mode in setOf("FULL_SAFE","P2_CAMERA") && ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return false
            val owner=CameraWorkCoordinator.claim("PREFLIGHT") ?: return false
            try {
                PreflightStore(context).write("latest-attempt.json",obj("runId" to owner,"version" to BuildConfig.VERSION_NAME,
                    "startedAtEpochMs" to System.currentTimeMillis()))
                PreflightRuntime.report=null
                PreflightRuntime.remoteCancelRun=null
                PreflightRuntime.remoteProgress=obj("runId" to owner,"stage" to "WAITING_FOR_IDLE","stages" to emptyList<String>())
            } catch(_:Throwable) {
                CameraWorkCoordinator.finish(owner)
                PreflightRuntime.mutable.value=PreflightUi(message="START_JOURNAL_UNWRITABLE")
                return false
            }
            PreflightRuntime.mutable.value=PreflightUi(active=true,phase="WAITING_FOR_IDLE")
            return runCatching { ContextCompat.startForegroundService(context,Intent(context,PreflightService::class.java).setAction(START)
                .putExtra("owner",owner).putExtra("mode",mode).putExtra("remotePayload",payload.toString())); true }
                .getOrElse { CameraWorkCoordinator.finish(owner); recordStartFailure(context,owner,mode,"SERVICE_START_FAILED"); false }
        }
        private fun recordStartFailure(context:Context,owner:String,mode:String,code:String) {
            val now=SystemClock.elapsedRealtime()
            val value=obj("schemaVersion" to 1,"format" to "OPENAVM_PREFLIGHT","exampleOnly" to false,
                "productionSwitchAllowed" to false,"runId" to owner,"reportId" to owner,"version" to BuildConfig.VERSION_NAME,
                "planVersion" to PreflightPlan.VERSION,"mode" to mode,"phase" to "START_REJECTED","reason" to code,
                "startEpochMs" to System.currentTimeMillis(),"startElapsedMs" to now,"savedElapsedMs" to now,
                "cleanup" to "CONFIRMED","tests" to listOf(PreflightReport.test("startup","FAIL",code,"CONFIGURED")))
            PreflightRuntime.report=value
            runCatching { PreflightStore(context).save(value) }
            PreflightRuntime.mutable.value=PreflightUi(phase="START_REJECTED",message=code,reportReady=true)
            DiagnosticUpload.finished(context,value.toString().toByteArray())
        }
        fun stop() { instance?.let { owner -> owner.control.post { owner.stopRun("USER_CANCELLED") } } }
        internal fun stopExpected(run:String) {
            if(CameraWorkCoordinator.state.value.owner==run)PreflightRuntime.remoteCancelRun=run
            instance?.let {owner->owner.control.post {if(owner.runId==run)owner.stopRun("USER_CANCELLED")}}
        }
        fun pageHidden() { instance?.takeIf{it.mode!="CAPABILITIES_ONLY"}?.let { owner -> owner.control.post { owner.stopRun("PAGE_HIDDEN") } } }
    }
}
