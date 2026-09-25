package com.dante.zeekrcapabilitylab.runtime

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import kotlinx.serialization.json.*
import java.io.File

internal class C0Store(context:Context) {
    private val file=AtomicFile(File(context.filesDir,"diagnostics/c0-parking.json"))
    val boot=runCatching {Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)}.getOrDefault(-1)
    fun read():JsonObject? = synchronized(fileLock) { if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) null else
        file.openRead().use { check(it.channel.size()<=256*1024); Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
    }
    fun write(data:JsonObject) = synchronized(fileLock) {
        file.baseFile.parentFile?.mkdirs(); val out=file.startWrite()
        try {out.write(data.toString().toByteArray());file.finishWrite(out)}catch(t:Throwable){file.failWrite(out);throw t}
    }
    fun reportForUser():JsonObject? {
        val saved=read() ?: return null
        val run=saved["runId"]?.jsonPrimitive?.content.orEmpty()
        if(saved["phase"]==JsonPrimitive("FINISHED") || C0ParkingService.ownsRun(run))return saved
        return JsonObject(saved+mapOf("lastSavedPhase" to (saved["phase"] ?: JsonNull),"phase" to JsonPrimitive("INTERRUPTED"),
            "reason" to JsonPrimitive("PROCESS_OR_SERVICE_ENDED_NO_REPLAY"),"permitRemainingMs" to JsonPrimitive(0),
            "tests" to JsonArray(listOf(buildJsonObject{put("name","c0_background_reopen");put("outcome","INCOMPLETE")}))))
    }
    companion object {
        // The activity and service construct separate stores for this same AtomicFile.
        private val fileLock=Any()
        fun installInterlock(context:Context) {
            val store=C0Store(context)
            val value=runCatching {store.read()}
            if(value.isSuccess && value.getOrNull()==null)return
            val old=value.getOrNull()
            val oldBoot=old?.get("bootCount")?.jsonPrimitive?.intOrNull
            if(old?.get("cleanup")==JsonPrimitive("CONFIRMED") || (store.boot>=0 && oldBoot!=null && oldBoot>=0 && oldBoot!=store.boot))return
            CameraWorkCoordinator.claim("C0_INTERRUPTED")?.let {
                CameraWorkCoordinator.publish(it,"上轮后台相机实验释放未确认，请复制报告后重启车机。","C0_CLEANUP_UNCONFIRMED")
            }
        }
    }
}
