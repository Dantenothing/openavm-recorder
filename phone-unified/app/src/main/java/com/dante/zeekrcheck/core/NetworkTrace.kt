package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.Request
import java.util.ArrayDeque

/** Fixed labels only. No URLs, addresses, headers, bodies, account identifiers or exception messages. */
object NetworkTrace {
    private val events=ArrayDeque<JsonObject>()
    @Volatile var changed: ((String)->Unit)?=null
    @Synchronized fun record(request:Request,outcome:String,issue:String?=null,httpStatus:Int?=null) {
        val endpoint=Endpoint.entries.firstOrNull { request.url.encodedPath=="/${it.path}" }?.name ?: when(request.url.encodedPath) {
            "/${RequestPolicy.VEHICLES}" -> "VEHICLES"
            "/${RequestPolicy.BEARER}" -> "SESSION_EXCHANGE"
            "/ms-remote-control/v1.0/remoteControl/control" -> "CONTROL"
            PresenceHeartbeat.URL.encodedPath -> "ONLINE_PRESENCE"
            else -> "ACCOUNT"
        }
        events.add(buildJsonObject {
            put("at",System.currentTimeMillis()); put("endpoint",endpoint); put("method",request.method)
            put("outcome",outcome); issue?.let { put("issue",it) }; httpStatus?.let { put("httpStatus",it) }
        })
        while(events.size>40) events.removeFirst()
        runCatching { changed?.invoke(export()) }
    }
    @Synchronized fun export()=buildJsonObject {
        put("schema","zeekr-network-trace/1");putJsonArray("events") { events.forEach { add(it) } }
    }.toString()
}
