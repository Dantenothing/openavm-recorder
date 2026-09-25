package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.service.recorder.UsbPendingRecordingOutput
import com.dante.zeekrcapabilitylab.usbexport.UsbExportAssetKind
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import java.net.URI
import kotlinx.serialization.json.*

/** Admission for exact diagnostic entries; a matching generic OpenAVM filename is insufficient. */
internal object ProbeRetainedPolicy {
    private val uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val namespaceRegex=Regex("(?:p1-($uuid)-(health|full|stress)|p2-($uuid)-(camera))")
    fun split(namespace: String): Pair<String,String>? = namespaceRegex.matchEntire(namespace)?.let {
        if(it.groupValues[1].isNotEmpty())it.groupValues[1] to it.groupValues[2] else it.groupValues[3] to it.groupValues[4]
    }
    fun sequenceValidated(namespace: String, proof: JsonObject?, report: JsonObject?): Boolean = runCatching {
        val (run,suite)=split(namespace) ?: return@runCatching false
        if(suite=="camera") {
            val seconds=proof?.get("seconds")?.jsonPrimitive?.intOrNull ?: return@runCatching false
            val frames=proof["frames"]?.jsonPrimitive?.intOrNull ?: return@runCatching false
            return@runCatching seconds in setOf(20,240,600) && frames in 1..(seconds*40+120) &&
                proof["run"]==JsonPrimitive(run) && proof["suite"]==JsonPrimitive("camera") &&
                proof["kind"]==JsonPrimitive("OPENAVM_CAMERA_PROOF") && proof["files"]==JsonPrimitive(4) &&
                proof["nativeCleanup"]==JsonPrimitive("CONFIRMED") && proof["sequenceStatus"]==JsonPrimitive("PASS") &&
                proof["admittedFrames"]==JsonPrimitive(frames)
        }
        val expectedFrames=if(suite=="health")120 else 600
        if(proof?.get("run")==JsonPrimitive(run) && proof["suite"]==JsonPrimitive(suite) &&
            proof["nativeCleanup"]==JsonPrimitive("CONFIRMED") && proof["sequenceStatus"]==JsonPrimitive("PASS") &&
            proof["files"]==JsonPrimitive(4) && proof["frames"]==JsonPrimitive(expectedFrames))return@runCatching true
        if(report?.get("runId")!=JsonPrimitive(run) || report["cleanup"]!=JsonPrimitive("CONFIRMED"))return@runCatching false
        val source=report.getValue("tests").jsonArray.map { it.jsonObject }.firstOrNull {
            it["id"] in listOf(JsonPrimitive("p1_basic"),JsonPrimitive("p1_input")) }?.get("data")?.jsonObject ?: return@runCatching false
        if(source["scope"] !in listOf(JsonPrimitive("P1_BASIC_SYNTHETIC_DIRECT_GL"),JsonPrimitive("P1_INPUT_SYNTHETIC_OES_SHARED_OFFSCREEN")))return@runCatching false
        val sequence=source["${suite}Sequence"]?.jsonObject ?: return@runCatching false
        sequence["status"]==JsonPrimitive("PASS") && sequence["files"]==JsonPrimitive(4) &&
            sequence["frames"]==JsonPrimitive(expectedFrames) && sequence["issues"]?.jsonArray?.isEmpty()==true
    }.getOrDefault(false)
    fun identityReason(namespace: String, entry: UsbPendingRecordingOutput, target: UsbExportTarget,
        tokenMatches: Boolean, creationReceiptMatches: Boolean): String? {
        if(split(namespace)==null || entry.recordingSessionId!=namespace)return "DIAGNOSTIC_NAMESPACE_MISMATCH"
        if(!entry.operationId.matches(Regex(uuid)) || entry.segmentNumber !in 1..4 || entry.nativeCheckpoint!=null)return "DIAGNOSTIC_ENTRY_INVALID"
        if(!entry.storageUuid.equals(target.storageUuid,true) || !entry.volumeName.equals(target.volumeName,true))return "DIAGNOSTIC_OTHER_VOLUME"
        val exactUri=runCatching {
            val collection=URI(target.collectionUri); val item=URI(entry.itemUri)
            val prefix=collection.path.trimEnd('/')+"/"
            item.scheme.equals(collection.scheme,true) && item.authority.equals(collection.authority,true) &&
                item.query==null && item.fragment==null && item.path.startsWith(prefix) &&
                item.path.removePrefix(prefix).matches(Regex("[0-9]+"))
        }.getOrDefault(false)
        if(!exactUri)return "DIAGNOSTIC_URI_MISMATCH"
        val name=Regex("OpenAVM_([0-9]+)_([0-9a-f]{12})_S([0-9]{3})\\.mp4").matchEntire(entry.requestedName)
            ?: return "DIAGNOSTIC_NAME_MISMATCH"
        val started=name.groupValues[1].toLongOrNull() ?: return "DIAGNOSTIC_NAME_MISMATCH"
        val bundle=UsbExportPolicy.bundleId(namespace,entry.segmentNumber,started)
        if(entry.bundleId!=bundle || name.groupValues[2]!=bundle.take(12) || name.groupValues[3].toInt()!=entry.segmentNumber)
            return "DIAGNOSTIC_BUNDLE_MISMATCH"
        val asset=entry.assets.singleOrNull() ?: return "DIAGNOSTIC_ASSETS_MISMATCH"
        if(asset.kind!=UsbExportAssetKind.VIDEO || asset.itemUri!=entry.itemUri || asset.requestedName!=entry.requestedName ||
            asset.expectedMimeType!="video/mp4" || asset.observedOwnerPackage!=entry.observedOwnerPackage)return "DIAGNOSTIC_ASSETS_MISMATCH"
        if(!tokenMatches || !creationReceiptMatches)return "DIAGNOSTIC_CREATION_PROOF_MISSING"
        return null
    }
    fun disposition(identityReason: String?, sequenceValidated: Boolean): String =
        identityReason ?: if(sequenceValidated)"DELETE_VALIDATED_DIAGNOSTIC" else "RETAIN_UNVERIFIED_OR_FAILED_EVIDENCE"
    fun hasRoom(retained: Int) = retained in 0..4 // Existing cap remains 8, reserve 4 for the next suite.
}
