package com.dante.zeekrcapabilitylab.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.diagnostic.DiagnosticCopyReport
import com.dante.zeekrcapabilitylab.diagnostic.RecorderDiagnostics
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
    var report by remember { mutableStateOf<DiagnosticCopyReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var shortReport by remember { mutableStateOf<String?>(null) }
    var capabilityReport by remember { mutableStateOf<String?>(null) }
    fun collect(latest: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if (latest) RecorderDiagnostics.latest(context) else RecorderDiagnostics.prepare(context)
            } }
            result.onSuccess { report = it; status = "" }.onFailure {
                status = Utils.t("Cannot read diagnostics: {0}", "无法读取诊断：{0}", it.javaClass.simpleName)
            }
            busy = false
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
        busy = true
        runCatching { withContext(Dispatchers.IO) { ShortRecorderDiagnostics.collect(context) } }
            .onSuccess { shortReport = it }
            .onFailure { status = Utils.t("Cannot read diagnostics: {0}", "无法读取诊断：{0}", it.javaClass.simpleName) }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Utils.t("Recording diagnostics", "录像诊断")) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Utils.t("Copy device status and recent recording logs as JSON. Video files are not included.", "将设备状态和近期录像日志复制为 JSON，不包含视频文件。"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                shortReport?.let { compact ->
                    Button(onClick = { copy(compact) }, enabled = !busy) { Text(Utils.t("Copy short JSON", "复制简短 JSON")) }
                    Text(Utils.t("One copy includes current status, recent errors and the last saved fault. Detailed logs are optional.", "一次复制即可包含当前状态、近期错误与上次保存的异常；详细日志按需生成。"), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    val cached = capabilityReport
                    if (cached != null) copy(cached) else {
                        busy = true
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
                            } finally { busy = false }
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
                TextButton(onClick = { collect(true) }, enabled = !busy) { Text(Utils.t("Reopen last saved report", "重新打开上次保存的诊断")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) } },
    )
}
