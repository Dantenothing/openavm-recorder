package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.preflight.*
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.SecureRandom
import com.dante.zeekrcapabilitylab.preflight.remote.LabExperiment
import com.dante.zeekrcapabilitylab.preflight.remote.payloadHash

/** Diagnostic suites only. Neither source route opens a camera or selects production recording. */
@androidx.annotation.RequiresApi(29)
internal class ProbeBasicRunner(private val context:Context,private val target:UsbExportTarget,
    private val run:String,private val store:PreflightStore,private val cancelled:()->Boolean,
    private val emit:(JsonObject)->Unit, private val sharedInput: Boolean = false,
    private val experiment:LabExperiment? = null) {
    private val data=linkedMapOf<String,JsonElement>()
    private val checkpoints=ArrayDeque<JsonObject>()
    private var gl:ProbeGlProducer?=null
    private var encoder:ProbeContinuousEncoder?=null
    private val outputs=ArrayList<UsbMediaStoreRecordingOutputHandle>()
    private val retainedDescriptors=ArrayList<android.os.ParcelFileDescriptor>()
    private val backend=UsbMediaStoreBackend(context)
    private val retained=ProbeRetainedDiagnostics(context,target,run,store,cancelled)
    private var nativeClean=true
    private var suiteName:String?=null
    private fun checkpoint(stage:String) {
        check(!cancelled()) { "TEST_CANCELLED" }
        store.checkpoint(run,"p1:$stage")
        val event=obj("stage" to stage,"atElapsedMs" to SystemClock.elapsedRealtime())
        checkpoints+=event; if(checkpoints.size>48)checkpoints.removeFirst()
        emit(obj("event" to "P1_STAGE","stage" to stage))
    }
    fun run():JsonObject {
        try {
            data["retainedDiagnostics"]=retained.reconcile()
            retained.ensureRoom()
            if(experiment!=null) {
                check(!experiment.reference) { "REFERENCE_MUST_USE_UNMODIFIED_SUITE" }
                data["experiment"]=obj("configuration" to experiment.json(),"configurationHash" to payloadHash(experiment.json()),
                    "referenceAcceptance" to false,"frameRate" to 30,"files" to 4)
                val name=if(experiment.health)"health" else if(experiment.high)"stress" else "full"
                val suite=execute(!experiment.health,experiment.high)
                data["${name}Raster"]=suite
                val passed=suite["sequenceStatus"]==JsonPrimitive("PASS") && suite["fileCleanupStatus"]==JsonPrimitive("PASS") &&
                    suite["realtimeStatus"]==JsonPrimitive("PASS") && (!sharedInput ||
                        (data["${name}Input"]?.jsonObject?.get("status")==JsonPrimitive("PASS") && suite["encodedLoadStatus"]==JsonPrimitive("PASS")))
                return result(if(passed)"PASS" else "FAIL",if(passed)null else "EXPERIMENT_REVIEW_REQUIRED")
            }
            val health=execute(false)
            data["health"]=health
            if(health["sequenceStatus"]!=JsonPrimitive("PASS")) return result("FAIL","HEALTH_SEQUENCE_FAILED")
            data["fullRaster"]=execute(true)
            val full=data.getValue("fullRaster").jsonObject
            if(full["sequenceStatus"]!=JsonPrimitive("PASS"))return result("FAIL","FULL_SEQUENCE_FAILED")
            val stress=if(sharedInput)execute(true,stress=true).also { data["stressRaster"]=it } else null
            val suites=listOfNotNull(health,full,stress)
            val retainedNow = suites.sumOf { it.getValue("retainedSyntheticFiles").jsonPrimitive.int +
                it.getValue("journalRemovalFailures").jsonPrimitive.int }
            val pressurePassed = !sharedInput || suites.all { it["encodedLoadStatus"] == JsonPrimitive("PASS") }
            val inputPassed = !sharedInput || listOf("healthInput", "fullInput", "stressInput").all { data[it]?.jsonObject?.get("status") == JsonPrimitive("PASS") }
            val status = ProbeCompletion.status(suites.all { it["sequenceStatus"] == JsonPrimitive("PASS") } && inputPassed,
                suites.all { it["realtimeStatus"] == JsonPrimitive("PASS") }, retainedNow, pressurePassed)
            return result(status, if (!inputPassed) "SHARED_INPUT_COVERAGE_INCOMPLETE" else if (retainedNow > 0) "SYNTHETIC_FILES_RETAINED"
                else if (!pressurePassed) "ENCODED_LOAD_GROUP_REVIEW" else if (status == "WARN") "SUBMISSION_TIMING_REVIEW" else null)
        } catch(t:Throwable) {
            if(t is PreflightCleanupUnconfirmed) nativeClean=false
            suiteName?.let { name -> data.putIfAbsent("${name}FailureSnapshot",obj("reason" to code(t),
                "atElapsedMs" to SystemClock.elapsedRealtime(),"stage" to checkpoints.lastOrNull()?.get("stage"),
                "state" to null,"note" to "QUERY_DECODE_OR_FILE_HANDLING_SEE_FINAL_ENCODER_SNAPSHOT_SEPARATELY")) }
            data["failure"]=obj("type" to t.javaClass.simpleName,"reason" to code(t),
                "cause" to t.cause?.let(::code),"cleanupConfirmed" to nativeClean)
            if(t is PreflightCleanupUnconfirmed || !nativeClean) {
                synchronized(PreflightRuntime.retained) { PreflightRuntime.retained+=this }
                runCatching { store.write("p1-last-detail.json",result("INCOMPLETE","CLEANUP_UNCONFIRMED")) }
                throw PreflightCleanupUnconfirmed()
            }
            return result(if(cancelled()) "CANCELLED" else "FAIL",code(t))
        }
    }
    private fun execute(full:Boolean,stress:Boolean=false):JsonObject {
        val name=if(stress)"stress" else if(full)"full" else "health"
        suiteName=name
        retained.ensureRoom()
        val nonce=SecureRandom().nextInt(65536)
        val frames=if(full)600 else 120
        val every=frames/4
        // This ledger is fixed BEFORE source generation. Failed submissions never renumber it.
        val planned=LongArray(frames) { it*1_000_000L/30 }
        val endPts=frames*1_000_000L/30
        val progress=ProbeSegmentProgress((1..3).map { planned[every*it] })
        data["${name}Plan"]=obj("nonce" to nonce,"firstId" to 0,"frames" to frames,"ptsFormula" to "floor(id*1000000/30)",
            "ptsSha256" to sha(planned.joinToString(",").toByteArray()),"files" to 4,"endPtsUs" to endPts,
            "loadGroup" to if(!sharedInput)"BASIC" else if(stress)"HIGH" else "TARGET",
            "terminalTailPolicy" to "ACTUAL_INPUT_COUNT_AT_30_FPS_PLANNED_END_ONLY_WHEN_COMPLETE")
        checkpoint("${name}_QUERY")
        val specs=ProbeExperimentCandidates.specs(full,sharedInput,stress,experiment)
        val declared=specs.flatMap { spec -> ProbeCodecFormat.declarations(spec.encoding).map { spec to it } }
        data["${name}Declarations"]=j(declared.map { (spec,d) -> obj("codec" to d.codecName,"format" to ProbeCodecFormat.evidence(spec.encoding),
            "formatSupported" to d.formatSupported,"surfaceInput" to d.surfaceInput,"sizeAndRate" to d.sizeAndRateSupported,
            "bitrateModeSupported" to d.bitrateModeSupported,"rateControlCapabilities" to d.rateControlCapabilities,"error" to d.queryError) })
        val selected=declared.firstOrNull { it.second.accepts(it.first.encoding) } ?: error("NO_DECLARED_AVC_CANDIDATE")
        val spec=selected.first
        data["${name}Selected"]=obj("codec" to selected.second.codecName,"request" to ProbeCodecFormat.evidence(spec.encoding),
            "queryAndConfigureIdentical" to true,"runtimeFallback" to false)
        val namespace="p1-$run-$name"
        val journal=UsbRecordingRecoveryJournal(context,namespace)
        val sink=UsbMediaStoreRecordingOutputSink(context,target,namespace,RecordingMode.NORMAL,
            File(context.filesDir,"preflight/tokens-$namespace"),journal) { descriptor ->
                retainedDescriptors+=descriptor; nativeClean=false; throw PreflightCleanupUnconfirmed()
            }
        fun output(index:Int):UsbMediaStoreRecordingOutputHandle {
            checkpoint("${name}_FILE_${index}_OPEN_INTENT")
            progress.openStarted(index,SystemClock.elapsedRealtime())
            val fresh=UsbExportVolumeResolver.resolveExact(context,target) ?: error("USB_REMOVED")
            check(PreflightPlan.budgetReason(0,0,192L*1024*1024,fresh.freeBytes)==null) { "USB_FREE_SPACE_LOW" }
            val handle=sink.openSegment(index+1,CameraFormatProfile(ProfileSize(spec.layout.encoded.width,spec.layout.encoded.height),
                spec.encoding.bitrateBps),System.currentTimeMillis()) as UsbMediaStoreRecordingOutputHandle
            outputs+=handle
            store.write("owned-$namespace.json",obj("run" to run,"owned" to outputs.map { obj("uri" to it.pendingVideo.itemUri,
                "operation" to it.pendingVideo.operationId) }))
            progress.openFinished(index,SystemClock.elapsedRealtime())
            return handle
        }
        fun metadata(index:Int,base:Long):JsonObject = obj("schemaVersion" to 1,"kind" to "OPENAVM_SYNTHETIC_LAYOUT",
            "run" to run,"nonce" to nonce,"fileIndex" to index,"runBasePtsUs" to base,"coordinateOrigin" to "TOP_LEFT",
            "inputWidth" to spec.layout.input.width,"inputHeight" to spec.layout.input.height,
            "encodedWidth" to spec.layout.encoded.width,"encodedHeight" to spec.layout.encoded.height,"stripHeight" to spec.layout.stripHeight,
            "rule" to "column=floor(sourceY/stripHeight); x=sourceX+column*inputWidth; y=sourceY%stripHeight",
            "padding" to "sourceY>=inputHeight is black","viewSemantics" to "SYNTHETIC_REGIONS_0_1_2_NOT_VEHICLE_CAMERAS",
            "sourceColor" to "SYNTHETIC_RGB","vehicleColor" to "UNKNOWN")
        var geometry:JsonObject?=null
        var files:List<ProbeContinuousEncoder.Closed>?=null
        var attempts=0; var submitted=0
        var sourceStartNs=0L; var filesClosedNs=0L
        val submitTimes=ArrayList<Long>(); val swapTimes=ArrayList<Long>()
        var failure:Throwable?=null
        encoder=null
        checkpoint("${name}_GL_ALLOCATE")
        val graphics: ProbeGlProducer = (if (sharedInput) ProbeSharedInputGl(spec.layout,nonce,frames,cancelled,
            onEncoderSubmission={ pts -> progress.input(pts,SystemClock.elapsedRealtime()) })
            else ProbeSyntheticGl(spec.layout,nonce)).also { gl=it }
        try {
            graphics.initialize(); geometry=graphics.geometry(); data["${name}Geometry"]=geometry
            checkpoint("${name}_CODEC_CONFIGURE")
            val active=ProbeContinuousEncoder(selected.second.codecName,spec.encoding,output(0),progress) { i,b -> metadata(i,b).toString().toByteArray() }
                .also { encoder=it }
            check(!cancelled()) { "TEST_CANCELLED" }
            active.start(); graphics.attach(active.surface)
            active.prepareNext(1,output(1))
            checkpoint("${name}_PACED_ENCODING")
            var queued=1; var armed=0
            val start=SystemClock.elapsedRealtimeNanos()
            var nextStart=start
            for(id in 0 until frames) {
                check(!cancelled()) { "TEST_CANCELLED" }; active.checkHealthy()
                if(armed<queued && active.preparedIndex()==queued) { active.armCut(queued,planned[every*queued]); armed=queued }
                val targetNs=maxOf(start+planned[id]*1000,nextStart)
                while(SystemClock.elapsedRealtimeNanos()<targetNs) {
                    check(!cancelled()) { "TEST_CANCELLED" }; Thread.sleep(1)
                }
                val before=SystemClock.elapsedRealtimeNanos()
                if(attempts==0)sourceStartNs=before
                attempts++; graphics.submit(id,planned[id]); submitted++
                progress.source(planned[id])
                if(!sharedInput)active.inputSubmitted(planned[id])
                if(id%30==0)emit(obj("event" to "P1_PROGRESS","stage" to "${name}_PACED_ENCODING",
                    "statistics" to JsonObject(active.snapshot().json()+obj("encodedPayloadBytes" to active.encodedBytes,
                        "completedFiles" to active.completedFiles))))
                submitTimes+=before-start; swapTimes+=SystemClock.elapsedRealtimeNanos()-before
                // Never burst late frames to conceal poor real-time performance.
                nextStart=before+1_000_000_000L/30
                if(active.completedFiles>=queued && queued<3) {
                    queued++; active.prepareNext(queued,output(queued))
                }
                active.checkHealthy()
            }
        } catch(t:Throwable) {
            failure=t; encoder?.recordFailure(t)
            data["${name}FailureSnapshot"]=encoder?.faultSnapshot() ?: obj("reason" to code(t),"state" to progress.snapshot(SystemClock.elapsedRealtime()).json())
        }
        finally {
            // No codec/input release before confirmed GL producer end, including partial startup.
            try { graphics.endProducer() } catch(t:Throwable) { nativeClean=false; if(failure==null)failure=t }
            if(graphics.producerConfirmed) {
                encoder?.let { active ->
                    val actualEnd=progress.endPtsUs()
                    data["${name}ActualEndPtsUs"]=j(actualEnd)
                    try { files=active.finish(actualEnd,submitted==frames && failure==null); filesClosedNs=SystemClock.elapsedRealtimeNanos() }
                    catch(t:Throwable) { if(failure==null)failure=t; active.recordFailure(t) }
                    if(!active.cleanupConfirmed())nativeClean=false
                    data["${name}Encoder"]=obj("encodedFrames" to active.encodedFrames,"encodedBytes" to active.encodedBytes,
                        "closedFiles" to active.completedFiles,"codecEosSignals" to active.eosSignals,
                        "formatFingerprint" to active.formatFingerprint,"maxQueueBytes" to active.maximumQueueBytes,
                        "maxQueueItems" to active.maximumQueueItems,"nativeFailure" to active.failureEvidence(),
                        "formatObservation" to active.formatObservation())
                    active.faultSnapshot()?.let { data["${name}FailureSnapshot"]=it }
                    data["${name}FinalSnapshot"]=active.snapshot().json()
                }
                try { graphics.close() } catch(t:Throwable) { nativeClean=false; if(failure==null)failure=t }
            }
            if(nativeClean) for(handle in outputs) {
                try { handle.close() } catch(t:Throwable) {
                    // A sync failure invalidates durability; retry close to distinguish it from an owned FD.
                    if(failure==null)failure=t
                    try { handle.abandonUnavailableTarget() } catch(_:Throwable) { nativeClean=false }
                }
            }
            val gaps=submitTimes.zipWithNext { a,b -> b-a }
            if(sharedInput)data["${name}Input"]=graphics.evidence()
            if(sharedInput)data["${name}EncodedLoad"]=ProbeLoadEvidence.measure(
                if(stress)ProbeLoadEvidence.Group.HIGH else ProbeLoadEvidence.Group.TARGET,spec.encoding.bitrateBps,
                encoder?.encodedBytes ?: 0,progress.endPtsUs(),
                if(sourceStartNs>0 && filesClosedNs>sourceStartNs)filesClosedNs-sourceStartNs else null,
                submitted==frames && files?.size==4 && failure==null && nativeClean)
            val actualFps=if(submitTimes.size>1)(submitTimes.size-1)*1e9/(submitTimes.last()-submitTimes.first()) else 0.0
            data["${name}Timing"]=obj("plannedFrames" to frames,"sourceAttempts" to attempts,"gpuSubmitted" to submitted,
                "actualFps" to actualFps,"maxSubmitGapMs" to (gaps.maxOrNull()?.div(1e6)),"maxSwapMs" to (swapTimes.maxOrNull()?.div(1e6)),
                "lateFramesOver50ms" to submitTimes.withIndex().count { it.value-planned[it.index]*1000>50_000_000 },
                "lateFramesMeaning" to "CUMULATIVE_OFFSET_FROM_IDEAL_SCHEDULE_NOT_INDIVIDUAL_STALLS",
                "submitGapsOver50ms" to gaps.count { it > 50_000_000 },
                "submitElapsedNs" to submitTimes,"pacing" to "MONOTONIC_NO_CATCHUP_BURSTS",
                "realtimeStatus" to if(submitted==frames && actualFps>=29.0 && (gaps.maxOrNull() ?: Long.MAX_VALUE)<=100_000_000L) "PASS" else "WARN")
        }
        data.putIfAbsent("${name}FinalSnapshot",progress.snapshot(SystemClock.elapsedRealtime()).json())
        store.write("proof-$namespace.json",obj("run" to run,"suite" to name,"nativeCleanup" to if(nativeClean)"CONFIRMED" else "UNCONFIRMED",
            "sequenceStatus" to "NOT_RUN","files" to (files?.size ?: 0),"failure" to failure?.let(::code)))
        if(!nativeClean)throw PreflightCleanupUnconfirmed()
        failure?.let { throw it }
        check(submitted==frames && files?.size==4) { "ENCODING_MANIFEST_INCOMPLETE" }
        encoder=null; gl=null
        checkpoint("${name}_COLD_FILE_DECODE")
        val verifier=SyntheticSegmentVerifier(planned,4,33_334,ptsToleranceUs=20)
        val decoded=ArrayList<JsonObject>()
        for(file in requireNotNull(files)) {
            checkpoint("${name}_DECODE_FILE_${file.index}")
            val handle=file.output as UsbMediaStoreRecordingOutputHandle
            decoded+=ProbeFileDecoder(context).inspect(Uri.parse(handle.pendingVideo.itemUri),file,metadata(file.index,file.baseUs),
                spec,nonce,verifier,cancelled)
            data["${name}DecodedFiles"]=j(decoded)
        }
        verifier.finishRun(); val sequence=verifier.result()
        data["${name}Sequence"]=obj("status" to sequence.status.name,"scope" to sequence.scope,"frames" to sequence.decodedFrames,
            "files" to sequence.completedSegments,"maximumBoundaryGapUs" to sequence.maximumBoundaryGapUs,
            "issues" to sequence.issues.map { it.name })
        store.write("proof-$namespace.json",obj("run" to run,"suite" to name,"nativeCleanup" to "CONFIRMED",
            "sequenceStatus" to sequence.status.name,"files" to sequence.completedSegments,"frames" to sequence.decodedFrames))
        // Delete only this suite's exact, owned, closed synthetic files after validation; never user videos.
        var deleted=0
        var journalFailures=0
        val cleanup = ArrayList<JsonObject>()
        if(sequence.status==SyntheticSequenceStatus.PASS) for(file in requireNotNull(files)) {
            val handle=file.output as UsbMediaStoreRecordingOutputHandle
            // Exact creation-time identity, never values learned from the current candidate row.
            // Backend validates exact URI, volume, directory, name, MIME and original/app owner.
            val pending = handle.pendingVideo
            val asset = UsbExportAsset(UsbExportAssetKind.VIDEO, requestedName=pending.displayName,
                expectedBytes=0,expectedSha256="",expectedMimeType="video/mp4",itemUri=pending.itemUri,
                observedOwnerPackage=pending.observedOwnerPackage)
            val deletion = runCatching { backend.deleteOwned(target,asset) }
            val removed = deletion.getOrNull()?.deletionConfirmed == true
            val committed = removed && runCatching { handle.markCommitted() }.isSuccess
            if(removed)deleted++
            if(removed && !committed)journalFailures++
            cleanup += obj("file" to file.index,"deletionConfirmed" to removed,"journalRemovalConfirmed" to committed,
                "reason" to if(committed)null else if(removed)"JOURNAL_REMOVE_FAILED" else ProbeCompletion.deletionReason(deletion.getOrNull()?.error),
                "failureType" to deletion.exceptionOrNull()?.javaClass?.simpleName)
        }
        data["${name}FileCleanup"]=j(cleanup)
        val loadPassed=data["${name}EncodedLoad"]?.jsonObject?.get("status")==JsonPrimitive("PASS")
        return obj("sequenceStatus" to sequence.status.name,"geometry" to geometry,
            "realtimeStatus" to data.getValue("${name}Timing").jsonObject["realtimeStatus"],
            "files" to 4,"deletedSyntheticFiles" to deleted,"retainedSyntheticFiles" to 4-deleted,
            "journalRemovalFailures" to journalFailures,
            "fileCleanupStatus" to if(deleted==4 && journalFailures==0)"PASS" else "WARN",
            "encodedLoadStatus" to if(!sharedInput)"NOT_RUN" else if(loadPassed)"PASS" else "WARN",
            "actualRoute" to "USB_MEDIASTORE","cameraOpened" to false)
    }
    private fun result(status:String,reason:String?):JsonObject {
        if(sharedInput)data["loadGroups"]=obj("target" to groupSummary("full"),"high" to groupSummary("stress"))
        return JsonObject(obj("runId" to run,"status" to status,"reason" to reason,
        "scope" to if(sharedInput)"P1_INPUT_SYNTHETIC_OES_SHARED_OFFSCREEN" else "P1_BASIC_SYNTHETIC_DIRECT_GL",
        "cameraOpened" to false,"nativeCleanup" to if(nativeClean)"CONFIRMED" else "UNCONFIRMED",
        "oesInput" to if(sharedInput)"SEE_PER_SUITE_INPUT_EVIDENCE" else "NOT_IMPLEMENTED",
        "sharedPoolPressure" to if(sharedInput)"SEE_PER_SUITE_INPUT_EVIDENCE" else "NOT_IMPLEMENTED","realCamera" to "NOT_IMPLEMENTED",
        "productionGaplessRecording" to "NOT_CLAIMED","checkpoints" to checkpoints.toList())+data)
    }
    private fun groupSummary(name:String):JsonObject = obj("status" to when {
        data["${name}FailureSnapshot"]!=null -> "FAIL"
        data["${name}Plan"]==null -> "NOT_RUN"
        data["${name}Sequence"]==null -> "INCOMPLETE"
        data["${name}Sequence"]?.jsonObject?.get("status")!=JsonPrimitive("PASS") -> "FAIL"
        data["${name}Input"]?.jsonObject?.get("status")!=JsonPrimitive("PASS") -> "INCOMPLETE"
        data["${name}EncodedLoad"]?.jsonObject?.get("status")!=JsonPrimitive("PASS") -> "WARN"
        data["${name}Raster"]?.jsonObject?.get("fileCleanupStatus")!=JsonPrimitive("PASS") -> "WARN"
        data["${name}Timing"]?.jsonObject?.get("realtimeStatus")!=JsonPrimitive("PASS") -> "WARN"
        else -> "PASS"
    },"encoding" to data["${name}Selected"],"load" to data["${name}EncodedLoad"],
        "sequence" to data["${name}Sequence"],"failureSnapshot" to data["${name}FailureSnapshot"],
        "finalSnapshot" to data["${name}FinalSnapshot"],"files" to data["${name}Raster"])
    private fun code(t:Throwable):String = t.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) } ?: t.javaClass.simpleName
}
