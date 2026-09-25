package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.preflight.*
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.SecureRandom

/** P2: real source, actual windows, unchanged continuous codec/writer, independent closed-file proof. */
@androidx.annotation.RequiresApi(30)
internal class CameraProbeRunner(private val context:Context,private val target:UsbExportTarget,
    private val run:String,private val store:PreflightStore,private val plan:CameraProbePlan,
    private val cancelled:()->Boolean,private val emit:(JsonObject)->Unit) {
    private val data=linkedMapOf<String,JsonElement>()
    private var graphics:CameraSharedInputGl?=null
    private var camera:CameraProbeSource?=null
    private var encoder:ProbeContinuousEncoder?=null
    private var clean=true
    private val outputs=ArrayList<UsbMediaStoreRecordingOutputHandle>()
    private val retainedDescriptors=ArrayList<android.os.ParcelFileDescriptor>()
    private val preparation=CameraFilePreparation<UsbMediaStoreRecordingOutputHandle>()
    private val namespace="p2-$run-camera"
    private fun stage(value:String) {
        check(!cancelled()) {"TEST_CANCELLED"};store.checkpoint(run,"p2:$value")
        emit(obj("event" to "P2_STAGE","stage" to value))
    }
    fun run():JsonObject {
        try {return execute()}
        catch(t:Throwable) {
            if(t is PreflightCleanupUnconfirmed)clean=false
            data["failure"]=obj("type" to t.javaClass.simpleName,"reason" to code(t))
            val report=result(if(cancelled())"CANCELLED" else "FAIL",code(t))
            runCatching {store.write("p2-last-detail.json",report)}
            if(!clean) {synchronized(PreflightRuntime.retained){PreflightRuntime.retained+=this};throw PreflightCleanupUnconfirmed()}
            return report
        }
    }
    private fun execute():JsonObject {
        stage("camera_QUERY")
        val retained=ProbeRetainedDiagnostics(context,target,run,store,cancelled)
        data["retainedDiagnostics"]=retained.reconcile();retained.ensureRoom()
        check(plan.spaceReason(target.freeBytes)==null) {plan.spaceReason(target.freeBytes).orEmpty()}
        val cfg=ProductRecorderConfigFactory.create(context,RecordingSourceRole.SURROUND) ?: error("SURROUND_SOURCE_UNRESOLVED")
        check(cfg.profile.size==ProfileSize(1280,5140)) {"P2_NATIVE_SOURCE_SIZE_REQUIRED"}
        val spec=ContinuousProbeSpec.surroundRepack(ProbeAvcProfile.HIGH).let {it.copy(encoding=it.encoding.copy(bitrateMode=ProbeBitrateMode.VBR))}
        val declarations=ProbeCodecFormat.declarations(spec.encoding)
        data["declarations"]=j(declarations.map {obj("codec" to it.codecName,"formatSupported" to it.formatSupported,
            "sizeAndRateSupported" to it.sizeAndRateSupported,"bitrateModeSupported" to it.bitrateModeSupported,"error" to it.queryError)})
        val selected=declarations.firstOrNull {it.accepts(spec.encoding)} ?: error("P2_NO_DECLARED_CODEC")
        val nonce=SecureRandom().nextInt(65536)
        val progress=ProbeSegmentProgress(plan.cutTargetsUs)
        data["plan"]=obj("seconds" to plan.seconds,"cutsUs" to plan.cutTargetsUs,"files" to 4,"frameRate" to 30,
            "sourceWidth" to 1280,"sourceHeight" to 5140,"encodedWidth" to 3840,"encodedHeight" to 1728,
            "encodedByteBudget" to plan.encodedByteBudget,"minimumFreeBytes" to plan.minimumFreeBytes,
            "bitrateAcceptance" to "OBSERVE_ONLY_PER_USER_DECISION_2026_09_20","windowScript" to plan.windowScript,
            "sourceTimestampPolicy" to "DISTINCT_OES_TIMESTAMPS_NORMALIZED_TO_FIRST_ACQUIRED_FRAME",
            "imageIds" to "DIAGNOSTIC_BARCODE_STAMP_AFTER_DISTINCT_CAMERA_ACQUISITION",
            "doesNotProve" to "PIXEL_CHANGE_OR_ALL_HAL_FRAMES_USE_SEPARATE_CAPTURE_EVIDENCE",
            "codec" to selected.codecName,"request" to ProbeCodecFormat.evidence(spec.encoding))
        val sink=UsbMediaStoreRecordingOutputSink(context,target,namespace,RecordingMode.NORMAL,
            File(context.filesDir,"preflight/tokens-$namespace"),UsbRecordingRecoveryJournal(context,namespace)) {fd->
                retainedDescriptors+=fd;clean=false;throw PreflightCleanupUnconfirmed()
            }
        fun open(index:Int):UsbMediaStoreRecordingOutputHandle {
            stage("camera_FILE_${index}_OPEN_INTENT");progress.openStarted(index,SystemClock.elapsedRealtime())
            val fresh=UsbExportVolumeResolver.resolveExact(context,target) ?: error("USB_REMOVED")
            val remaining=(plan.encodedByteBudget-(encoder?.encodedBytes ?: 0L)).coerceAtLeast(0L)
            check((fresh.freeBytes ?: 0)>=1024L*1024*1024+remaining*2) {"P2_USB_FREE_SPACE_LOW"}
            val handle=sink.openSegment(index+1,CameraFormatProfile(ProfileSize(3840,1728),28_000_000),System.currentTimeMillis()) as UsbMediaStoreRecordingOutputHandle
            outputs+=handle
            store.write("owned-$namespace.json",obj("run" to run,"owned" to outputs.map {obj("uri" to it.pendingVideo.itemUri,"operation" to it.pendingVideo.operationId)}))
            progress.openFinished(index,SystemClock.elapsedRealtime());return handle
        }
        fun metadata(index:Int,base:Long)=obj("schemaVersion" to 1,"kind" to "OPENAVM_CAMERA_DIAGNOSTIC_LAYOUT",
            "run" to run,"nonce" to nonce,"fileIndex" to index,"runBasePtsUs" to base,"inputWidth" to 1280,"inputHeight" to 5140,
            "encodedWidth" to 3840,"encodedHeight" to 1728,"stripHeight" to 1728,"coordinateOrigin" to "TOP_LEFT",
            "rule" to "column=floor(sourceY/1728); x=sourceX+column*1280; y=sourceY%1728",
            "padding" to "sourceY>=5140 is black","role" to "SURROUND","diagnosticMarkerBands" to true,
            "productionPlayerCompatible" to false)
        var fault:Throwable?=null
        var files:List<ProbeContinuousEncoder.Closed>?=null
        var durationReached=false
        var windowStarted=false
        val gl=CameraSharedInputGl(context,spec.layout,nonce,plan.maxFrames,cancelled) {pts->progress.input(pts,SystemClock.elapsedRealtime())}.also {graphics=it}
        val source=CameraProbeSource(context,cfg.cameraId).also {camera=it}
        try {
            stage("camera_GL_ALLOCATE");gl.initialize()
            CameraProbeWindows.begin(context);windowStarted=true
            stage("camera_CODEC_CONFIGURE")
            val codec=ProbeContinuousEncoder(selected.codecName,spec.encoding,open(0),progress,plan.encodedByteBudget,p2FileLifecycle=true) {i,b->metadata(i,b).toString().toByteArray()}.also {encoder=it}
            codec.start();gl.attach(codec.surface);codec.prepareNext(1,open(1))
            stage("camera_OPEN");source.start(gl.cameraSurface)
            stage("camera_LIVE")
            var queued=1;var armed=0
            var lastFrameAt=SystemClock.elapsedRealtime()
            val start=lastFrameAt;var nextSample=start;var detachedAt=0L;var rejoined=false
            var detachedSource:Int?=null;var detachedEncoder:Int?=null
            val power=context.getSystemService(PowerManager::class.java)
            while(!durationReached) {
                check(!cancelled()) {"TEST_CANCELLED"};source.checkHealthy();codec.checkHealthy();gl.checkHealthy()
                val now=SystemClock.elapsedRealtime()
                check(now-start<plan.seconds*1000L+15_000) {"P2_CAPTURE_DURATION_TIMEOUT"}
                if(armed<queued && codec.preparedIndex()==queued) {codec.armCut(queued,plan.cutTargetsUs[queued-1]);armed=queued}
                preparation.poll()?.let {codec.prepareNext(queued,it)}
                if(gl.acquire()) {lastFrameAt=now;progress.source(gl.lastPtsUs)}
                else {check(now-lastFrameAt<2_000) {"P2_CAMERA_INPUT_STALLED"};Thread.sleep(2)}
                if(codec.completedFiles>=queued && queued<3 && !preparation.busy) {queued++;val next=queued;preparation.start {open(next)}}
                if(plan.windowScript && detachedAt==0L && gl.lastPtsUs>=plan.durationUs/3) {
                    CameraProbeWindows.requestMirror(context,false);detachedAt=now
                    emit(obj("event" to "P2_WINDOW_DETACH_REQUESTED","statistics" to codec.snapshot().json()))
                } else if(detachedAt>0 && !rejoined && now-detachedAt>=1_200) {
                    CameraProbeWindows.requestMirror(context,true);rejoined=true
                    emit(obj("event" to "P2_WINDOW_REJOIN_REQUESTED","statistics" to codec.snapshot().json()))
                }
                if(CameraProbeWindows.detachCount==1 && detachedSource==null) {
                    detachedSource=gl.frameCount;detachedEncoder=codec.encodedFrames
                    emit(obj("event" to "P2_WINDOW_DETACHED","stage" to "camera_WINDOW_DETACHED","statistics" to codec.snapshot().json()))
                }
                if(CameraProbeWindows.rejoinCount==1 && "windowScript" !in data) {
                    val sourceAdvance=gl.frameCount-(detachedSource ?: gl.frameCount)
                    val encoderAdvance=codec.encodedFrames-(detachedEncoder ?: codec.encodedFrames)
                    data["windowScript"]=obj("sourceProgressWhileDetached" to sourceAdvance,"encodedProgressWhileDetached" to encoderAdvance,
                        "status" to if(sourceAdvance>=3 && encoderAdvance>=3)"PASS" else "FAIL")
                    emit(obj("event" to "P2_WINDOW_REJOINED","stage" to "camera_WINDOW_REJOINED","statistics" to codec.snapshot().json()))
                }
                if(now>=nextSample) {
                    check(power.isInteractive && PreflightActivity.visible) {"PAGE_HIDDEN_OR_SCREEN_OFF"}
                    check(power.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE) {"THERMAL_SEVERE"}
                    check(UsbExportVolumeResolver.isRemovableVolumeMounted(context,target.storageUuid)) {"USB_REMOVED"}
                    emit(obj("event" to "P2_PROGRESS","stage" to "camera_LIVE","statistics" to JsonObject(codec.snapshot().json()+obj(
                        "encodedPayloadBytes" to codec.encodedBytes,"completedFiles" to codec.completedFiles,
                        "finalizers" to codec.finalizationEvidence(),
                        "windows" to CameraProbeWindows.evidence(live=true)))));nextSample=now+5_000
                }
                durationReached=gl.lastPtsUs>=plan.durationUs-33_334L
            }
            data["windowsAtLastSource"]=CameraProbeWindows.evidence(live=true)
            stage("camera_STOP_PRODUCER")
        } catch(t:Throwable) {fault=t;encoder?.recordFailure(t);data["failureSnapshot"]=encoder?.faultSnapshot() ?: progress.snapshot(SystemClock.elapsedRealtime()).json()}
        finally {
            try {source.close()}catch(t:Throwable){clean=false;if(fault==null)fault=t}
            if(source.cleanupConfirmed) {
                try {gl.endProducer(true)}catch(t:Throwable){clean=false;if(fault==null)fault=t}
            }
            if(!preparation.finish())clean=false
            if(gl.producerConfirmed) {
                encoder?.let {codec->
                    try {files=codec.finish(gl.actualEncodedEndPtsUs(),durationReached && fault==null)}catch(t:Throwable){if(fault==null)fault=t;codec.recordFailure(t)}
                    if(!codec.cleanupConfirmed())clean=false
                    data["encoder"]=obj("encodedFrames" to codec.encodedFrames,"encodedBytes" to codec.encodedBytes,"codecEosSignals" to codec.eosSignals,
                        "closedFiles" to codec.completedFiles,"maxQueueBytes" to codec.maximumQueueBytes,"maxQueueItems" to codec.maximumQueueItems,
                        "formatObservation" to codec.formatObservation(),"nativeFailure" to codec.failureEvidence())
                    data["finalSnapshot"]=codec.snapshot().json();codec.faultSnapshot()?.let {data["failureSnapshot"]=it}
                    codec.finalizationEvidence()?.let {data["finalizers"]=it}
                    data["sourcePtsUs"]=j(gl.pts().toList());data["codecPtsUs"]=j(codec.codecPts().toList())
                }
                try {gl.close()}catch(t:Throwable){clean=false;if(fault==null)fault=t}
            }
            // Also runs after partial window construction. Native targets retain their own EGL owners.
            try {CameraProbeWindows.end()}catch(t:Throwable){clean=false;if(fault==null)fault=t}
            if(!CameraProbeWindows.readersReleased())clean=false
            if(clean)outputs.forEach {handle->try {handle.close()}catch(t:Throwable){if(fault==null)fault=t;try{handle.abandonUnavailableTarget()}catch(_:Throwable){clean=false}}}
            data["input"]=gl.evidence();data["camera"]=source.evidence(gl.sourceTimes());data["windows"]=CameraProbeWindows.evidence()
            data["runtime"]=obj("durationReached" to durationReached,"windowStarted" to windowStarted,"acquiredFrames" to gl.frameCount,
                "lastSourcePtsUs" to gl.lastPtsUs,"nativeCleanup" to if(clean)"CONFIRMED" else "UNCONFIRMED",
                "actualPayloadMbps" to if(gl.lastPtsUs>0)(encoder?.encodedBytes ?: 0)*8.0/gl.actualEncodedEndPtsUs() else null,
                "bitrateIsAcceptanceGate" to false)
        }
        store.write("proof-$namespace.json",obj("run" to run,"suite" to "camera","kind" to "OPENAVM_CAMERA_PROOF",
            "nativeCleanup" to if(clean)"CONFIRMED" else "UNCONFIRMED","sequenceStatus" to "NOT_RUN","failure" to fault?.let(::code)))
        if(!clean)throw PreflightCleanupUnconfirmed()
        fault?.let {throw it}
        check(durationReached && files?.size==4) {"P2_FILE_MANIFEST_INCOMPLETE"}
        stage("camera_COLD_FILE_DECODE")
        val sourcePts=gl.pts();val codecPts=requireNotNull(encoder).codecPts()
        val codecPtsMatch=sourcePts.contentEquals(codecPts)
        data["sourceToCodecPts"]=obj("status" to if(codecPtsMatch)"PASS" else "FAIL",
            "sourceFrames" to sourcePts.size,"codecFrames" to codecPts.size,"comparison" to "EXACT_MICROSECOND_LEDGER",
            "mismatches" to sourcePts.indices.filter {it>=codecPts.size || sourcePts[it]!=codecPts[it]}.take(24))
        // Android MPEG4Writer coalesces near-identical durations by <100us plus tick rounding.
        // This fixed sub-frame allowance never authorizes a missing frame or a PTS reset.
        val verifier=SyntheticSegmentVerifier(sourcePts,4,100_000,120,DecodedPtsContract.ANDROID_MP4_SUBFRAME_ADJUSTMENT)
        val decoded=ArrayList<JsonObject>()
        for(file in requireNotNull(files)) {
            stage("camera_DECODE_FILE_${file.index}")
            decoded+=ProbeFileDecoder(context).inspect(Uri.parse((file.output as UsbMediaStoreRecordingOutputHandle).pendingVideo.itemUri),
                file,metadata(file.index,file.baseUs),spec,nonce,verifier,cancelled,realCamera=true)
            data["decodedFiles"]=j(decoded)
        }
        verifier.finishRun();val sequence=verifier.result()
        data["sequence"]=obj("status" to sequence.status.name,"scope" to "DECODED_CAMERA_ACQUISITION_IDS_AND_SOURCE_PTS",
            "frames" to sequence.decodedFrames,"files" to sequence.completedSegments,"maximumBoundaryGapUs" to sequence.maximumBoundaryGapUs,
            "maximumSourceGapUs" to sequence.maximumInputGapUs,"issues" to sequence.issues.map {it.name},
            "ptsContract" to "ANDROID_MP4_SUBFRAME_ADJUSTMENT","ptsToleranceUs" to 120,
            "maximumPtsErrorUs" to sequence.maximumPtsErrorUs,"beyondStrictRoundingFrames" to sequence.beyondStrictRoundingFrames,
            "ptsErrorSamples" to sequence.ptsErrors.map {obj("frame" to it.frame,"file" to it.file,
                "expectedFilePtsUs" to it.expectedFilePtsUs,"decodedFilePtsUs" to it.decodedFilePtsUs,"errorUs" to it.errorUs)})
        store.write("proof-$namespace.json",obj("run" to run,"suite" to "camera","kind" to "OPENAVM_CAMERA_PROOF","seconds" to plan.seconds,
            "nativeCleanup" to "CONFIRMED","sequenceStatus" to if(codecPtsMatch)sequence.status.name else "FAIL","files" to sequence.completedSegments,
            "frames" to sequence.decodedFrames,"admittedFrames" to gl.frameCount))
        // Same exact-identity reconciliation as P1; failed image sequences are retained for review.
        val reconciled=retained.reconcile();data["fileCleanup"]=reconciled
        val cameraEvidence=data.getValue("camera").jsonObject
        val capturedAll=cameraEvidence["sensorFramesNotAcquired"]==JsonPrimitive(0) && cameraEvidence["acquiredTimestampsWithoutResult"]==JsonPrimitive(0)
        val passed=codecPtsMatch && sequence.status==SyntheticSequenceStatus.PASS && data.getValue("input").jsonObject["status"]==JsonPrimitive("PASS") &&
            data.getValue("windows").jsonObject["status"]==JsonPrimitive("PASS") &&
            data["windowsAtLastSource"]?.jsonObject?.get("status")==JsonPrimitive("PASS") && capturedAll &&
            cameraEvidence["acquiredNativeFrameRangeConfirmed"]==JsonPrimitive(true) &&
            cameraEvidence["failuresWithinAcquiredRange"]==JsonPrimitive(0) && cameraEvidence["lostBuffersWithinAcquiredRange"]==JsonPrimitive(0) &&
            cameraEvidence["unattributableFailureCallbacks"]==JsonPrimitive(0) &&
            cameraEvidence["identityDuringCuts"]==JsonPrimitive("UNCHANGED") && (!plan.windowScript ||
                (CameraProbeWindows.rejoinCount==1 && data["windowScript"]?.jsonObject?.get("status")==JsonPrimitive("PASS"))) &&
            UsbRecordingRecoveryJournal(context,namespace).entries().isEmpty()
        return result(if(passed)"PASS" else "FAIL",if(passed)null else "P2_CONTINUITY_OR_WINDOW_REVIEW_REQUIRED")
    }
    private fun result(status:String,reason:String?)=JsonObject(obj("runId" to run,"status" to status,"reason" to reason,
        "scope" to "P2_REAL_CAMERA_REAL_WINDOWS_DIAGNOSTIC","nativeCleanup" to if(clean)"CONFIRMED" else "UNCONFIRMED",
        "productionGaplessRecording" to "NOT_YET_ROUTED","videoUploaded" to false)+data)
    private fun code(t:Throwable)=t.message?.takeIf {it.matches(Regex("[A-Z0-9_]{1,100}"))} ?: t.javaClass.simpleName
}
