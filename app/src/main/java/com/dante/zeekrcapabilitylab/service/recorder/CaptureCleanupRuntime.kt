package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class HandlerCloseDispatcher(private val handler: Handler) : CloseDispatcher {
    override fun execute(action: () -> Unit) = handler.post(action)
    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        check(handler.postDelayed(task, delayMs)) { "CLEANUP_TIMER_REJECTED" }
        return { handler.removeCallbacks(task) }
    }
}
class ExecutorCloseDispatcher(private val executor: Executor) : CloseDispatcher {
    override fun execute(action: () -> Unit): Boolean = try { executor.execute(action); true }
        catch (_: RejectedExecutionException) { false }
    override fun after(delayMs: Long, action: () -> Unit): () -> Unit = error("Use control for deadlines")
}

/** Process-scoped control survives Activity/Service destruction. Unknown native owners stay reserved. */
object CaptureCleanupRuntime {
    val handler = Handler(HandlerThread("capture-cleanup-control").apply { start() }.looper)
    val control: CloseDispatcher = HandlerCloseDispatcher(handler)
    private val journal = Handler(HandlerThread("capture-cleanup-journal").apply { start() }.looper)
    private data class Owner(val device: CameraDevice?, val transaction: CaptureCloseTransaction)
    private val owners = mutableMapOf<Any, Owner>()
    private val pending = kotlinx.coroutines.flow.MutableStateFlow(0)
    val pendingOwners: kotlinx.coroutines.flow.StateFlow<Int> = pending
    private val closedDevices = java.util.WeakHashMap<CameraDevice, Boolean>()
    private val history = linkedMapOf<String, MutableList<JsonElement>>()
    private var context: Context? = null
    private var loaded = false
    private var omitted = 0
    private var dirty = false
    private val flush = Runnable {
        val target = synchronized(this) {
            dirty = false
            context?.let { AtomicFile(File(it.filesDir, "recordings/camera-lifecycle.json")) }
        } ?: return@Runnable
        runCatching {
            target.baseFile.parentFile?.mkdirs()
            val bytes = snapshot().toString().toByteArray()
            val out = target.startWrite()
            try { out.write(bytes); target.finishWrite(out) }
            catch (t: Throwable) { target.failWrite(out); throw t }
        }
    }
    @Synchronized fun initialize(value: Context) {
        context = value.applicationContext
        if (loaded) return
        loaded = true
        runCatching {
            val file = AtomicFile(File(value.filesDir, "recordings/camera-lifecycle.json"))
            file.openRead().use {
                require(it.channel.size() <= 512 * 1024)
                val saved = Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
                omitted = saved["omittedTransactions"]?.jsonPrimitive?.int ?: 0
                saved["transactions"]?.jsonObject?.entries?.toList()?.takeLast(12)?.forEach { (key, steps) ->
                    history[key] = steps.jsonArray.toMutableList()
                }
            }
        }
    }
    @Synchronized fun retain(token: Any, cameraId: String, device: CameraDevice?, tx: CaptureCloseTransaction) {
        CanaryCameraInterlock.beginCleanup(token, cameraId)
        owners[token] = Owner(device, tx)
        pending.value = owners.size
        if (device != null && closedDevices[device] == true) tx.deviceClosed()
    }
    @Synchronized fun settled(token: Any, safe: Boolean) {
        if (!safe) return // Retain handles and admission until actual proof; no timeout eviction.
        owners.remove(token)
        pending.value = owners.size
        CanaryCameraInterlock.cleanupComplete(token)
    }
    @Synchronized fun deviceClosed(device: CameraDevice) {
        closedDevices[device] = true
        owners.values.filter { it.device === device }.forEach { it.transaction.deviceClosed() }
    }
    @Synchronized fun trace(id: String, name: String, detail: String) {
        val steps = history.getOrPut(id) { mutableListOf() }
        // A transaction has bounded native stages. Preserve its beginning, end and every call boundary.
        if (steps.size < 160) steps.add(buildJsonObject {
            put("at", System.currentTimeMillis()); put("elapsed", SystemClock.elapsedRealtime())
            put("step", name); put("detail", detail.take(600))
        })
        while (history.size > 12) { history.remove(history.keys.first()); omitted++ }
        if (!dirty) { dirty = true; journal.postDelayed(flush, 100) }
    }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("omittedTransactions", omitted)
        put("unsettledOwners", owners.size)
        put("transactions", buildJsonObject { history.forEach { (id, steps) -> put(id, JsonArray(steps.toList())) } })
    }
    /** Bounded waiter; timeout never frees an owner or grants camera access. */
    fun awaitIdle(timeoutMs: Long = 15_000, done: (Boolean) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        handler.post(object : Runnable {
            override fun run() {
                if (CanaryCameraInterlock.normalIdle()) done(true)
                else if (SystemClock.elapsedRealtime() >= deadline) done(false)
                else handler.postDelayed(this, 50)
            }
        })
    }
}
