package com.dante.zeekrcapabilitylab.preflight.remote

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Only the isolated remote process writes this journal. UI uses Messenger, never cached preferences. */
internal class LabStore(context:Context) {
    val dir=File(context.filesDir,"remote-lab").apply { mkdirs() }
    private val file=AtomicFile(File(dir,"session.json"))
    val boot=runCatching { Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1) }.getOrDefault(-1)
    val state:JsonObject get()=synchronized(lock) {
        cached ?: runCatching { Json.parseToJsonElement(file.readFully().toString(Charsets.UTF_8)).jsonObject }
            .getOrDefault(obj()).also { cached=it }
    }
    fun active()=LabPolicy.active(state,SystemClock.elapsedRealtime(),boot)
    fun change(vararg pairs:Pair<String,Any?>)=synchronized(lock) {
        val next=JsonObject(state+obj(*pairs));val out=file.startWrite()
        try { out.write(next.toString().toByteArray());file.finishWrite(out);cached=next }
        catch(t:Throwable) { file.failWrite(out);throw t }
    }
    fun arm(updates:Boolean) {
        check(boot>=0) { "BOOT_ID_UNAVAILABLE" }
        val now=SystemClock.elapsedRealtime()
        change("armed" to true,"sessionId" to UUID.randomUUID().toString(),"boot" to boot,"startedElapsed" to now,
            "deadlineElapsed" to now+LabPolicy.DURATION_MS,"serverOpened" to false,"allowUpdates" to updates,
            "commands" to obj(),"events" to emptyList<JsonObject>(),"eventSeq" to 0,"eventAck" to 0,"prepared" to null,"installerSession" to null,
            "updatingCommand" to null,"updateFrom" to null,"updateTarget" to null,"installerCallback" to null)
    }
    fun record(id:String,value:JsonObject) {
        require(LabPolicy.uuid.matches(id))
        val prior=state["commands"] as? JsonObject ?: obj()
        check(prior.size<128 || id in prior) { "COMMAND_JOURNAL_FULL" }
        change("commands" to JsonObject(prior+(id to value)))
    }
    fun records()=state["commands"] as? JsonObject ?: obj()
    fun appendEvent(type:String,data:JsonObject,generation:String) {
        require(Regex("[A-Z0-9_]{1,48}").matches(type))
        val seq=state.number("eventSeq")+1
        val base=obj("seq" to seq,"type" to type,"processGeneration" to generation,"atElapsedMs" to SystemClock.elapsedRealtime())
        var event=JsonObject(base+obj("data" to data))
        if(event.toString().toByteArray().size>2048)event=JsonObject(base+obj("data" to obj(
            "omittedForSize" to true,"dataHash" to payloadHash(data),"runId" to data["runId"],"commandId" to data["commandId"])))
        val old=(state["events"] as? JsonArray).orEmpty()
        change("eventSeq" to seq,"events" to (old+event).takeLast(128))
    }
    fun pendingEvents()=JsonArray((state["events"] as? JsonArray).orEmpty().filter {it.jsonObject.number("seq")>state.number("eventAck")}.take(24))
    fun acknowledgeEvents(ack:Long,sentThrough:Long) {
        require(ack in state.number("eventAck")..sentThrough.coerceAtLeast(state.number("eventAck"))) {"EVENT_ACK_INVALID"}
        if(ack>state.number("eventAck"))change("eventAck" to ack)
    }
    fun apk(hash:String):File { require(LabPolicy.hash.matches(hash));return File(dir,"candidate-$hash.apk") }
    companion object {
        // Receiver and service share one process and one journal, including during package replacement.
        private val lock=Any()
        private var cached:JsonObject?=null
    }
}
