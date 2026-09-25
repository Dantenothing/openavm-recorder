package com.dante.zeekrcapabilitylab.preflight.cloud

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.dante.zeekrcapabilitylab.preflight.PreflightRuntime
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object DiagnosticUpload {
    private const val JOB = 0x5E61
    private val io=Executors.newSingleThreadExecutor { Thread(it,"preflight-report-outbox") }
    private val lock=ReentrantLock()
    @Volatile private var workflowOwner:String?=null
    fun reserveWorkflow(owner:String):Boolean {
        if(!lock.tryLock())return false
        try { if(workflowOwner!=null)return false;workflowOwner=owner;return true } finally {lock.unlock()}
    }
    fun releaseWorkflow(owner:String) { if(workflowOwner==owner)workflowOwner=null }
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS)
        .callTimeout(25,TimeUnit.SECONDS).build()
    private class HttpFailure(val status: Int): Exception("HTTP_$status")
    fun startTestWhenIdle(owner:String?=null,start: () -> Boolean): Boolean {
        // Reservation already excluded network uploads. Local outbox writes may finish between steps.
        if(owner!=null && workflowOwner==owner)return start()
        if(!lock.tryLock()) return false
        try { if(workflowOwner!=null && workflowOwner!=owner)return false;return start() } finally { lock.unlock() }
    }
    private fun request(connection: DiagnosticConnection, path: String, payload: ByteArray, method: String = "POST", auth: Boolean=true): JsonObject {
        val builder=Request.Builder().url(connection.origin+path).header("User-Agent","OpenAVM-Diagnostics/1")
            .method(method,payload.toRequestBody("application/json".toMediaType()))
        if(auth) builder.header("Authorization","Bearer ${connection.token}")
        return client.newCall(builder.build()).execute().use { response ->
            if(!response.isSuccessful) throw HttpFailure(response.code)
            val source=response.body?.source() ?: error("EMPTY_RESPONSE")
            check(!source.request(16_385)) { "RESPONSE_TOO_LARGE" }
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }
    fun pair(context: Context, endpoint: String, code: String) = lock.withLock {
        check(!PreflightRuntime.state.value.active && workflowOwner==null) { "TEST_IS_RUNNING" }
        val origin=DiagnosticPolicy.origin(endpoint)
        val clean=code.trim().replace("-","").replace(" ","").uppercase()
        require(Regex("[A-Z2-7]{12}").matches(clean)) { "PAIRING_CODE_INVALID" }
        val settings=DiagnosticSettings(context); val connection=settings.prepare(origin)
        val result=request(connection,"/v1/pair",obj("code" to clean,"deviceId" to connection.device,"token" to connection.token).toString().toByteArray(),auth=false)
        check(result["service"] == JsonPrimitive(DiagnosticPolicy.SERVICE) && result["deviceId"] == JsonPrimitive(connection.device) &&
            result["paired"] == JsonPrimitive(true)) { "PAIRING_RESPONSE_INVALID" }
        settings.paired(); DiagnosticOutbox(context).status("已连接 · 测试结束后自动上传完整报告")
        schedule(context)
    }
    fun check(context: Context) = lock.withLock {
        check(!PreflightRuntime.state.value.active && workflowOwner==null) { "TEST_IS_RUNNING" }
        val settings=DiagnosticSettings(context); val connection=settings.load() ?: error("NOT_PAIRED")
        check(connection.paired) { "NOT_PAIRED" }
        val result=request(connection,"/v1/check","{}".toByteArray())
        check(result["service"] == JsonPrimitive(DiagnosticPolicy.SERVICE) && result["deviceId"] == JsonPrimitive(connection.device) && result["ready"]==JsonPrimitive(true)) { "RECEIVER_IDENTITY_MISMATCH" }
        DiagnosticOutbox(context).status("连接正常 · 云端接收服务可用")
    }
    fun enable(context: Context, enabled: Boolean) = lock.withLock {
        val settings=DiagnosticSettings(context)
        if(enabled) check(settings.load()?.paired == true) { "NOT_PAIRED" }
        settings.enable(enabled)
        if(enabled) schedule(context) else context.getSystemService(JobScheduler::class.java).cancel(JOB)
    }
    fun submit(context: Context, bytes: ByteArray): DiagnosticIdentity = lock.withLock {
        check(DiagnosticSettings(context).let { it.enabled() && it.load()?.paired==true }) { "UPLOAD_NOT_ENABLED" }
        val box=DiagnosticOutbox(context)
        box.enqueue(bytes).also {
            if(box.receipt()?.get("reportSha256")==JsonPrimitive(it.hash))
                box.status("云端已确认完整收到 · ${it.version} · ${it.run.take(8)} · 回执 ${it.hash.take(12)}")
            else box.status("已保存待上传 · ${it.version} · ${it.run.take(8)}")
            schedule(context)
        }
    }
    fun finished(context: Context, bytes: ByteArray) {
        val app=context.applicationContext
        io.execute { if(DiagnosticSettings(app).enabled()) runCatching { submit(app,bytes) }
            .onFailure { runCatching { DiagnosticOutbox(app).status("报告保存在体检记录；上传入队失败：${safeError(it)}") } } }
    }
    fun schedule(context: Context) {
        if(!DiagnosticSettings(context).enabled()) return
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(JOB)!=null) return
        val job=JobInfo.Builder(JOB,ComponentName(context,DiagnosticUploadJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1_000)
            .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if(scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS) DiagnosticOutbox(context).status("系统暂未安排上传；重新打开体检页可补传")
    }
    fun retry(context: Context) = lock.withLock {
        DiagnosticOutbox(context).resetRetries()
        context.getSystemService(JobScheduler::class.java).cancel(JOB)
        schedule(context)
    }
    fun flush(context: Context, stopped: AtomicBoolean): Boolean {
        if(!lock.tryLock()) return true
        try {
            val settings=DiagnosticSettings(context)
            if(!settings.enabled()) return false
            if(PreflightRuntime.state.value.active || workflowOwner!=null) return true // Upload after the whole workflow, outside measured runs.
            val connection=settings.load()?.takeIf { it.paired } ?: return false
            val outbox=DiagnosticOutbox(context)
            for(file in outbox.pending().take(4)) {
                if(stopped.get() || !settings.enabled() || PreflightRuntime.state.value.active || workflowOwner!=null) return true
                if(!DiagnosticPolicy.retryAllowed(outbox.attempts(file))) {
                    outbox.status("已保留本机报告；自动重试已达 6 次，可点重试待上传")
                    return false
                }
                try {
                    val bytes=outbox.read(file); val identity=DiagnosticPolicy.identity(bytes)
                    check(file.name==DiagnosticPolicy.fileName(identity)) { "QUEUED_REPORT_IDENTITY_MISMATCH" }
                    outbox.status("正在上传 · ${identity.version} · ${identity.run.take(8)}")
                    outbox.attempting(file)
                    val result=request(connection,"/v1/reports/${identity.run}/${identity.hash}",bytes,"PUT")
                    outbox.acknowledge(file,identity,result)
                    outbox.status("云端已确认完整收到 · ${identity.version} · ${identity.run.take(8)} · 回执 ${identity.hash.take(12)}")
                } catch(t:Throwable) {
                    outbox.status("尚未上传成功，已保留本机报告 · ${safeError(t)}")
                    return (t is java.io.IOException || t is HttpFailure) &&
                        DiagnosticPolicy.retryAllowed(outbox.attempts(file),(t as? HttpFailure)?.status)
                }
            }
            return outbox.pending().isNotEmpty()
        } finally { lock.unlock() }
    }
    fun safeError(t: Throwable): String = when(t) {
        is HttpFailure -> when(t.status) { 401,403 -> "认证未通过"; 409 -> "配对码已使用"; 507 -> "云端保留额度已满"; else -> "HTTP_${t.status}" }
        is java.io.IOException -> "网络暂不可用，联网后补传"
        else -> t.message?.takeIf { Regex("[A-Z0-9_]{1,64}").matches(it) } ?: t.javaClass.simpleName
    }
}

class DiagnosticUploadJob: JobService() {
    private val executor=Executors.newSingleThreadExecutor { Thread(it,"preflight-report-upload") }
    private var stopped=AtomicBoolean()
    override fun onStartJob(params: JobParameters): Boolean {
        val flag=AtomicBoolean(); stopped=flag
        executor.execute {
            val retry=runCatching { DiagnosticUpload.flush(applicationContext,flag) }.getOrElse {
                runCatching { DiagnosticOutbox(applicationContext).status("上传暂未完成 · ${DiagnosticUpload.safeError(it)}；可手动重试") }; false
            }
            if(!flag.get()) jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdown(); super.onDestroy() }
}
