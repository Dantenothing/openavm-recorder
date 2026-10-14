package com.dante.zeekrcapabilitylab.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dante.zeekrcapabilitylab.diagnostic.DiagnosticCopyReport
import com.dante.zeekrcapabilitylab.diagnostic.RecorderDiagnostics
import com.dante.zeekrcapabilitylab.diagnostic.RecorderSupportReport
import com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics
import com.dante.zeekrcapabilitylab.enhancement.CapabilityCollector
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecorderDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveModel: RecorderSupportSaveModel = viewModel()
    // The combined snapshot is capped at 96 KiB. Preserve it while the system file
    // picker recreates the Activity; never resample a different run on its return.
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<DiagnosticCopyReport?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val busy = preparing || pendingExport != null || saveModel.saving
    var status by remember { mutableStateOf("") }
    var shortReport by remember { mutableStateOf<String?>(null) }
    var capabilityReport by remember { mutableStateOf<String?>(null) }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (snapshot == null) {
            status = Utils.t("Report unavailable. Tap Export diagnostics file again.", "报告暂不可用，请重新点击导出诊断文件。")
        } else saveModel.save(context, snapshot, uri)
    }
    fun collect(latest: Boolean) {
        if (busy) return
        preparing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if (latest) RecorderDiagnostics.latest(context) else RecorderDiagnostics.prepare(context)
            } }
            result.onSuccess { report = it; status = "" }.onFailure {
                status = Utils.t("Cannot read diagnostics: {0}", "无法读取诊断：{0}", it.javaClass.simpleName)
            }
            preparing = false
        }
    }
    fun copy(value: String) {
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("OpenAVM diagnostics", value))
        }.onSuccess { status = Utils.t("Copied. Paste this JSON into an email or message.", "已复制，可将 JSON 粘贴到邮件或消息中。") }
            .onFailure { status = Utils.t("Copy failed. Try again.", "复制失败，请重试。") }
    }
    LaunchedEffect(Unit) {
        if (pendingExport != null || saveModel.saving) return@LaunchedEffect
        preparing = true
        runCatching { withContext(Dispatchers.IO) { ShortRecorderDiagnostics.collect(context) } }
            .onSuccess { shortReport = it }
            .onFailure { status = Utils.t("Cannot read diagnostics: {0}", "无法读取诊断：{0}", it.javaClass.simpleName) }
        preparing = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Utils.t("Recording diagnostics", "录像诊断")) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Utils.t("Export one file with recording, preview and sleep diagnostics. No video, coordinates or account data are included.",
                    "将录像、预览和离车诊断导出为一个文件，不包含视频、坐标或账号资料。"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (com.dante.zeekrcapabilitylab.BuildConfig.RECORDER_SUPPORT_ENABLED) OutlinedButton(onClick = {
                    context.startActivity(android.content.Intent(context, com.dante.zeekrcapabilitylab.preflight.PreflightActivity::class.java))
                }, enabled = !busy) { Text(Utils.t("Online support session", "在线诊断会话")) }
                Button(onClick = {
                    if (!busy) {
                        preparing = true
                        status = ""
                        saveModel.clearOutcome()
                        scope.launch {
                            try {
                                pendingExport = withContext(Dispatchers.IO) { RecorderSupportReport.collect(context) }
                                saveReport.launch("OpenAVM-diagnostics-${System.currentTimeMillis()}.json")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                pendingExport = null
                                status = Utils.t("Could not prepare the file. Please try again.", "无法生成诊断文件，请重试。")
                            } finally { preparing = false }
                        }
                    }
                }, enabled = !busy) { Text(Utils.t("Export diagnostics file", "导出诊断文件")) }
                shortReport?.let { compact ->
                    Button(onClick = { copy(compact) }, enabled = !busy) { Text(Utils.t("Copy short JSON", "复制简短 JSON")) }
                    Text(Utils.t("One copy includes current status, recent errors and the last saved fault. Detailed logs are optional.", "一次复制即可包含当前状态、近期错误与上次保存的异常；详细日志按需生成。"), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    val cached = capabilityReport
                    if (cached != null) copy(cached) else {
                        preparing = true
                        scope.launch {
                            try {
                                val collected = CapabilityCollector.collect(context)
                                capabilityReport = collected
                                copy(collected)
                            } catch (_: TimeoutCancellationException) {
                                status = Utils.t("Capability query timed out. Please try again later.", "能力读取超时，请稍后再试。")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                status = Utils.t("Cannot read diagnostics: {0}", "无法读取诊断：{0}", error.javaClass.simpleName)
                            } finally { preparing = false }
                        }
                    }
                }, enabled = !busy) {
                    Text(if (capabilityReport == null) Utils.t("Collect and copy capability JSON", "采集并复制能力 JSON")
                        else Utils.t("Copy capability JSON", "复制能力 JSON"))
                }
                Text(Utils.t("Reads camera and encoder declarations without opening cameras or starting recording. This does not confirm multi-camera support.",
                    "只读取摄像头和编码器的声明，不打开相机或开始录像；此报告不代表已验证多路录像。"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { collect(false) }, enabled = !busy) { Text(Utils.t("Prepare detailed report", "生成详细报告")) }
                report?.let { current ->
                    if (current.canCopyWhole) {
                        Button(onClick = { copy(current.text) }, enabled = !busy) { Text(Utils.t("Copy full JSON", "复制完整 JSON")) }
                    } else {
                        Text(Utils.t("This report has {0} parts. Copy every part in order.", "本报告共 {0} 段，请按顺序复制全部段落。", current.parts.size))
                        current.parts.forEachIndexed { index, part ->
                            OutlinedButton(onClick = { copy(part) }, enabled = !busy) { Text(Utils.t("Copy part {0}/{1}", "复制第 {0}/{1} 段", index + 1, current.parts.size)) }
                        }
                    }
                }
                if (status.isNotEmpty()) Text(status)
                when (saveModel.outcome) {
                    RecorderSupportSaveModel.Outcome.SAVED -> Text(Utils.t("Diagnostics saved as one file. Attach it to your support message.", "诊断已保存为一个文件，可直接作为附件发送。"))
                    RecorderSupportSaveModel.Outcome.FAILED -> Text(Utils.t("Could not save the file. Try another folder.", "文件保存失败，请重试或选择其他文件夹。"))
                    RecorderSupportSaveModel.Outcome.NONE -> Unit
                }
                TextButton(onClick = { collect(true) }, enabled = !busy) { Text(Utils.t("Reopen last saved report", "重新打开上次保存的诊断")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) } },
    )
}
