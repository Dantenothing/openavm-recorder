package com.dante.zeekrcapabilitylab.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.enhancement.*
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.util.Utils
import java.io.File

@Composable fun ConcurrentRecordingSettings() {
    val context = LocalContext.current
    val state by CameraWorkCoordinator.state.collectAsState()
    val recorder by CameraRecordingService.state.collectAsState()
    var secondary by remember { mutableStateOf(RecordingSourceRole.CABIN) }
    var message by remember { mutableStateOf("") }
    val preferences = remember(context) { context.getSharedPreferences("multi_camera", 0) }
    var previous by remember(preferences) { mutableStateOf(preferences.getString("result", "").orEmpty()) }
    DisposableEffect(preferences) {
        // A fast preflight rejection can start and finish between Compose frames.
        // Observe the persisted result itself, not just the active-task flag.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "result") { previous = prefs.getString("result", "").orEmpty(); message = "" }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Utils.t("Two-camera recording · experimental", "两路录像 · 实验功能"), style = MaterialTheme.typography.titleMedium)
        Text(Utils.t("Park before testing. Records Surround + Cabin or IR to two separate USB videos for 60 seconds, then releases both cameras. Requires a declared camera/encoder combination. No automatic retry; the screen turning off stops the test.",
            "请停车后测试。环视 + Cabin 或 IR 分别录制到 USB，60 秒后释放两路相机。需系统声明支持相机及编码器组合；失败不自动重试，息屏会停止测试。"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR).forEach { role ->
                OutlinedButton(enabled = !state.active, onClick = { secondary = role }) { Text("${if(secondary == role) "✓ " else ""}Surround + $role") }
            }
            OutlinedButton(enabled = !state.active && !CameraRecordingService.isRunning(), onClick = {
                message = ""
                if (!ConcurrentRecordingService.start(context, secondary)) message = Utils.t("Stop current camera work first.", "请先停止当前相机任务。")
            }) { Text(Utils.t("Start 60 s test", "开始 60 秒测试")) }
            if (state.active && state.kind == "MULTI") OutlinedButton(onClick = { ConcurrentRecordingService.stop() }) { Text(Utils.t("Stop", "停止")) }
        }
        Text(if(state.kind == "MULTI" && state.active) state.message else previous, style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val report = runCatching { File(context.filesDir, "multi-camera-last.json").takeIf { it.length() in 1..32768 }?.readText() }.getOrNull()
            if(report != null) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM multi-camera", report)); message = Utils.t("Copied", "已复制") }
            else message = Utils.t("No completed test report yet", "暂无已完成的测试报告")
        }) { Text(Utils.t("Copy two-camera test JSON", "复制两路测试 JSON")) }
    } }
}
