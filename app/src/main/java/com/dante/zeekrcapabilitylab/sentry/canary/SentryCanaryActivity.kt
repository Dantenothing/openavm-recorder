package com.dante.zeekrcapabilitylab.sentry.canary

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.util.AtomicFile
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dante.zeekrcapabilitylab.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine

/** Debug test surface deliberately separate from the normal recording controls. */
class SentryCanaryActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var save: Button
    private lateinit var stop: Button
    private lateinit var exportClip: Button
    private lateinit var exportReport: Button
    private lateinit var copyStartup: Button
    private lateinit var viewReport: Button
    private lateinit var copyStatus: TextView
    private lateinit var usbRun: Button
    private lateinit var usbRecover: Button
    private lateinit var usbCleanup: Button
    private lateinit var exportBundle: Button
    private var exportSource: File? = null
    private var preparingReport = false
    private val bundleExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) CanaryEvidenceExportJobs.request(applicationContext, uri)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SentryCanaryService.start(this) else toast("需要相机权限才能开始测试")
    }
    private val export = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = exportSource
        exportSource = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || source == null) return@registerForActivityResult
        lifecycleScope.launch {
            val okay = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                }.isSuccess
            }
            toast(if (okay) "导出完成" else "导出失败，请重新选择位置")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SENTRY_CANARY_ENABLED) { finish(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply {
            textSize = 22f
            text = "OpenAVM 哨兵测试 · RAM / 手动短片"
        })
        root.addView(TextView(this).apply {
            textSize = 16f
            text = if (!BuildConfig.SENTRY_CAPTURE_ENABLED) "本版本暂时停用哨兵 RAM 监听，已有报告和短片仍可查看或导出。" else
                "先停止普通录像。RAM 缓存 768 MiB，预录目标 3 分钟；已有至少 5 秒完整历史时可先保存诊断短片。\n" +
                "这是硬件验证入口，自动离车切换与 AI 尚未启用。测试最长运行 2 小时；离开本页仍继续，可从通知停止。\n" +
                "建议顺序：开始 → 等约 3 分钟 10 秒 → 保存 → 等待校验 → 停止 → 复制 JSON 报告。\n" +
                "前 3 分钟历史未满时，保存的是诊断短片。启动 4 分钟后仍未达标会提示，但已有可用历史时继续监听。\n" +
                "每轮结果自动归档；报告可直接复制到邮件正文，不需要选择保存位置。"
        })
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; root.addView(this) }
        start = button("1. 开始 RAM 监听") {
            if (!BuildConfig.SENTRY_CAPTURE_ENABLED) toast("本版本暂时停用哨兵 RAM 监听")
            else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) SentryCanaryService.start(this)
            else permissions.launch(Manifest.permission.CAMERA)
        }
        save = button("2. 保存诊断短片（已有历史 + 后 10 秒）") { SentryCanaryService.trigger(this) }
        stop = button("3. 停止并生成报告") { SentryCanaryService.stop(this) }
        copyStartup = button("复制本轮启动诊断（本次反馈用这个）") { prepareStartupCopy() }
        exportReport = button("4. 复制 JSON 报告（全部记录，运行中也可）") { prepareTextReport(refresh = true, copyImmediately = true) }
        viewReport = button("查看 / 分段复制上次 JSON") { prepareTextReport(refresh = false, copyImmediately = false) }
        copyStatus = TextView(this).apply {
            text = "复制后粘贴到邮件正文。报告包含多轮记录和检查结果，不含视频；完成新测试后请重新点“复制 JSON 报告”。"
            root.addView(this)
        }
        exportClip = button("可选：导出最近短片") {
            SentryCanaryService.state.value.clip?.fileName?.let { name ->
                if (name.matches(Regex("[0-9a-f-]{36}\\.mp4"))) selectExport(File(filesDir, "sentry-canary/clips/$name"), "video/mp4")
            }
        }
        root.addView(TextView(this).apply {
            text = "确认短片可播放后，可在同一次测试中继续 USB 封装检查。此步骤只使用已触发的短片，写入 OpenAVM/SentryCanary 测试目录。"
        })
        usbRun = button("6. 用已校验短片测试 USB 封装") {
            UsbCanaryJobs.request(this, UsbCanaryOperation.RUN, SentryCanaryService.state.value.clip)
        }
        usbRecover = button("检查上次 USB 测试的中断结果") { UsbCanaryJobs.request(this, UsbCanaryOperation.RECOVER, null) }
        usbCleanup = button("清理本工具创建的那一个 USB 测试文件") { UsbCanaryJobs.request(this, UsbCanaryOperation.CLEANUP, null) }
        exportBundle = button("可选：导出全部报告和短片（ZIP）") {
            bundleExport.launch("OpenAVM-Sentry-${System.currentTimeMillis()}.zip")
        }
        button("返回设置") { finish() }
        status = TextView(this).apply { textSize = 16f; root.addView(this) }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SentryCanaryService.restoreReport(applicationContext)
                UsbCanaryJobs.restore(applicationContext)
                runCatching { CanaryEvidenceStore.read(applicationContext) }
            }
            combine(SentryCanaryService.state, UsbCanaryJobs.state, CanaryEvidenceStore.state, CanaryEvidenceExportJobs.state) { ram, usb, archive, export ->
                render(ram, usb, archive, export)
            }.collect { }
        }
    }

    private fun render(value: CanarySnapshot, usb: UsbCanaryJobSnapshot, archive: CanaryArchiveState, export: CanaryExportState) {
        start.isEnabled = BuildConfig.SENTRY_CAPTURE_ENABLED && !value.running && !usb.busy && !export.busy
        save.isEnabled = value.running && value.clipReady && !value.saving
        save.text = if (value.ramReady) "2. 保存验收短片（前 ${value.historyTargetSeconds} 秒 + 后 10 秒）" else "2. 保存诊断短片（已有历史 + 后 10 秒）"
        stop.isEnabled = value.running && value.phase != "STOPPING"
        exportReport.isEnabled = !preparingReport
        copyStartup.isEnabled = !preparingReport
        viewReport.isEnabled = !preparingReport
        exportClip.isEnabled = !value.running && !usb.busy && !export.busy && value.clip?.fileName != null
        usbRun.isEnabled = !value.running && !usb.busy && !export.busy && value.clip?.containerVerified == true && value.clip.partial == false
        usbRecover.isEnabled = !value.running && !usb.busy && !export.busy
        usbCleanup.isEnabled = !value.running && !usb.busy && !export.busy
        exportBundle.isEnabled = !value.running && !usb.busy && !export.busy && !preparingReport
        status.text = buildString {
            appendLine("状态：${value.phase}")
            appendLine(when {
                value.ramReady -> "${value.historyTargetSeconds} 秒 RAM 目标已就绪，可保存验收短片。"
                value.clipReady -> "已有可用历史，可保存诊断短片；尚未达到 ${value.historyTargetSeconds} 秒目标。"
                value.ramReadinessReason == "GOP_GAP_RESTART_REQUIRED" -> "已出现画面间断，请停止并复制本轮启动诊断。"
                value.ramReadinessReason == "CLOCK_NOT_READY" -> "正在等待有效的视频时间戳。"
                value.running && value.phase != "STOPPING" -> "等待至少 5 秒完整历史及关键帧，之后第二步会亮起。"
                else -> "RAM 监听未运行，保存按钮不可用。"
            })
            if (value.historyTargetTimedOut) appendLine("启动 ${value.historyStartupDeadlineSeconds} 秒时未达到 ${value.historyTargetSeconds} 秒目标；已有短片仍可用于诊断。")
            appendLine("画面：${value.width} × ${value.height}；实际 %.1f fps".format(value.actualFps))
            appendLine("实际数据：%.2f MB/秒（%.2f Mbps）；温度状态：${value.thermalStatus ?: "不可用"}".format(
                value.actualBitrateBps / 8_000_000.0, value.actualBitrateBps / 1_000_000.0))
            appendLine("历史：%.1f 秒；编码池占用 %.1f / %.1f MiB；峰值 %.1f MiB".format(
                value.historySeconds, value.encodedLiveBytes / 1048576.0, value.encodedCapacityBytes / 1048576.0, value.encodedHighWaterBytes / 1048576.0))
            appendLine("已完整缓存 %.1f 秒；有效数据 %.1f MiB，峰值 %.1f MiB".format(
                value.completedHistorySeconds, value.encodedPayloadLiveBytes / 1048576.0, value.encodedPayloadHighWaterBytes / 1048576.0))
            appendLine("因容量不足提前移除的历史 GOP：${value.budgetEvictedGops}")
            appendLine("丢弃 GOP：${value.droppedGops}；最大输出间隔：${value.maxDrainGapMs} ms；进程 PSS：${value.processPssKiB / 1024} MiB")
            appendLine("正在保存：${value.saving}；停止原因：${value.stopReason ?: "无"}")
            value.startup?.let { startup ->
                appendLine("启动检查：${startup.stage}；原始规格 ${startup.sourceWidth} × ${startup.sourceHeight}")
                appendLine("编码器：${value.encoder.ifBlank { "尚未选定" }}；已尝试 ${startup.attempts.size} 个")
                startup.systemAvailableBytes?.let { appendLine("系统可用 RAM：%.2f GiB".format(it / 1073741824.0)) }
                appendLine("应用堆上限 ${startup.appHeapMaxBytes / 1048576} MiB；启动时堆余量 ${startup.appHeapHeadroomBytes / 1048576} MiB")
                appendLine("编码池预算 ${value.encodedBudgetBytes / 1048576} MiB；存储 ${if (value.bufferStorage == "ANONYMOUS_SHARED_MEMORY") "系统共享内存" else "应用堆"}")
            }
            value.clip?.let {
                appendLine("最近短片：${it.reason}；partial=${it.partial}")
                appendLine("触发前 %.1f 秒 / 触发后 %.1f 秒；容器校验=${it.containerVerified}；首帧解码=${it.decodedFrameVerified}".format(
                    it.preRollAchievedUs / 1_000_000.0, it.postRollAchievedUs / 1_000_000.0))
            }
            appendLine("时间映射目前为编码回调估计，精度和完整播放仍需实车确认。")
            appendLine("USB：${if (usb.busy) "${usb.operation} 进行中" else usb.result?.reason ?: "尚未测试"}")
            usb.result?.let { appendLine("USB gate=${it.passed}；提交状态=${it.phase}；stop=${it.stopDurationMs} ms；最长单次写入=${it.verification?.longestWriteSampleMs} ms") }
            value.telemetry?.let { appendLine("本轮运行 ${it.elapsedMs / 1000} 秒；PSS 峰值 ${it.peakPssKiB / 1024} MiB；温控峰值 ${it.peakThermal ?: "未知"}；曾达到 RAM 就绪=${it.ramReadyObserved}") }
            val checks = CanaryEvidenceEvaluator.ram(value).checks + CanaryEvidenceEvaluator.usb(usb).checks
            appendLine("自动检查：已观察通过 ${checks.count { it.status == CanaryCheckStatus.PASS }} 项 / 未通过 ${checks.count { it.status == CanaryCheckStatus.FAIL }} 项 / 待验证 ${checks.count { it.status == CanaryCheckStatus.PENDING }} 项。完整硬件验收仍待确认。")
            checks.filter { it.status == CanaryCheckStatus.FAIL }.forEach { appendLine("未通过：${it.explanation}") }
            appendLine("归档：${archive.records} 条；按容量淘汰 ${archive.evicted} 条。${archive.error ?: ""}")
            appendLine(export.message)
        }
    }

    private fun prepareStartupCopy() {
        if (preparingReport) return
        updateReportPreparation(true)
        val current = CombinedCanaryReport(build = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT,
            ramAndClip = SentryCanaryService.state.value, usb = UsbCanaryJobs.state.value)
        val capturedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { CanaryStartupReportText.prepare(current, capturedAt) } }
            updateReportPreparation(false)
            result.fold(onSuccess = { report ->
                if (!copyJsonPart(report, 0)) showTextReport(report, false)
                else copyStatus.text = "本轮启动诊断已复制为一段 JSON，请粘贴到邮件正文。"
            }, onFailure = { toast("本轮诊断生成失败，请使用全部记录的 JSON 报告") })
        }
    }

    private fun prepareTextReport(refresh: Boolean, copyImmediately: Boolean) {
        if (preparingReport) return
        updateReportPreparation(true)
        copyStatus.text = "正在准备 JSON 报告…"
        val capturedAt = System.currentTimeMillis()
        val current = CombinedCanaryReport(build = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT,
            ramAndClip = SentryCanaryService.state.value, usb = UsbCanaryJobs.state.value)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val previous = if (refresh) null else readSavedTextReport()
                    if (previous != null) previous to true else {
                        val archive = runCatching { CanaryEvidenceStore.read(applicationContext) }
                        val prepared = CanaryTextReportWriter.prepare(archive.getOrDefault(CanaryEvidenceArchive()),
                            current, capturedAt, if (archive.isFailure) "EVIDENCE_READ_FAILED" else null)
                        prepared to runCatching { saveTextReport(prepared.json) }.isSuccess
                    }
                }
            }
            updateReportPreparation(false)
            result.fold(onSuccess = { (report, cached) ->
                val copied = copyImmediately && copyJsonPart(report, 0)
                if (!copyImmediately || !copied || report.parts.size > 1) showTextReport(report, copied)
                if (!cached) {
                    copyStatus.append("\n上次报告保存失败；请保持此页面，按当前报告完成复制。")
                    toast("报告已生成，但无法保存快照；请保持此页面完成复制")
                }
            }, onFailure = {
                copyStatus.text = if (refresh) "JSON 报告生成失败：${it.javaClass.simpleName}，请重试。"
                    else "上次 JSON 读取失败，请点“4. 复制 JSON 报告”重新生成。"
                toast(copyStatus.text.toString())
            })
        }
    }

    private fun updateReportPreparation(preparing: Boolean) {
        preparingReport = preparing
        render(SentryCanaryService.state.value, UsbCanaryJobs.state.value,
            CanaryEvidenceStore.state.value, CanaryEvidenceExportJobs.state.value)
    }

    /** A private snapshot lets the user return from email without changing the multipart report. */
    private fun textReportFile() = AtomicFile(File(filesDir, "sentry-canary/copy-report.json"))

    private fun readSavedTextReport(): CanaryPreparedTextReport? = try {
        textReportFile().openRead().use { input ->
            check(input.channel.size() <= CanaryTextReportWriter.MAX_REPORT_BYTES) { "TEXT_REPORT_TOO_LARGE" }
            CanaryTextReportWriter.fromJson(input.readBytes().toString(Charsets.UTF_8))
        }
    } catch (_: FileNotFoundException) { null }

    private fun saveTextReport(json: String) {
        val file = textReportFile()
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun copyJsonPart(report: CanaryPreparedTextReport, index: Int): Boolean {
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("OpenAVM Sentry JSON ${index + 1}/${report.parts.size}", report.parts[index]))
        }.isSuccess
        copyStatus.text = when {
            !copied -> "自动复制失败，请在 JSON 正文长按并选择全选、复制。"
            report.parts.size == 1 -> "JSON 报告已复制，可直接粘贴到邮件正文。"
            else -> "已复制第 ${index + 1}/${report.parts.size} 段。请逐段粘贴到邮件；返回时点“查看 / 分段复制上次 JSON”继续。"
        }
        toast(copyStatus.text.toString())
        return copied
    }

    private fun showTextReport(report: CanaryPreparedTextReport, firstCopied: Boolean) {
        var index = 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val information = TextView(this).apply { textSize = 15f; root.addView(this) }
        val scroll = ScrollView(this).apply {
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels * 2 / 5))
        }
        val body = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); scroll.addView(this) }
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        val previous = Button(this).apply { text = "上一段"; navigation.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        val next = Button(this).apply { text = "下一段"; navigation.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        val dialog = AlertDialog.Builder(this).setTitle("JSON 报告").setView(root)
            .setPositiveButton("复制本段", null).setNegativeButton("关闭", null).create()
        fun renderPart() {
            information.text = if (report.parts.size == 1) "完整 JSON，可复制后粘贴到邮件正文；也可长按正文选择文字。"
                else "第 ${index + 1}/${report.parts.size} 段。每段都要复制并粘贴到邮件；可分多封发送。返回应用后可查看上次 JSON 继续复制。"
            information.append("\n这是生成时的快照；完成新测试后请回主页面重新生成。")
            body.text = report.parts[index]
            previous.isEnabled = index > 0
            next.isEnabled = index < report.parts.lastIndex
            scroll.scrollTo(0, 0)
        }
        previous.setOnClickListener { if (index > 0) { index--; renderPart() } }
        next.setOnClickListener { if (index < report.parts.lastIndex) { index++; renderPart() } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { copyJsonPart(report, index) }
        }
        renderPart()
        dialog.show()
        if (firstCopied) information.append("\n第 1 段已复制，请先粘贴后再复制下一段。")
        else copyStatus.text = "JSON 已打开，可点“复制本段”，或长按正文选择文字。"
    }

    private fun selectExport(file: File, mime: String) {
        if (exportSource != null) return
        if (SentryCanaryService.state.value.running || !file.isFile) { toast("请先停止并等待报告生成"); return }
        exportSource = file
        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, file.name)
        })
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
