package com.dante.zeekrcapabilitylab.preflight.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.PreflightStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticCloudPanel(active: Boolean, reportAvailable: Boolean, parked:Boolean, modifier: Modifier=Modifier) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(BuildConfig.DIAGNOSTIC_ENDPOINT) }
    var code by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取上传状态") }
    var actionMessage by remember { mutableStateOf("") }
    var pending by remember { mutableIntStateOf(0) }
    suspend fun refresh() {
        val snapshot=withContext(Dispatchers.IO) {
            val settings=DiagnosticSettings(context); val outbox=DiagnosticOutbox(context)
            Triple(settings.load(),settings.enabled(),outbox.status() to outbox.pending().size)
        }
        paired=snapshot.first?.paired==true; enabled=snapshot.second
        status=snapshot.third.first; pending=snapshot.third.second
        snapshot.first?.let { endpoint=it.origin }
    }
    fun action(block: () -> Unit) {
        busy=true; actionMessage=""
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { actionMessage=DiagnosticUpload.safeError(it) }
            runCatching { refresh() }.onFailure { actionMessage=DiagnosticUpload.safeError(it) }
            busy=false
        }
    }
    LaunchedEffect(active) {
        if(!active) {
            runCatching { refresh(); withContext(Dispatchers.IO) { DiagnosticUpload.schedule(context) } }
                .onFailure { actionMessage=DiagnosticUpload.safeError(it) }
            while(true) { delay(2_000); if(!busy) runCatching { refresh() }.onFailure { actionMessage=DiagnosticUpload.safeError(it) } }
        }
    }
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("联网诊断",style=MaterialTheme.typography.titleLarge)
            Text("通过车载 4G / Wi-Fi 直接上传完整诊断 JSON。首次配对后，测试结束会自动上传；无需打开浏览器或发邮件。",style=MaterialTheme.typography.bodyMedium)
            if(!paired) {
                OutlinedTextField(endpoint,{endpoint=it},label={Text("接收服务 HTTPS 地址")},singleLine=true,enabled=!busy && !active,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(code,{code=it},label={Text("12 位私人配对码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy && !active,modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy && !active && endpoint.isNotBlank() && code.isNotBlank(),onClick={
                    val savedEndpoint=endpoint; val savedCode=code
                    action { DiagnosticUpload.pair(context,savedEndpoint,savedCode) }; code=""
                }) { Text("连接并启用自动上传") }
            } else {
                Text("已配对：$endpoint",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(enabled=!busy && !active,checked=enabled,onCheckedChange={on->action{DiagnosticUpload.enable(context,on)}})
                    Text("允许通过移动网络自动上传")
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy && !active,onClick={action{DiagnosticUpload.check(context)}}) { Text("检查连接") }
                    Button(enabled=enabled && !busy && !active && reportAvailable,onClick={action {
                        DiagnosticUpload.submit(context,PreflightStore(context).latestExport().first)
                        DiagnosticUpload.retry(context)
                    }}) { Text("上传显示的报告") }
                }
                if(pending>0) OutlinedButton(enabled=enabled && !busy && !active,onClick={action{DiagnosticUpload.retry(context)}}) { Text("重试待上传（$pending）") }
            }
            Text(if(active) "正在测试 · 结束后再上传" else status,color=MaterialTheme.colorScheme.primary)
            if(actionMessage.isNotBlank()) Text(actionMessage,color=MaterialTheme.colorScheme.error)
            Text("只上传体检报告，不上传录像、位置或账号密码。只有云端返回匹配回执才显示成功。断网时保存在本机；系统休眠期间可能延后，重新打开本页会继续安排补传。",style=MaterialTheme.typography.bodySmall)
            com.dante.zeekrcapabilitylab.preflight.remote.RemoteLabPanel(parked,active,paired)
        }
    }
}
