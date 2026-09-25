package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*

internal object LabTransport {
    fun result(value:JsonObject):JsonObject = if(value.toString().toByteArray().size<=8192)value else
        obj("commandState" to value["commandState"],"runState" to value["runState"],"outcome" to value["outcome"],
            "cleanupState" to value["cleanupState"],"reason" to "RESULT_DETAIL_IN_REPORT","runId" to value["runId"],
            "resultHash" to payloadHash(value))
    /** Relay accepts a 32 KiB body, not 32 KiB independently for status and events. */
    fun poll(status:JsonObject,pending:JsonArray):JsonObject {
        require(status.toString().toByteArray().size<=24_576) {"STATUS_TOO_LARGE"}
        val chosen=ArrayList<JsonElement>()
        for(event in pending.take(24)) {
            val candidate=obj("status" to status,"events" to (chosen+event))
            if(candidate.toString().toByteArray().size>32_768)break
            chosen+=event
        }
        return obj("status" to status,"events" to chosen)
    }
    fun trimProgress(progress:JsonObject):JsonObject = if(progress.toString().toByteArray().size<=8192)progress else
        obj("runId" to progress["runId"],"stage" to progress["stage"],"stages" to progress["stages"],
            "observedAtElapsedMs" to progress["observedAtElapsedMs"],"statisticsInFullReport" to true)
}
