package com.dante.zeekrcapabilitylab.diagnostic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.mirror.MirrorHomeHost
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticCloudPanel
import com.dante.zeekrcapabilitylab.preflight.remote.LabUi
import com.dante.zeekrcapabilitylab.preflight.remote.flag
import com.dante.zeekrcapabilitylab.preflight.remote.string
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Composable internal fun SupportScreen() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lab by LabUi.state.collectAsState()
    val engine=lab["engine"] as? JsonObject
    val active=engine?.flag("active")==true
    var parked by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(SupportRound.current().id) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    MaterialTheme(colorScheme=darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(Utils.t("Online recording diagnostics", "录像在线诊断"),style=MaterialTheme.typography.headlineSmall)
                    Text(BuildConfig.VERSION_NAME)
                    Text(Utils.t("This private session lets your developer run the actual preview → recording → Stop → preview controls and receive diagnostic reports. Each sequence stops on failure. Video and location are not uploaded.",
                        "开启私人会话后，开发者可运行实际的预览 → 录像 → 停止 → 预览流程并接收诊断报告。发生失败就停止本轮，不上传视频或位置。"))
                    Text(Utils.t("Preview comparison checks one camera with two initialization orders. Each order uses one attempt, records no video, and keeps the normal preview settings unchanged.",
                        "预览对照可检查单个摄像头的两种初始化顺序。每种顺序只尝试一次、不录像，也不改变日常预览设置。"), style=MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(parked,{parked=it},enabled=!lab.flag("armed") && !active)
                        Text(Utils.t("Parked. Keep this page open, screen on and network connected. Selected cameras may record test clips using the current recording settings.",
                            "已停车。保持本页打开、亮屏并联网；检查可能按当前录像设置录制所选摄像头的测试片段。"),Modifier.padding(top=10.dp))
                    }
                    OutlinedButton(enabled=!active && !busy,onClick={
                        round=SupportRound.begin().id
                        message=Utils.t("New round started. Earlier evidence is retained separately.","已开始新一轮；此前证据单独保留。")
                    }) {Text(Utils.t("Start a fresh diagnostic round", "开始新一轮诊断"))}
                    Text(Utils.t("Round {0}. New events are grouped by time and recording session. This does not reset the camera or delete recordings.",
                        "轮次 {0}。新日志按时间和录像会话单独归档；不会重置相机或删除录像。",round.take(8)),style=MaterialTheme.typography.bodySmall)
                    Button(enabled=!active && !busy,onClick={
                        busy=true
                        scope.launch {
                            val result=withContext(Dispatchers.IO) {runCatching { SupportRound.save(context.applicationContext) }}
                            message=if(result.isSuccess) Utils.t("Snapshot saved. If upload is enabled, it is queued for the private receiver.","诊断已保存；启用上传后将发送到私人接收服务。")
                                else Utils.t("Could not prepare the snapshot.","无法生成诊断快照。")
                            busy=false
                        }
                    }) {Text(Utils.t("Collect current diagnostics", "采集当前诊断并上传"))}
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(message)
                    Text(Utils.t("Product preview", "实际产品预览"),style=MaterialTheme.typography.titleMedium)
                    AndroidView(factory={MirrorHomeHost(it)},modifier=Modifier.fillMaxWidth().height(200.dp).background(Color.Black))
                    Text(Utils.t("Keep existing recordings for comparison. Optional support updates preserve this session's original expiry. A sleeping vehicle cannot be woken by this page.",
                        "保留已有录像用于对照。可选的诊断版更新会保留本次会话原定的到期时间；本页不能唤醒已休眠的车辆。"),style=MaterialTheme.typography.bodySmall)
                }
                DiagnosticCloudPanel(active,true,parked,Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
