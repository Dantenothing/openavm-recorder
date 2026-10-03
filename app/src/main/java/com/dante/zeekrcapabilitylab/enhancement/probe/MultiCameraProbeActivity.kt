package com.dante.zeekrcapabilitylab.enhancement.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ui.product.FourLaneTextureContainer
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MultiCameraProbeActivity : ComponentActivity() {
    private val targets = mutableMapOf<String, ProbePreviewTarget>()
    private var notice by mutableStateOf<String?>(null)
    private var exportReport: String? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) Utils.t("Permission granted. Check the options and tap Start again.", "权限已授予，请核对选项后再次点击开始。")
            else Utils.t("Camera permission is required for the test.", "测试需要摄像头权限。")
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = exportReport; exportReport = null
        if (uri != null && report == null) notice = Utils.t("Report is unavailable. Return to the test and save it again.", "报告暂不可用，请返回测试页面重新保存。")
        if (uri != null && report != null) lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(report.toByteArray(Charsets.UTF_8)) }
            }.isSuccess }
            notice = if (saved) Utils.t("Report saved", "报告已保存") else Utils.t("Could not save report", "报告保存失败")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED) { finish(); return }
        exportReport = savedInstanceState?.getString("pendingProbeExport")
        setContent { ZeekrCapabilityLabTheme {
            val state by MultiCameraProbeRuntime.state.collectAsState()
            MultiCameraProbeScreen(state, notice, onRead = { MultiCameraProbeRuntime.inspect(this) },
                onStart = { slots, parked, allow ->
                    notice = null
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    else MultiCameraProbeRuntime.start(this, slots, targets.toMap(), parked, allow)
                }, onStop = { MultiCameraProbeRuntime.stop() }, onBack = ::finish,
                onCopy = { state.report?.let {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM camera probe", it))
                    notice = Utils.t("Report copied", "报告已复制")
                } }, onSave = { state.report?.let { exportReport = it; save.launch("OpenAVM-multi-camera-${System.currentTimeMillis()}.json") } },
                preview = { source -> CameraPane(source, targets) })
        } }
    }
    override fun onResume() { super.onResume(); MultiCameraProbeRuntime.setVisible(BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingProbeExport", exportReport)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { MultiCameraProbeRuntime.setVisible(false); super.onPause() }
    override fun onDestroy() { targets.clear(); super.onDestroy() }
}

@Composable
private fun CameraPane(source: ProbeSource, targets: MutableMap<String, ProbePreviewTarget>) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        FourLaneTextureContainer(context).apply {
            singleSource = source.slot != 0
            setCompositeSize(source.size.width, source.size.height)
            var target: ProbePreviewTarget? = null
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    target = ProbePreviewTarget(source.id, texture).also { targets[source.id] = it }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    target?.let { value ->
                        runCatching { texture.timestamp }.getOrNull()?.let {
                            MultiCameraProbeRuntime.frame(value, it, SystemClock.elapsedRealtime())
                        }
                    }
                    invalidate()
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    val value = target ?: return true
                    if (targets[source.id] === value) targets.remove(source.id)
                    val releaseByView = value.ownership.viewDestroyed()
                    MultiCameraProbeRuntime.surfaceDestroyed(value)
                    target = null
                    return releaseByView
                }
            }
        }
    })
}

internal fun probeSourceName(slot: Int): String = when (slot) {
    0 -> Utils.t("Surround", "环视")
    1 -> Utils.t("Cabin A", "座舱 A")
    else -> Utils.t("Cabin B", "座舱 B")
}

@Composable
internal fun MultiCameraProbeScreen(state: ProbeUiState, notice: String? = null, onRead: () -> Unit,
    onStart: (List<Int>, Boolean, Boolean) -> Unit, onStop: () -> Unit, onBack: () -> Unit,
    onCopy: () -> Unit, onSave: () -> Unit, preview: @Composable (ProbeSource) -> Unit) {
    var slots by remember { mutableStateOf(listOf(0, 1)) }
    var parked by remember { mutableStateOf(false) }
    var allowUnlisted by remember { mutableStateOf(false) }
    val selected = slots.mapNotNull { slot -> state.catalog?.sources?.find { it.slot == slot } }
    val declaration = state.catalog?.let {
        if (it.query == ProbeDeclaration.DECLARED) MultiCameraProbePolicy.declaration(selected.map(ProbeSource::id), it.declaredSets)
        else it.query
    }
    val usable = selected.size == slots.size && selected.map(ProbeSource::id).distinct().size == slots.size
    val queryUsable = declaration == ProbeDeclaration.DECLARED || declaration == ProbeDeclaration.NOT_LISTED
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Utils.t("Multi-camera preview test", "多路相机预览测试"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text(Utils.t("Back", "返回")) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Utils.t("Experimental · preview only", "实验功能 · 仅预览"), style = MaterialTheme.typography.titleMedium)
                    Text(Utils.t("Checks whether two or three streams keep updating together for 20 seconds. No videos or photos are saved. Leaving this page or turning off the screen stops the test.",
                        "检查两路或三路画面能否持续同时更新 20 秒。不保存录像或照片；离开页面或息屏即停止。"), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onRead, enabled = !state.active && !state.reading, modifier = Modifier.fillMaxWidth().testTag("probe-read")) {
                        Text(if (state.reading) Utils.t("Reading camera info…", "正在读取相机信息…") else Utils.t("Read camera info", "读取相机信息"))
                    }
                    listOf(listOf(0, 1), listOf(0, 2), listOf(0, 1, 2)).forEachIndexed { index, choice ->
                        val label = if (choice.size == 3) Utils.t("All three cameras", "三路相机")
                            else Utils.t("Surround + {0}", "环视 + {0}", probeSourceName(choice.last()))
                        OutlinedButton(onClick = { slots = choice; parked = false; allowUnlisted = false }, enabled = !state.active,
                            modifier = Modifier.fillMaxWidth().testTag("probe-choice-$index")) { Text((if (slots == choice) "✓ " else "") + label) }
                    }
                    Text(when (declaration) {
                        ProbeDeclaration.DECLARED -> Utils.t("System declaration: this combination is listed. Actual preview has not been verified.", "系统声明：列出了这组相机，实际预览仍需验证。")
                        ProbeDeclaration.NOT_LISTED -> Utils.t("System declaration: not listed. A manual runtime test is optional and may fail.", "系统声明：未列出这组相机。可选择本次实际尝试，结果可能失败。")
                        ProbeDeclaration.QUERY_FAILED -> Utils.t("Could not query the system declaration. Testing is blocked.", "系统声明读取失败，暂不能测试。")
                        ProbeDeclaration.API_UNAVAILABLE -> Utils.t("This test requires Android 11 or later.", "此测试需要 Android 11 或更新版本。")
                        null -> Utils.t("Read camera info first. This does not open a camera.", "请先读取相机信息，这一步不会打开相机。")
                    }, modifier = Modifier.testTag("probe-declaration"), style = MaterialTheme.typography.bodySmall)
                    if (state.catalog != null && !usable) Text(Utils.t("The selected sources are missing or mapped to the same camera. Check camera mapping in Settings.",
                        "所选录像源缺失或映射到同一相机，请检查设置中的摄像头映射。"), color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(parked, { parked = it }, enabled = !state.active, modifier = Modifier.testTag("probe-parked"))
                        Text(Utils.t("I am parked and ready to test", "已停车，可以开始测试"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (declaration == ProbeDeclaration.NOT_LISTED) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowUnlisted, { allowUnlisted = it }, enabled = !state.active, modifier = Modifier.testTag("probe-unlisted"))
                        Text(Utils.t("Try this unlisted combination once", "本次尝试系统未声明的组合"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { onStart(slots, parked, allowUnlisted); parked = false; allowUnlisted = false },
                        enabled = !state.active && !state.reading && usable && queryUsable && parked &&
                            (declaration == ProbeDeclaration.DECLARED || allowUnlisted), modifier = Modifier.fillMaxWidth().testTag("probe-start")) {
                        Text(Utils.t("Start 20 s preview test", "开始 20 秒预览测试"))
                    }
                    if (state.active) Button(onStop, modifier = Modifier.fillMaxWidth().testTag("probe-stop")) { Text(Utils.t("Stop now", "立即停止")) }
                    HorizontalDivider()
                    Text(probePhaseLabel(state), modifier = Modifier.testTag("probe-status"), style = MaterialTheme.typography.titleMedium)
                    if (state.reason != null) Text(probeReasonLabel(state.reason), style = MaterialTheme.typography.bodySmall)
                    if (state.active) Text(Utils.t("Simultaneous preview: {0} / 20 s", "同时出帧：{0} / 20 秒", state.observationMs / 1000))
                    if (notice != null) Text(notice, style = MaterialTheme.typography.bodySmall)
                    if (state.report != null) {
                        Text(Utils.t("Report contains frame counts and release results, without images or location.", "报告包含出帧和释放结果，不含画面或位置。"), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onCopy, modifier = Modifier.fillMaxWidth().testTag("probe-copy")) { Text(Utils.t("Copy test report", "复制测试报告")) }
                        OutlinedButton(onSave, modifier = Modifier.fillMaxWidth().testTag("probe-save")) { Text(Utils.t("Save test report", "保存测试报告")) }
                        if (!state.reportSaved) Text(Utils.t("Local save is pending or failed. You can still copy or save the report here.", "本地保存尚未完成或失败，仍可在此复制或另存报告。"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected.isEmpty()) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Text(Utils.t("Cameras stay off until you start a test", "点击开始测试前，相机保持关闭"), color = Color.White)
                    } else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        selected.firstOrNull { it.slot == 0 }?.let { source ->
                            ProbePane(source, state, Modifier.weight(1.6f).fillMaxHeight(), preview)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            selected.filter { it.slot != 0 }.forEach { source ->
                                key(source.id, source.size) { ProbePane(source, state, Modifier.weight(1f), preview) }
                            }
                        }
                    }
                    Text(Utils.t("Cabin A/B follow your existing camera mapping. Confirm their physical positions from the picture. A passed preview does not verify multi-camera recording.",
                        "座舱 A/B 沿用已有摄像头映射，请根据画面确认实际位置。预览通过不代表已验证多路录像。"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProbePane(source: ProbeSource, state: ProbeUiState, modifier: Modifier, preview: @Composable (ProbeSource) -> Unit) {
    val frame = state.frames.firstOrNull { it.id == source.id }
    Card(modifier) {
        Column(Modifier.fillMaxSize()) {
            Text("${probeSourceName(source.slot)} · Camera ${source.id} · ${source.size}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) { key(source.id, source.size) { preview(source) } }
            Text(Utils.t("{0} frames · {1} fps", "{0} 帧 · {1} fps", frame?.frames ?: 0,
                frame?.averageFps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun probePhaseLabel(state: ProbeUiState): String = when (state.phase) {
    "IDLE" -> Utils.t("Not started", "尚未开始")
    "WAITING_FOR_IDLE" -> Utils.t("Waiting for camera release…", "正在等待相机释放…")
    "OPENING" -> Utils.t("Opening cameras…", "正在打开相机…")
    "CONFIGURING" -> Utils.t("Waiting for fresh frames…", "正在等待新画面…")
    "OBSERVING" -> Utils.t("Checking all streams…", "正在检查各路画面…")
    "STOPPING" -> Utils.t("Releasing cameras…", "正在释放相机…")
    "RELEASE_UNCONFIRMED" -> Utils.t("Camera release is unconfirmed", "相机释放尚未确认")
    else -> if (state.reason == "PREVIEW_OBSERVED") Utils.t("20 s preview observed · released", "已观测到 20 秒并发预览 · 已释放")
        else Utils.t("Test ended · cameras released", "测试结束 · 相机已释放")
}

private fun probeReasonLabel(reason: String): String = when {
    reason == "PREVIEW_OBSERVED" -> Utils.t("Every selected stream kept advancing. Recording remains untested.", "所选各路画面均持续更新，录像仍未测试。")
    reason == "USER_STOP" -> Utils.t("Stopped manually. No automatic restart.", "已手动停止，不会自动重启。")
    reason in setOf("PAGE_HIDDEN", "SCREEN_OFF", "SURFACE_DESTROYED") -> Utils.t("Stopped because the preview page or screen became unavailable.", "页面或屏幕已不可见，测试已停止。")
    reason == "FIRST_FRAMES_TIMEOUT" -> Utils.t("Not all cameras produced fresh frames within 15 seconds.", "15 秒内未等到所有相机的新画面。")
    reason == "STREAM_STALLED" -> Utils.t("One or more streams stopped advancing.", "一路或多路画面停止更新。")
    reason == "OBSERVATION_INTERRUPTED" -> Utils.t("Observation was interrupted; continuous preview is not proven.", "观测中断，无法确认持续并发预览。")
    reason in setOf("CAMERA_WORK_BUSY", "CAMERA_RELEASE_PENDING", "STOP_RECORDING_FIRST") -> Utils.t("Stop recording and close other camera previews before testing.", "请先停止录像并关闭其他相机预览。")
    reason == "UNLISTED_OPT_IN_REQUIRED" -> Utils.t("Confirm the one-time unlisted combination test first.", "请先勾选本次尝试未声明组合。")
    else -> Utils.t("Test detail: {0}", "测试详情：{0}", reason)
}
