package com.dante.zeekrcapabilitylab.preflight.remote

import kotlinx.serialization.json.*

internal object LabEvidenceApproval {
    fun selected(request:JsonObject,currentHash:String,eligible:Map<String,Boolean>):List<String> {
        LabContract.payload("DIAGNOSTICS_RETIRE",request)
        check(request.string("inventoryHash")==currentHash) {"DIAGNOSTIC_INVENTORY_CHANGED"}
        val ids=request.getValue("operationIds").jsonArray.map {it.jsonPrimitive.content}
        check(ids.all {it in eligible}) {"DIAGNOSTIC_OPERATION_NOT_FOUND"}
        check(ids.all {eligible[it]==true}) {"DIAGNOSTIC_IDENTITY_NOT_CONFIRMED"}
        return ids
    }
}
