package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticConnection
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal class LabHttpFailure(val status:Int):Exception("HTTP_$status")
internal class LabWire(private val connection:DiagnosticConnection) {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    fun cancelAll()=client.dispatcher.cancelAll()
    private fun request(path:String)=Request.Builder().url(connection.origin+path)
        .header("Authorization","Bearer ${connection.token}").header("User-Agent","OpenAVM-RemoteLab/1")
    fun post(path:String,value:JsonObject=obj()):JsonObject = client.newCall(request(path)
        .post(value.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
        if(!it.isSuccessful)throw LabHttpFailure(it.code)
        val source=it.body?.source() ?: error("EMPTY_RESPONSE")
        check(!source.request(131073)) { "RESPONSE_TOO_LARGE" }
        Json.parseToJsonElement(source.readUtf8()).jsonObject
    }
    fun download(session:String,hash:String,destination:File) {
        require(LabPolicy.uuid.matches(session) && LabPolicy.hash.matches(hash))
        val temp=File(destination.path+".part")
        try {
            client.newCall(request("/v1/lab/sessions/$session/apks/$hash").get().build()).execute().use { response ->
                if(!response.isSuccessful)throw LabHttpFailure(response.code)
                val body=response.body ?: error("EMPTY_APK")
                check(body.contentLength()<=LabPolicy.MAX_APK) { "APK_SIZE_LIMIT" }
                body.byteStream().use { input -> FileOutputStream(temp).use { output ->
                    val buffer=ByteArray(65536);var count=0L
                    while(true) { val n=input.read(buffer);if(n<0)break;count+=n;check(count<=LabPolicy.MAX_APK) { "APK_SIZE_LIMIT" };output.write(buffer,0,n) }
                    output.fd.sync()
                } }
            }
            check(temp.renameTo(destination)) { "APK_SAVE_FAILED" }
        } finally { temp.delete() }
    }
}
