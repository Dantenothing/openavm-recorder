package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Passive bounded local journal. No networking, WakeLock, camera probing or repeating timers. */
internal object AwayJournal {
    private val io=Executors.newSingleThreadScheduledExecutor { Thread(it,"away-evidence").apply { isDaemon=true } }
    private val queue=ArrayBlockingQueue<AwayRow>(256)
    private val scheduled=AtomicBoolean()
    private val dropped=AtomicInteger()
    private val rows=ArrayDeque<AwayRow>()
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    @Volatile private var file:AtomicFile?=null
    @Volatile private var observingUntil=0L
    val observing get()=SystemClock.elapsedRealtime()<observingUntil
    private var writeError:String?=null
    private var lastReconcile:AwayRow?=null
    private val names=setOf("PROCESS_STARTED","APP_FOREGROUND","APP_BACKGROUND","SCREEN_ON","SCREEN_OFF",
        "DISPLAY_CHANGED","POWER_DELAYED_SNAPSHOT","POWER_PASSIVE_SNAPSHOT","VEHICLE_AWAY_TEST_MARKER","NATURAL_AWAY_TEST_ENDED_BY_C0",
        "RECORDER_START","RECORDER_STOP","RECORDER_STOPPED","RECORDER_SEGMENT_START","RECORDER_SEGMENT_FINALIZE_BEGIN",AwayReportEvidence.CONTINUOUS_FAILURE,
        AwayReportEvidence.FIRST_CONTINUOUS_FAILURE,
        "RECORDER_CAMERA_RESUME_GATE_ARMED","RECORDER_CAMERA_RESUME_GATE_DISARMED","RECORDER_POWER_SNAPSHOT_RECONCILED",
        "RECORDER_CAMERA_LOSS_ROUTE","RECORDER_CAMERA_LOSS_OBSERVED","RECORDER_CAMERA_CALLBACK",
        "RECORDER_CLOSE_REQUESTED","RECORDER_CLOSE_PROGRESS","RECORDER_CLOSE_UNCONFIRMED","RECORDER_CLOSE_SETTLED",
        "RECORDER_SESSION_TERMINATED","RECORDER_MIRROR_APP_ENTRY","RECORDER_MIRROR_UNAVAILABLE","RECORDER_WAKE_LOCK_ACQUIRED","RECORDER_WAKE_LOCK_RELEASED")
    private val safeKeys=setOf("probeId","processStartId","interactive","displays","appForeground","activity","recorder","source",
        "segment","wakeLock","phase","screenOn","mainDisplayOn","mainDisplayState","mainDisplayId","pendingToken","confirmAtElapsedMs",
        "reason","lastReason","route","authorityReason","finalizeReason","generation","cameraGeneration","decision",
        "backgroundPowerOffEvidence","recordingMode","testType","detail","standalonePreview","floatingControls","captureCallbackAgeMs",
        "cleanupOwners","recordingBackend","elapsed","uptime","remoteLabActive","experimentalC0Active",
        "recordingSessionId","callbackType","cameraErrorCode","errorCode","callbackEntryElapsedMs","staleCallback",
        "closeId","closeStep","stepElapsedMs","closeRequestedElapsedMs","closeDurationMs","safeToContinue","deviceClosed",
        "terminal","outputLost","status","cleanupPending","cleanupUnconfirmed","recoverableContention","deviceOwnerId") +
        com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureEvidence.FACT_KEYS + setOf(
            "controlsSession", "sleepCycle", "windowSession", "sleepPhase", "pid", "overlayAttached", "overlayShown",
            "screenUsable", "captureReleased", "recorderRunning", "previewRunning", "auxiliaryActive", "nativeIdle", "glIdle", "previewOwner", "returnMode",
            "profileAttempt", "profileWaitMs", "profileStatus", "requestedRole")
    fun init(context:Context) {
        if(file!=null)return
        file=AtomicFile(File(context.filesDir,"diagnostics/away-timeline.json"))
        io.execute {
            runCatching { file!!.openRead().use { input ->
                check(input.channel.size()<=3*1024*1024)
                json.decodeFromString<List<AwayRow>>(input.bufferedReader().readText()).takeLast(1200).forEach(rows::addLast)
            } }.onFailure { if(file!!.baseFile.exists())writeError="READ_${it.javaClass.simpleName}" }
        }
    }
    fun observe(e:ProbeEvent) {
        if(file==null || (e.eventName !in names && !e.eventName.startsWith("RECORDER_VEHICLE_AWAY_") && !e.eventName.startsWith("RECORDER_CAMERA_RECOVERY_") &&
            !e.eventName.startsWith("MIRROR_SLEEP_") && !e.eventName.startsWith("MIRROR_PREVIEW_")))return
        val facts=e.payload.filterKeys { it in safeKeys }.mapValues { it.value.take(160) }.toMutableMap()
        facts["errorCode"]?.let { value ->
            facts["errorCode"] = AwayReportEvidence.errorCode(value) ?: "UNKNOWN_ERROR"
        }
        if (e.eventName=="RECORDER_SESSION_TERMINATED") facts["reason"] =
            AwayReportEvidence.errorCode(e.payload["reason"]) ?: "UNKNOWN_TERMINAL_REASON"
        if(e.eventName==AwayReportEvidence.CONTINUOUS_FAILURE && "errorCode" !in facts) {
            AwayReportEvidence.errorCode(e.errorMessage)?.let { facts["errorCode"]=it }
        }
        val row=AwayRow(e.eventName,e.epochMs,e.elapsedRealtimeMs,SystemClock.uptimeMillis(),ZeekrApp.processStartId,facts)
        if(e.eventName=="RECORDER_POWER_SNAPSHOT_RECONCILED") synchronized(this) {
            val keys=setOf("phase","appForeground","screenOn","mainDisplayOn","mainDisplayState","backgroundPowerOffEvidence","generation")
            val old=lastReconcile
            if(old!=null && old.process==row.process && row.elapsed>=old.elapsed && row.elapsed-old.elapsed<60_000 &&
                old.facts.filterKeys{it in keys}==row.facts.filterKeys{it in keys})return
            lastReconcile=row
        }
        if(!queue.offer(row))dropped.incrementAndGet()
        scheduleDrain()
    }
    private fun scheduleDrain() {
        if(scheduled.compareAndSet(false,true))io.schedule({
            drainAndSave();scheduled.set(false);if(queue.isNotEmpty())scheduleDrain()
        },250,TimeUnit.MILLISECONDS)
    }
    fun startMarker() { observingUntil=SystemClock.elapsedRealtime()+4*60*60_000L; VehicleAwayProbe.startTestMarker("NATURAL_AWAY_PASSIVE") }
    fun endForExperiment() {
        observingUntil=0
        com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,"NATURAL_AWAY_TEST_ENDED_BY_C0")
    }
    private fun drainAndSave() {
        while(true) { val e=queue.poll() ?: break; rows.addLast(e); while(rows.size>1200)rows.removeFirst() }
        runCatching {
            val target=file ?: return; target.baseFile.parentFile?.mkdirs()
            val bytes=json.encodeToString(rows.toList()).toByteArray(); check(bytes.size<=3*1024*1024)
            val out=target.startWrite(); try {out.write(bytes);target.finishWrite(out)}catch(t:Throwable){target.failWrite(out);throw t}
        }.onFailure { writeError=it.javaClass.simpleName }
    }
    /** Must be called from an IO thread. Only this export reads the cleanup snapshot. */
    fun report(recorderFault:JsonObject?=null):JsonObject = io.submit<JsonObject> {
        drainAndSave()
        val all=rows.toList(); val marker=all.indexOfLast { it.event=="VEHICLE_AWAY_TEST_MARKER" && it.facts["testType"]=="NATURAL_AWAY_PASSIVE" }
        val fromMarker=if(marker>=0)all.drop(marker) else all
        val boundary=fromMarker.indexOfFirst{it.event=="NATURAL_AWAY_TEST_ENDED_BY_C0"}
        val scoped=if(marker>=0 && boundary>=0)fromMarker.take(boundary) else fromMarker
        val base=obj("schemaVersion" to 1,"format" to "OPENAVM_AWAY_SHORT_JSON","version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,"classification" to AwayTimeline.classify(scoped),
            "markerPresent" to (marker>=0),"endedBeforeC0" to (marker>=0 && boundary>=0),
            "queueDropped" to dropped.get(),"journalWriteError" to writeError,
            "cpuSleepGapMsWithinSameProcess" to AwayTimeline.sleepEvidence(scoped),"physicalLockState" to "UNKNOWN",
            "wholeVehicleSleep" to "NOT_PROVEN","shadow" to AwayTimeline.shadow(scoped),
            "eventCounts" to scoped.groupingBy { it.event }.eachCount(),
            "cleanupNow" to obj("unsettledOwners" to CaptureCleanupRuntime.pendingOwners.value),
            "recordingEvidence" to AwayReportEvidence.recording(scoped),
            "lifecycleEvidence" to AwayReportEvidence.lifecycle(scoped),
            "mirrorSleep" to MirrorSleepEvidence.report(all),
            "recorderFaultInTestWindow" to AwayReportEvidence.faultInWindow(scoped,recorderFault,BuildConfig.VERSION_CODE),
            "videoIncluded" to false)
        AwayReportEvidence.bounded(base,scoped)
    }.get(5,TimeUnit.SECONDS)
    fun cloudReport(recorderFault:JsonObject?=null):JsonObject {
        val data=report(recorderFault)
        return obj("schemaVersion" to 1,"format" to "OPENAVM_PREFLIGHT","exampleOnly" to false,"productionSwitchAllowed" to false,
            "runId" to UUID.randomUUID().toString(),"version" to BuildConfig.VERSION_NAME,"versionCode" to BuildConfig.VERSION_CODE,
            "startEpochMs" to System.currentTimeMillis(),"phase" to "NATURAL_AWAY_OBSERVATION","tests" to emptyList<String>(),"away" to data)
    }
}
