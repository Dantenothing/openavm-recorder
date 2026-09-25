package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import com.dante.zeekrcapabilitylab.preflight.*
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.serialization.json.*
import java.io.File
import android.net.Uri
import com.dante.zeekrcapabilitylab.preflight.remote.*

/** Reads only private P1 ledgers. No USB directory listing or ordinary recorder journal recovery. */
@androidx.annotation.RequiresApi(29)
internal class ProbeRetainedDiagnostics(private val context: Context, private val target: UsbExportTarget,
    private val run: String, private val store: PreflightStore, private val cancelled: () -> Boolean) {
    private val json=Json { ignoreUnknownKeys=true }
    private val dir=File(context.filesDir,"preflight")
    private fun ledgers(): List<File> = dir.listFiles().orEmpty().filter {
        (it.name.startsWith("usb-p1-") || it.name.startsWith("usb-p2-")) && it.extension=="json" }
        .also { check(it.size<=64) { "P1_RETAINED_LEDGER_LIMIT" } }
    private fun entries(file: File): List<UsbPendingRecordingOutput> {
        val data=store.read(file.name) ?: error("DIAGNOSTIC_LEDGER_UNREADABLE")
        check(data["schemaVersion"]==JsonPrimitive(2)) { "DIAGNOSTIC_LEDGER_VERSION" }
        val items=data.getValue("entries").jsonArray.map { json.decodeFromJsonElement<UsbPendingRecordingOutput>(it) }
        check(items.size<=4 && items.map { it.operationId }.distinct().size==items.size) { "DIAGNOSTIC_LEDGER_INVALID" }
        return items
    }
    fun retainedCount() = ledgers().sumOf { entries(it).size }
    fun ensureRoom() { check(ProbeRetainedPolicy.hasRoom(retainedCount())) { "P1_RETAINED_EVIDENCE_LIMIT_REVIEW_REQUIRED" } }
    private data class Exact(val namespace:String,val entry:UsbPendingRecordingOutput,val fingerprint:String,val view:JsonObject)
    private fun exactInventory():List<Exact> {
        check(UsbExportVolumeResolver.resolveExact(context,target)!=null) {"USB_REMOVED"}
        val backend=UsbMediaStoreBackend(context)
        return ledgers().sortedBy {it.name}.flatMap { file ->
            val ns=file.name.removePrefix("usb-").removeSuffix(".json")
            val split=ProbeRetainedPolicy.split(ns)
            val receipt=store.read("owned-$ns.json")
            val valid=ProbeRetainedPolicy.sequenceValidated(ns,store.read("proof-$ns.json"),split?.let {store.read("report-${it.first}.json")})
            entries(file).sortedBy {it.operationId}.map { entry ->
                check(!cancelled()) {"TEST_CANCELLED"}
                val token=File(dir,"tokens-$ns/${entry.operationId}.token")
                val tokenMatches=LabPolicy.uuid.matches(entry.operationId) && token.isFile && token.length()<=64 &&
                    runCatching {token.readText()==entry.operationId}.getOrDefault(false)
                val created=split!=null && receipt?.get("run")==JsonPrimitive(split.first) &&
                    receipt["owned"]?.jsonArray?.any {it.jsonObject["uri"]==JsonPrimitive(entry.itemUri) &&
                        it.jsonObject["operation"]==JsonPrimitive(entry.operationId)}==true
                var reason=ProbeRetainedPolicy.identityReason(ns,entry,target,tokenMatches,created)
                val observed=if(reason==null)backend.metadata(Uri.parse(entry.itemUri)) else null
                if(reason==null && observed!=null && (observed.displayName!=entry.requestedName ||
                    observed.relativePath?.trim('/')!=UsbExportPolicy.RELATIVE_PATH.trim('/') ||
                    !observed.volumeName.equals(target.volumeName,true) || observed.mimeType!="video/mp4" ||
                    observed.ownerPackage.isNullOrBlank() || (observed.ownerPackage!=context.packageName && observed.ownerPackage!=entry.observedOwnerPackage)))
                    reason="DIAGNOSTIC_OBSERVED_IDENTITY_MISMATCH"
                val facts=obj("entry" to json.encodeToJsonElement(entry),"identityReason" to reason,"sequenceValidated" to valid,
                    "observed" to observed?.let {obj("name" to it.displayName,"path" to it.relativePath,"bytes" to it.sizeBytes,
                        "mime" to it.mimeType,"pending" to it.pending,"volume" to it.volumeName,"owner" to it.ownerPackage,"modified" to it.dateModifiedSeconds)})
                val hash=payloadHash(facts)
                Exact(ns,entry,hash,obj("operationId" to entry.operationId,"priorRun" to split?.first,"suite" to split?.second,
                    "segmentIndex" to entry.segmentNumber-1,"bytes" to observed?.sizeBytes,
                    "existence" to if(reason!=null)"UNVERIFIED" else if(observed==null)"ABSENT" else "PRESENT",
                    "identityReason" to reason,"eligibleForReviewedRetirement" to (reason==null),"sequenceValidated" to valid,
                    "disposition" to ProbeRetainedPolicy.disposition(reason,valid),"fingerprint" to hash))
            }
        }.also {check(it.map {x->x.entry.operationId}.distinct().size==it.size) {"DIAGNOSTIC_DUPLICATE_OPERATION"}}
    }
    private fun inventoryHash(items:List<Exact>)=payloadHash(JsonArray(items.map {obj("id" to it.entry.operationId,"fingerprint" to it.fingerprint)}))
    fun inventory():JsonObject {
        val items=exactInventory()
        return obj("status" to "PASS","inventoryHash" to inventoryHash(items),"files" to items.map {it.view},
            "retainedEvidenceFiles" to items.size,"maximumRetainedFiles" to 8,"reservedForNextSuite" to 4,
            "admission" to if(ProbeRetainedPolicy.hasRoom(items.size))"READY" else "BLOCKED",
            "ordinaryRecordingsScanned" to false,"videoArchivedByJson" to false)
    }
    fun retire(request:JsonObject):JsonObject {
        LabContract.payload("DIAGNOSTICS_RETIRE",request)
        val before=exactInventory()
        val ids=LabEvidenceApproval.selected(request,inventoryHash(before),before.associate {it.entry.operationId to it.view.flag("eligibleForReviewedRetirement")})
        val selected=ids.map {id->before.singleOrNull {it.entry.operationId==id} ?: error("DIAGNOSTIC_OPERATION_NOT_FOUND")}
        check(selected.all {it.view.flag("eligibleForReviewedRetirement")}) {"DIAGNOSTIC_IDENTITY_NOT_CONFIRMED"}
        val results=ArrayList<JsonObject>()
        fun save()=store.write("retirement-$run.json",obj("runId" to run,"request" to request,"selected" to selected.map {it.view},"results" to results.toList()))
        save() // Exact approval and identity receipt must be durable before the first deletion.
        val backend=UsbMediaStoreBackend(context)
        for(item in selected) {
            check(!cancelled()) {"TEST_CANCELLED"}
            UsbMutationCoordinator.withTarget(target.storageUuid) {
                check(UsbExportVolumeResolver.resolveExact(context,target)!=null) {"USB_REMOVED"}
                val current=exactInventory().singleOrNull {it.entry.operationId==item.entry.operationId}
                check(current?.fingerprint==item.fingerprint) {"DIAGNOSTIC_INVENTORY_CHANGED"}
                val e=item.entry
                val deleted=backend.deleteOwned(target,UsbExportAsset(UsbExportAssetKind.VIDEO,segmentNumber=e.segmentNumber,
                    requestedName=e.requestedName,expectedBytes=0,expectedSha256="",expectedMimeType="video/mp4",
                    itemUri=e.itemUri,observedOwnerPackage=e.observedOwnerPackage))
                if(deleted.deletionConfirmed) {
                    UsbRecordingRecoveryJournal(context,item.namespace).remove(e.operationId)
                    val token=File(dir,"tokens-${item.namespace}/${e.operationId}.token")
                    if(token.exists())check(token.delete()) {"DIAGNOSTIC_TOKEN_DELETE_FAILED"}
                }
                results+=obj("operationId" to e.operationId,"deletionConfirmed" to deleted.deletionConfirmed,
                    "journalRemovalConfirmed" to (e.operationId !in entries(File(dir,"usb-${item.namespace}.json")).map {it.operationId}),
                    "reason" to if(deleted.deletionConfirmed)null else ProbeCompletion.deletionReason(deleted.error))
                save()
                check(deleted.deletionConfirmed) {"DIAGNOSTIC_RETIREMENT_INCOMPLETE"}
            }
        }
        return obj("status" to "PASS","inventoryHash" to request.string("inventoryHash"),"retired" to results,
            "remaining" to inventory(),"ordinaryRecordingsScanned" to false)
    }
    fun reconcile(): JsonObject {
        val reports=ArrayList<JsonObject>(); var deleted=0
        val backend=UsbMediaStoreBackend(context)
        for(file in ledgers().sortedBy { it.name }) {
            check(!cancelled()) { "TEST_CANCELLED" }
            val namespace=file.name.removePrefix("usb-").removeSuffix(".json")
            val split=ProbeRetainedPolicy.split(namespace)
            val receipt=store.read("owned-$namespace.json")
            val proof=store.read("proof-$namespace.json")
            val previous=split?.let { store.read("report-${it.first}.json") }
            val validated=ProbeRetainedPolicy.sequenceValidated(namespace,proof,previous)
            val oldEntries=entries(file)
            if(oldEntries.isNotEmpty())store.write("retention-before-$namespace.json",obj("run" to run,
                "ledger" to requireNotNull(store.read(file.name)),"validatedSequence" to validated))
            for(entry in oldEntries) {
                check(!cancelled()) { "TEST_CANCELLED" }
                val token=File(dir,"tokens-$namespace/${entry.operationId}.token")
                // Do not read a token until its operation ID has a bounded UUID shape.
                val tokenMatches=entry.operationId.matches(Regex("[0-9a-f-]{36}")) && token.isFile && token.length()<=64 &&
                    runCatching { token.readText()==entry.operationId }.getOrDefault(false)
                val creationMatches=split!=null && receipt?.get("run")==JsonPrimitive(split.first) &&
                    receipt["owned"]?.jsonArray?.any { item -> item.jsonObject["uri"]==JsonPrimitive(entry.itemUri) &&
                        item.jsonObject["operation"]==JsonPrimitive(entry.operationId) }==true
                val identity=ProbeRetainedPolicy.identityReason(namespace,entry,target,tokenMatches,creationMatches)
                val disposition=ProbeRetainedPolicy.disposition(identity,validated)
                var removed=false; var journalRemoved=false; var failureCode: String?=null
                if(disposition=="DELETE_VALIDATED_DIAGNOSTIC") {
                    UsbMutationCoordinator.withTarget(target.storageUuid) {
                        check(UsbExportVolumeResolver.resolveExact(context,target)!=null) { "USB_REMOVED" }
                        val result=backend.deleteOwned(target,UsbExportAsset(UsbExportAssetKind.VIDEO,
                            segmentNumber=entry.segmentNumber,requestedName=entry.requestedName,expectedBytes=0,
                            expectedSha256="",expectedMimeType="video/mp4",itemUri=entry.itemUri,observedOwnerPackage=entry.observedOwnerPackage))
                        removed=result.deletionConfirmed
                        if(removed) {
                            UsbRecordingRecoveryJournal(context,namespace).remove(entry.operationId)
                            journalRemoved=entries(file).none { it.operationId==entry.operationId }
                            if(journalRemoved && token.exists())check(token.delete()) { "DIAGNOSTIC_TOKEN_DELETE_FAILED" }
                        } else failureCode=ProbeCompletion.deletionReason(result.error)
                    }
                }
                if(removed && journalRemoved)deleted++
                reports+=obj("priorRun" to split?.first,"suite" to split?.second,"file" to entry.segmentNumber-1,
                    "disposition" to disposition,"deletionConfirmed" to removed,"journalRemovalConfirmed" to journalRemoved,"reason" to failureCode)
            }
        }
        val remaining=retainedCount()
        return obj("status" to if(ProbeRetainedPolicy.hasRoom(remaining))"PASS" else "BLOCKED",
            "deletedValidatedFiles" to deleted,"retainedEvidenceFiles" to remaining,"maximumRetainedFiles" to 8,
            "reservedForNextSuite" to 4,"ordinaryRecordingsScanned" to false,"files" to reports)
    }
}
