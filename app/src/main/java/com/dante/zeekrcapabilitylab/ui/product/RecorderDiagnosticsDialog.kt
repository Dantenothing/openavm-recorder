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
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecorderDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DiagnosticCopyReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
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
    LaunchedEffect(Unit) { collect(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Utils.t("Recording diagnostics", "录像诊断")) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Utils.t("Copy device status and recent recording logs as JSON. Video files are not included.", "将设备状态和近期录像日志复制为 JSON，不包含视频文件。"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
