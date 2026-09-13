package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local, finalized events are durable staging; verified export uses the existing owned USB namespace. */
object GuardUsbAutoSave {
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean()
    private val dirty = AtomicBoolean()
    private val retry = AtomicBoolean()
    private val messages = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = messages.asStateFlow()

    fun schedule(context: Context, retryAfterMount: Boolean = false) {
        if (!com.dante.zeekrcapabilitylab.BuildConfig.SENTRY_INTEGRATED_ENABLED) return
        val app = context.applicationContext
        if (retryAfterMount) retry.set(true)
        dirty.set(true)
        if (!queued.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (dirty.getAndSet(false)) reconcile(app, retry.getAndSet(false))
            } finally {
                queued.set(false)
                if (dirty.get()) schedule(app)
            }
        }
    }
    private fun reconcile(context: Context, retryAfterMount: Boolean) {
        val store = GuardEventStore(context)
        val targets = runCatching { UsbExportVolumeResolver.mountedTargets(context) }.getOrDefault(emptyList())
        val target = targets.singleOrNull()
        val notes = linkedMapOf<String, String>()
        // The local event quota is bounded; only finalized assets enter the existing verified export engine.
        for (event in store.list().filter { it.state != "WRITING" && it.assets.isNotEmpty() }) {
            runCatching {
                val existing = UsbExportRepository.tasks.value.filter { it.logicalId == event.id }
                if (retryAfterMount) existing.filter {
                    it.state in setOf(UsbExportState.FAILED_RECOVERABLE, UsbExportState.WAITING_FOR_TARGET) &&
                        targets.any { target -> target.storageUuid == it.target.storageUuid && target.volumeName == it.target.volumeName }
                }.forEach { UsbExportRepository.retry(it.id) }
                val files = store.files(event)
                val missing = files.filter { file ->
                    GuardUsbSavePolicy.needsEnqueue(file.absolutePath, existing)
                }
                notes[event.id] = when {
                    files.size != event.assets.size -> "本机事件有缺失分段；请查看详情"
                    missing.isEmpty() -> "本机原件保留"
                    target == null && targets.size > 1 -> "本机已保存；检测到多个 USB，请在管理中选择"
                    target == null -> "本机已保存；等待可写 USB，接回后自动保存"
                    else -> {
                        UsbExportRepository.enqueue(event.id, "SENTRY",
                            event.createdAtEpochMs - event.preAchievedUs / 1000,
                            event.createdAtEpochMs + event.postAchievedUs / 1000, missing, target).getOrThrow()
                        "本机已保存；正在自动保存到 USB"
                    }
                }
            }.onFailure { notes[event.id] = "本机已保存；USB 保存失败：" + (it.message ?: it.javaClass.simpleName).take(180) }
        }
        messages.value = notes
    }
}

object GuardUsbSavePolicy {
    private val verified = setOf(UsbExportState.COMPLETED, UsbExportState.ALREADY_EXPORTED)
    fun needsEnqueue(path: String, tasks: List<UsbExportTask>): Boolean =
        tasks.none { it.sources.any { source -> source.path == path } }

    fun label(event: GuardEvent, tasks: List<UsbExportTask>, fallback: String?): String {
        if (event.state == "WRITING") return "正在保存到本机；事件结束后自动保存 USB"
        if (event.assets.isEmpty()) return "暂无已完成分段可保存到 USB"
        val own = tasks.filter { it.logicalId == event.id }
        val complete = event.assets.count { asset ->
            own.any { it.state in verified && it.sources.any { source -> source.fileName == asset.name } }
        }
        if (event.assets.isNotEmpty() && complete == event.assets.size) return "USB 已校验保存 · 本机原件保留"
        if (own.any { it.state in setOf(UsbExportState.FAILED, UsbExportState.FAILED_RECOVERABLE) })
            return "USB 已保存 " + complete + "/" + event.assets.size + " 段；有失败任务，本机原件保留"
        if (own.any { it.state !in verified && it.state != UsbExportState.CANCELLED })
            return "USB 已保存 " + complete + "/" + event.assets.size + " 段；正在等待 / 写入 / 校验"
        if (own.any { it.state == UsbExportState.CANCELLED })
            return "USB 保存已取消；本机原件保留，可手动导出"
        return fallback ?: "本机已保存；等待自动保存 USB"
    }
}
