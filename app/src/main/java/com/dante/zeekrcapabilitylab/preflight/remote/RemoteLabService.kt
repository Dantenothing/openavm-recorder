package com.dante.zeekrcapabilitylab.preflight.remote

import android.app.*
import android.content.Intent
import android.os.*
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.PreflightActivity
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticSettings
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated control process: all HTTP runs on io, never on the command/stop actor. */
class RemoteLabService:Service() {
    private val actor=Executors.newSingleThreadScheduledExecutor { Thread(it,"remote-lab-control") }
    private val io=Executors.newSingleThreadExecutor { Thread(it,"remote-lab-network") }
    private val lane=LabTaskLane(actor,io)
    private val main=Handler(Looper.getMainLooper())
    private val generation=UUID.randomUUID().toString()
    private lateinit var store:LabStore
    private var wire:LabWire?=null
    private var engine:Messenger?=null
    private var engineStatus=obj()
    private var engineAt=0L
    private var recovered=false
    @Volatile private var running=false
    @Volatile private var stopRequested=false
    private var endingAt=0L
    private var wake:PowerManager.WakeLock?=null
    private var message="远程会话未开启"
    private var lastConnected=0L
    private var connectedAt=0L
    private var nextPoll=0L
    private var permissions=obj()
    private var exitEvidence:JsonArray=JsonArray(emptyList())
    private var exitsAt=0L
    private var exitQuery="NOT_QUERIED"
    private val buildId by lazy {runCatching {assets.open("preflight/build-evidence.json").bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonObject.string("buildId") }}.getOrDefault("UNAVAILABLE")}
    private val receiver=Messenger(Handler(Looper.getMainLooper()) { msg ->
        if(msg.sendingUid==applicationInfo.uid) {
            val raw=msg.data.getString("json").orEmpty();val reply=msg.replyTo;val what=msg.what
            if(raw.length<=65536)actor.execute {runCatching {
                val data=Json.parseToJsonElement(raw).jsonObject
                when(what) {
                    LabMessages.REGISTER->{engine=reply;if(recovered){sendEngine(obj("kind" to "SESSION_END"));recovered=false};publish()}
                    LabMessages.STATUS->{engineStatus=data;engineAt=SystemClock.elapsedRealtime()}
                    LabMessages.RESULT->engineResult(data)
                    LabMessages.EVENT->if(store.state.string("sessionId").isNotEmpty())store.appendEvent(data.string("type"),
                        JsonObject((data["data"] as? JsonObject ?: obj())+obj("mainGeneration" to data["mainGeneration"])),generation)
                }
            }.onFailure {message=errorCode(it);publish()} }
        };true
    })
    override fun onCreate() {
        super.onCreate();store=LabStore(this);permissions=LabPermissions.read(this)
        actor.execute {
            for((id,raw) in store.records())if(raw.jsonObject.string("state") !in LabPolicy.terminal) {
                recovered=true
                record(id,"INTERRUPTED",obj("commandState" to "ACCEPTED","runState" to "INTERRUPTED",
                    "outcome" to "INCONCLUSIVE","cleanupState" to "UNKNOWN","reason" to "CONTROL_PROCESS_RESTARTED_NO_REPLAY"))
            }
        }
    }
    override fun onBind(intent:Intent):IBinder=receiver.binder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==END) {stopRequested=true;actor.execute {end("正在结束会话并等待测试收尾")};return START_NOT_STICKY}
        foreground()
        val parked=intent?.getBooleanExtra("parked",false)==true
        val resume=intent?.action==RESUME
        actor.execute {runCatching {
            if(running)return@runCatching
            check(resume || parked) {"PARKED_CONFIRMATION_REQUIRED"}
            val connection=DiagnosticSettings(this).load()?.takeIf {it.paired} ?: error("PAIRING_REQUIRED")
            if(resume)check(store.active()) {"SESSION_EXPIRED"}
            else if(!store.active())store.arm(false)
            lane.invalidate();wire?.cancelAll();wire=LabWire(connection)
            endingAt=0;stopRequested=false;running=true;nextPoll=0;connectedAt=0
            main.post {acquireWake()};tick()
        }.onFailure {end(errorCode(it))}}
        return START_NOT_STICKY
    }
    private fun foreground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"远程实验会话",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,2102,Intent(this,RemoteLabService::class.java).setAction(END),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,2103,Intent(this,PreflightActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenAVM 远程实验台").setContentText("停车会话 · 最长 4 小时 · 可随时结束")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"结束会话",stop).build()).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(2101,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(2101,notification)
    }
    private fun acquireWake() {
        wake?.takeIf {it.isHeld}?.release()
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OpenAVM:RemoteLab")
            .apply {acquire((store.state.number("deadlineElapsed")-SystemClock.elapsedRealtime()).coerceIn(1,LabPolicy.DURATION_MS))}
    }
    private fun engineReady()=engine!=null && engineAt>0 && SystemClock.elapsedRealtime()-engineAt<10_000 && engineStatus.flag("visible")
    private fun hasWork()=store.records().values.any {it.jsonObject.string("state") !in LabPolicy.terminal}
    private fun tick() {
        if(!running)return
        try {tickOnce()}catch(t:Throwable) {end("控制状态异常 · "+errorCode(t))}
        finally {publish();if(running)actor.schedule(::tick,1,TimeUnit.SECONDS)}
    }
    private fun tickOnce() {
        val now=SystemClock.elapsedRealtime()
        if((stopRequested || !store.active()) && endingAt==0L)end("会话已结束或到期，等待收尾")
        if(!engineReady() && now-exitsAt>30_000 && Build.VERSION.SDK_INT>=30) {
            exitsAt=now
            val observed=runCatching {getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName,0,3).map {
                    obj("atEpochMs" to it.timestamp,"reasonNumber" to it.reason,"signalOrExitStatus" to it.status,
                        "processRole" to if(it.processName==packageName)"MAIN" else "AUXILIARY")
                }}
            exitQuery=if(observed.isSuccess)"AVAILABLE" else "QUERY_FAILED"
            if(observed.isSuccess)exitEvidence=JsonArray(observed.getOrThrow())
        }
        for((id,raw) in store.records()) {
            val r=raw.jsonObject
            if(r.string("state") !in LabPolicy.terminal && now-r.number("acceptedElapsed")>30_000 &&
                (!engineReady() || r.string("state")=="ACCEPTED"))
                record(id,"INTERRUPTED",obj("commandState" to "ACCEPTED","runState" to "INTERRUPTED",
                    "outcome" to "INCONCLUSIVE","cleanupState" to "UNKNOWN","reason" to "ENGINE_RESPONSE_LOST_NO_REPLAY"))
        }
        if(endingAt>0 && now-endingAt>=45_000) {
            for((id,raw) in store.records())if(raw.jsonObject.string("state") !in LabPolicy.terminal)
                record(id,"INTERRUPTED",obj("outcome" to "INCONCLUSIVE","cleanupState" to "UNKNOWN","reason" to "SESSION_END_CLEANUP_TIMEOUT"))
            finishService();return
        }
        if(!lane.busy && now>=nextPoll)poll()
    }
    private data class Exchange(val opened:Boolean,val acknowledged:Map<String,JsonObject>,val response:JsonObject?,val pollStarted:Long,val closed:Boolean)
    private fun poll() {
        val api=wire ?: run {finishService();return}
        val session=store.state.string("sessionId")
        if(!LabPolicy.uuid.matches(session)) {finishService();return}
        val opened=store.state.flag("serverOpened")
        val records=store.records().mapValues {it.value.jsonObject}.filterValues {!it.flag("sent")}
        val body=LabTransport.poll(snapshot(),store.pendingEvents())
        val events=body.getValue("events").jsonArray
        val through=events.lastOrNull()?.jsonObject?.number("seq") ?: store.state.number("eventAck")
        val closing=endingAt>0 && !hasWork()
        lane.submit({
            if(!opened) {
                val response=api.post("/v1/lab/sessions",obj("sessionId" to session,"durationSeconds" to 14400))
                check(response.string("sessionId")==session && response.number("protocol")==1L) {"SESSION_RESPONSE_INVALID"}
            }
            for((id,r) in records)api.post("/v1/lab/commands/"+id+"/result",obj("state" to r["state"],"result" to r["result"]))
            val started=SystemClock.elapsedRealtime()
            if(closing) {
                api.post("/v1/lab/sessions/"+session+"/poll",body)
                api.post("/v1/lab/sessions/"+session+"/close")
                Exchange(true,records,null,started,true)
            } else Exchange(true,records,api.post("/v1/lab/sessions/"+session+"/poll",body),started,false)
        }) { outcome ->
            if(session!=store.state.string("sessionId"))return@submit
            nextPoll=SystemClock.elapsedRealtime()+5_000
            outcome.mapCatching { result ->
                store.change("serverOpened" to result.opened)
                for((id,sent) in result.acknowledged)if(store.records()[id]==sent)
                    store.record(id,JsonObject(sent+obj("sent" to true)))
                if(result.closed){finishService();return@mapCatching}
                val response=requireNotNull(result.response)
                check(response.string("sessionId")==session) {"SESSION_RESPONSE_INVALID"}
                store.acknowledgeEvents(response.number("eventAck"),through)
                connectedAt=SystemClock.elapsedRealtime();lastConnected=System.currentTimeMillis()
                if(endingAt==0L)message=if(hasWork())"已连接 · 正在执行实验" else "已连接 · 等待电脑任务"
                publish()
                if(!stopRequested && store.active())for(raw in response.getValue("commands").jsonArray) {
                    val c=raw.jsonObject
                    if(c.string("id") !in store.records())execute(c,response.number("now")+SystemClock.elapsedRealtime()-result.pollStarted)
                }
            }.onFailure { t ->
                connectedAt=0;message="连接暂未完成 · "+errorCode(t)
                if(t is LabHttpFailure && t.status in setOf(401,403,410))end(message)
            }
            publish()
        }
    }
    private fun snapshot():JsonObject=obj("protocol" to 2,"runnerVersion" to LabRecipe.RUNNER_VERSION,
        "versionCode" to BuildConfig.VERSION_CODE,"version" to BuildConfig.VERSION_NAME,"buildId" to buildId,"processGeneration" to generation,
        "historicalProcessExits" to exitEvidence,"processExitQuery" to exitQuery,"processExitObservedElapsedMs" to exitsAt,
        "sessionId" to store.state.string("sessionId"),"armed" to (store.active() && !stopRequested),"running" to running,
        "controlObservedElapsedMs" to SystemClock.elapsedRealtime(),
        "allowUpdates" to false,"networkConnected" to (connectedAt>0 && SystemClock.elapsedRealtime()-connectedAt<15_000),
        "remainingSeconds" to ((store.state.number("deadlineElapsed")-SystemClock.elapsedRealtime()).coerceAtLeast(0)/1000),
        "engineAgeMs" to if(engineAt>0)SystemClock.elapsedRealtime()-engineAt else -1,"engine" to engineStatus,
        "capabilities" to LabContract.capabilities(),"permissionQueries" to permissions,"message" to message,"lastConnectedEpochMs" to lastConnected,
        "eventHighWater" to store.state.number("eventSeq"),"eventAck" to store.state.number("eventAck"),"updateInProgress" to false)
    private fun publish() {runCatching {engine?.send(Message.obtain(null,LabMessages.UI).apply {data=Bundle().apply {putString("json",snapshot().toString())}})}}
    private fun sendEngine(c:JsonObject) {
        (engine ?: error("ENGINE_NOT_CONNECTED")).send(Message.obtain(null,LabMessages.COMMAND).apply {data=Bundle().apply {putString("json",c.toString())}})
    }
    private fun execute(c:JsonObject,now:Long) {
        val id=c.string("id")
        try {
            LabPolicy.validateCommand(c,store.state.string("sessionId"),BuildConfig.VERSION_CODE,now,buildId)
            check(store.active() && !stopRequested) {"SESSION_EXPIRED"}
            check(LabPolicy.canAccept(id,store.records())) {"COMMAND_ALREADY_SEEN_OR_FULL"}
            check(engineReady()) {"ENGINE_NOT_READY"}
            val kind=c.string("kind")
            if(kind !in setOf("STOP","REPORT")) {
                check(!engineStatus.flag("active") && !engineStatus.flag("cameraBusy")) {"ENGINE_BUSY"}
                if(kind !in setOf("CAPABILITIES","DIAGNOSTICS_LIST"))check(!engineStatus.flag("blocked")) {"ENGINE_UNSETTLED"}
            }
            record(id,"ACCEPTED",obj("commandState" to "ACCEPTED","runState" to "QUEUED","outcome" to "PENDING","cleanupState" to "NOT_APPLICABLE"),
                obj("command" to c,"acceptedElapsed" to SystemClock.elapsedRealtime()))
            sendEngine(JsonObject(c+obj("executeBeforeElapsed" to
                (SystemClock.elapsedRealtime()+minOf(10_000,c.number("expires")-now)))))
        } catch(t:Throwable) {
            if(LabPolicy.uuid.matches(id) && store.records().size<128)record(id,"REJECTED",obj("commandState" to "REJECTED","runState" to "NOT_STARTED",
                "outcome" to "INCONCLUSIVE","cleanupState" to "NOT_APPLICABLE","reason" to errorCode(t)))
        }
    }
    private fun record(id:String,state:String,result:JsonObject,base:JsonObject?=null) {
        val old=base ?: (store.records()[id] as? JsonObject) ?: obj()
        val safe=if(result.toString().toByteArray().size<=8192)result else obj("reason" to "RESULT_DETAIL_IN_REPORT","runId" to result["runId"],
            "outcome" to result["outcome"],"cleanupState" to result["cleanupState"],"resultHash" to payloadHash(result))
        store.record(id,JsonObject(old+obj("state" to state,"result" to safe,"sent" to false)))
        store.appendEvent("COMMAND_"+state,obj("commandId" to id,"outcome" to safe["outcome"],"reason" to safe["reason"]),generation)
        message=state+" · "+safe.string("reason");publish()
    }
    private fun engineResult(data:JsonObject) {
        val id=data.string("id");val prior=store.records()[id] as? JsonObject ?: return
        if(prior.string("state") in LabPolicy.terminal)return
        val state=data.string("state")
        if(state in setOf("RUNNING","SUCCEEDED","FAILED","REJECTED","INTERRUPTED"))record(id,state,data["result"] as? JsonObject ?: obj())
    }
    private fun end(reason:String) {
        stopRequested=true
        if(endingAt==0L) {
            endingAt=SystemClock.elapsedRealtime()
            lane.invalidate();wire?.cancelAll();nextPoll=0
            store.change("armed" to false)
            runCatching {sendEngine(obj("kind" to "SESSION_END"))}
        }
        message=reason;publish()
        if(!running)finishService()
    }
    private fun finishService() {
        running=false;stopRequested=true;lane.invalidate();wire?.cancelAll()
        publish()
        main.post {wake?.takeIf {it.isHeld}?.release();wake=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        stopRequested=true
        actor.execute {end("系统结束了远程会话");finishService()}
    }
    override fun onDestroy() {
        running=false;wire?.cancelAll();actor.shutdownNow();io.shutdownNow()
        wake?.takeIf {it.isHeld}?.release();super.onDestroy()
    }
    companion object {
        const val START="openavm.remote.START";const val END="openavm.remote.END";const val RESUME="openavm.remote.RESUME"
        private const val CHANNEL="openavm_remote_lab"
        fun installerEvent(@Suppress("UNUSED_PARAMETER") data:JsonObject):Boolean=false
        private fun errorCode(t:Throwable)=when(t) {
            is java.io.IOException->"NETWORK_UNAVAILABLE"
            else->t.message?.takeIf {Regex("[A-Z0-9_]{1,80}").matches(it)} ?: t.javaClass.simpleName
        }
    }
}
