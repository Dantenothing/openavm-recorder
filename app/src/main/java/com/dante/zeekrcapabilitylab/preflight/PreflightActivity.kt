package com.dante.zeekrcapabilitylab.preflight

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dante.zeekrcapabilitylab.mirror.MirrorHomeHost
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticCloudPanel
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticIdentity
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticPolicy
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticUpload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreflightActivity : ComponentActivity() {
    private var labHarness:com.dante.zeekrcapabilitylab.preflight.remote.LabHarness?=null
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xffa5c9dc),surface=Color(0xff182127),background=Color(0xff0e151a))) {
                val state by PreflightRuntime.state.collectAsState()
                val scope=rememberCoroutineScope()
                var parked by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var recoveryReady by remember { mutableStateOf(false) }
                var parts by remember { mutableStateOf<List<String>>(emptyList()) }
                var nextPart by remember { mutableIntStateOf(0) }
                var exporting by remember { mutableStateOf(false) }
                var identity by remember { mutableStateOf<DiagnosticIdentity?>(null) }
                var identityError by remember { mutableStateOf("") }
                var partsHash by remember { mutableStateOf("") }
                var baselinePreview by remember { mutableStateOf(false) }
                LaunchedEffect(state.phase,state.active) {
                    if(state.phase=="BASELINE")baselinePreview=true
                    else if(!state.active || state.phase=="WAITING_FOR_IDLE")baselinePreview=false
                }
                LaunchedEffect(state.active,state.phase,state.reportReady,recoveryReady) {
                    identity=null; identityError=""
                    if(!state.active) withContext(Dispatchers.IO) { runCatching {
                        DiagnosticPolicy.identity(PreflightStore(this@PreflightActivity).latestExport().first)
                    }}.onSuccess { identity=it }.onFailure { identityError=DiagnosticUpload.safeError(it) }
                }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { runCatching { PreflightRecovery.refreshInterruptedReport(this@PreflightActivity) } }
                    recoveryReady=true
                }
                fun copy(text:String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM Preflight",text)) }
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("OpenAVM Preflight",style=MaterialTheme.typography.headlineMedium)
                            Text("当前安装：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",style=MaterialTheme.typography.titleMedium)
                            identity?.let { report ->
                                val time=SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(Date(report.startedAt))
                                Text("显示的报告：${report.version} · $time · ${report.run.take(8)}",style=MaterialTheme.typography.bodyMedium)
                                if(report.version!=BuildConfig.VERSION_NAME) Text("这是旧版本的报告，不是本次安装版本的新测试结果。",color=MaterialTheme.colorScheme.error)
                            }
                            if(!state.active && identityError.isNotBlank() && identityError!="NO_REPORT")
                                Text("本轮报告暂不可用：$identityError。不会用旧报告冒充本轮。",color=MaterialTheme.colorScheme.error)
                            Text("真实相机连续分段 · P2",style=MaterialTheme.typography.titleMedium)
                            Text("Continuous camera recording + independent mirror",style=MaterialTheme.typography.bodySmall)
                            Text("先停止普通录像，停车并接好 USB，留在本页即可。开启远程会话后，电脑可以执行 20 秒、4 分钟和 10 分钟测试，自动切段、关闭再打开悬浮窗，并逐文件检查画面是否连续。")
                            Text("P2 会打开环视相机，右侧与悬浮窗显示实时后视画面。普通录像入口暂时保持原链路；此页验证新链路。",color=MaterialTheme.colorScheme.primary)
                            Text("短测 / 4 分钟 / 10 分钟分别需要约 1.4 / 4.7 / 10 GiB 空闲 USB。只清理由诊断账本确认且逐帧验证通过的测试视频；视频不会上传。页面不能自动确认 P 档或车速。",style=MaterialTheme.typography.bodySmall)
                            Row {
                                Checkbox(checked=parked,onCheckedChange={parked=it},enabled=!state.active)
                                Text("已停车，USB 已接好，准备留在此页面\nParked · USB connected",Modifier.padding(top=7.dp))
                            }
                            Button(enabled=parked && !state.active && !state.blocked,onClick={
                                if(!PreflightService.startCameraProbe(this@PreflightActivity))message="未开始：请先停止其他测试并确认相机权限。"
                            }) {Text("真实相机短测 · 20 秒（也可由电脑启动）")}
                            Button(enabled=parked && !state.active && !state.blocked,onClick={
                                parts=emptyList(); nextPart=0
                                if(!PreflightService.startSharedInput(this@PreflightActivity)) message="未开始：请等待上传完成，并停止录像或其他相机测试。"
                            }) { Text("合成画面对照诊断 · P1（可选）") }
                            OutlinedButton(enabled=parked && !state.active && !state.blocked,onClick={
                                parts=emptyList(); nextPart=0
                                if(!PreflightService.startSynthetic(this@PreflightActivity)) message="未开始：请先停止其他测试。"
                            }) { Text("基础直送诊断 · Beta17 对照（可选）") }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled=parked && !state.active && !state.blocked,onClick={
                                    parts=emptyList(); nextPart=0
                                    if(!PreflightService.start(this@PreflightActivity,true)) {
                                        message="未开始：请先停止录像或其他相机测试，并确认相机权限。"
                                        cameraPermission.launch(Manifest.permission.CAMERA)
                                    }
                                }) { Text("原链路长测 · 25 分钟") }
                                OutlinedButton(enabled=!state.active && !state.blocked,onClick={
                                    parts=emptyList(); nextPart=0
                                    if(!PreflightService.start(this@PreflightActivity,false)) message="未开始：请先停止录像或其他相机测试。"
                                }) { Text("只读能力检查") }
                            }
                            Text("原链路长测会打开相机，可选；无需先跑长测。只读检查只读取硬件声明。",style=MaterialTheme.typography.bodySmall)
                            if(state.active) Button(onClick={PreflightService.stop()},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.errorContainer)) { Text("停止并保留报告 / Stop") }
                            HorizontalDivider()
                            Text("${label(state.phase)} · ${state.elapsedSeconds/60}:${(state.elapsedSeconds%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.titleMedium)
                            if(state.message.isNotBlank()) Text(state.message,color=MaterialTheme.colorScheme.primary)
                            if(state.blocked) Text("资源收尾未确认。不要反复开始测试；先复制报告，再重启车机。",color=MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=identity!=null && !state.active && !exporting,onClick={
                                    exporting=true
                                    scope.launch {
                                        val result=withContext(Dispatchers.IO){runCatching {
                                            val (bytes,persisted)=PreflightStore(this@PreflightActivity).latestExport()
                                            PreflightReport.short(bytes,persisted)
                                        }}
                                        result.onSuccess { copy(it); message="短 JSON 已复制（${it.toByteArray().size/1024} KiB），可粘贴到邮件或聊天。" }
                                            .onFailure{message="暂时无法复制报告：${it.javaClass.simpleName}"}
                                        exporting=false
                                    }
                                }) { Text("复制短 JSON") }
                                OutlinedButton(enabled=identity!=null && !state.active && !exporting,onClick={
                                    exporting=true
                                    scope.launch {
                                        val result=withContext(Dispatchers.IO){runCatching {
                                                val (bytes,persisted)=PreflightStore(this@PreflightActivity).latestExport()
                                                val chunks=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP).chunked(48*1024)
                                                sha(bytes) to chunks.mapIndexed { i,chunk -> obj("schemaVersion" to 1,"format" to "OPENAVM_PREFLIGHT_JSON_PART",
                                                    "reportSha256" to sha(bytes),"persisted" to persisted,"partIndex" to i+1,"partCount" to chunks.size,"encoding" to "BASE64_UTF8","data" to chunk).toString() }
                                        }}
                                        result.onSuccess { (hash,ready) -> if(partsHash!=hash) nextPart=0; partsHash=hash; parts=ready; copy(ready[nextPart%ready.size]); message="完整 JSON 第 ${nextPart%ready.size+1}/${ready.size} 段已复制。粘贴后再点此按钮复制下一段。"; nextPart=(nextPart+1)%ready.size }
                                            .onFailure{message="完整报告暂不可用：${it.javaClass.simpleName}"}
                                        exporting=false
                                    }
                                }) { Text(if(parts.isEmpty()) "完整 JSON 分段复制" else "复制完整第 ${nextPart+1}/${parts.size} 段") }
                            }
                            if(message.isNotBlank()) Text(message)
                            Text("通常先发短 JSON 就够了。中途取消也能复制；需要进一步检查时再发完整分段，不用 ZIP。",style=MaterialTheme.typography.bodySmall)
                            state.results.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                            TextButton(onClick={finish()},enabled=!state.active) { Text("返回工具 / Back") }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            DiagnosticCloudPanel(state.active,identity!=null,parked,Modifier.fillMaxWidth().weight(1f))
                            if(state.phase=="BASELINE" || baselinePreview) {
                                Text("原链路长测预览 / Baseline preview",style=MaterialTheme.typography.titleMedium)
                                AndroidView(factory={MirrorHomeHost(it)},modifier=Modifier.fillMaxWidth().height(160.dp).background(Color.Black))
                            } else {
                                Text("连续链路实时预览 / P2 live preview",style=MaterialTheme.typography.titleMedium)
                                AndroidView(factory={com.dante.zeekrcapabilitylab.preflight.continuous.CameraProbePreviewHost(it)},
                                    modifier=Modifier.fillMaxWidth().height(160.dp).background(Color.Black))
                            }
                            Text("P2 会自动关闭再恢复悬浮窗。结束后报告自动上传；离开本页或熄屏会停止测试。",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    override fun onStart() { super.onStart(); visible=true;labHarness=com.dante.zeekrcapabilitylab.preflight.remote.LabHarness(this).also {it.start()} }
    override fun onStop() { labHarness?.stop();labHarness=null;visible=false; PreflightService.pageHidden(); super.onStop() }
    companion object {
        @Volatile var visible=false; private set
        private fun label(phase:String)=when(phase) {
            "READY"->"准备就绪"; "WAITING_FOR_IDLE"->"等待相机释放"; "PUBLIC_CAPABILITIES"->"读取公开能力"
            "EGL_SMALL"->"小型 GL 检查"; "USB_FD"->"USB 读写检查"; "CLOSED_FILE"->"分析已完成视频"
            "BASELINE"->"观察现有录像与预览"; "CLEANUP","FILES_CLEANUP"->"正在安全收尾"
            "P1_BASIC"->"连续编码与逐文件解码检查"
            "P1_INPUT"->"共享输入、慢读者与逐文件解码检查"
            "P2_CAMERA"->"真实相机、连续切段与悬浮窗检查"
            "COMPLETE_P2_CAMERA"->"真实相机测试已结束"
            "COMPLETE_P0","COMPLETE_P1_BASIC","COMPLETE_P1_INPUT"->"本轮已结束 · 请复制报告"; "CLEANUP_UNCONFIRMED"->"收尾未确认"; else->phase
        }
    }
}
