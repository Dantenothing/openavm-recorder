package com.dante.zeekrcapabilitylab.sentry.canary

import android.content.Context
import android.net.Uri
import android.os.Build
import com.dante.zeekrcapabilitylab.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CanaryExportState(val busy: Boolean = false, val message: String = "尚未导出结果包")

/** Exactly one user-selected export at a time; no worker retains an Activity. */
object CanaryEvidenceExportJobs {
    private val admitted = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(CanaryExportState())
    val state = mutableState.asStateFlow()
    fun request(context: Context, destination: Uri) {
        if (!BuildConfig.SENTRY_CANARY_ENABLED || !admitted.compareAndSet(false, true)) return
        val app = context.applicationContext
        val current = CombinedCanaryReport(build = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT,
            ramAndClip = SentryCanaryService.state.value, usb = UsbCanaryJobs.state.value)
        if (current.ramAndClip.running || current.usb.busy) {
            mutableState.value = CanaryExportState(message = "请先停止 RAM 和 USB 测试，再导出结果包")
            admitted.set(false)
            return
        }
        mutableState.value = CanaryExportState(true, "正在校验并导出报告和短片，请保持应用打开")
        executor.execute {
            val result = runCatching {
                val read = runCatching { CanaryEvidenceStore.read(app) }
                app.contentResolver.openOutputStream(destination, "wt")!!.use { output ->
                    CanaryEvidenceBundleWriter.write(output, File(app.filesDir, "sentry-canary/clips"),
                        read.getOrDefault(CanaryEvidenceArchive()), current, System.currentTimeMillis(),
                        if (read.isFailure) "EVIDENCE_READ_FAILED" else null)
                }
            }
            mutableState.value = CanaryExportState(message = result.fold(
                { "结果包导出完成：${it.includedClips} 段短片，${it.omittedClips} 段未包含（原因见 summary.txt）" },
                { "导出未完成：${it.javaClass.simpleName}；目标 ZIP 不能作为完整结果包，请重新导出" },
            ))
            admitted.set(false)
        }
    }
}
