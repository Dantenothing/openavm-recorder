package com.dante.zeekrcapabilitylab.preflight.remote

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject

@Composable
internal fun RemoteLabPanel(parked:Boolean,testActive:Boolean,paired:Boolean) {
    val context=LocalContext.current
    val state by LabUi.state.collectAsState()
    val armed=state.flag("armed")
    val engine=state["engine"] as? JsonObject
    HorizontalDivider()
    Text("远程实验台",style=MaterialTheme.typography.titleLarge)
    Text("停车并保持车机清醒、联网和本页打开。电脑可以逐条下发实验、调整下一轮参数、查看进度和取消测试；同一版本无需反复安装。")
    if(!armed) {
        Button(enabled=paired && parked && !testActive && !state.flag("running"),onClick={
            runCatching {ContextCompat.startForegroundService(context,Intent(context,RemoteLabService::class.java)
                .setAction(RemoteLabService.START).putExtra("parked",true))}
                .onFailure {LabUi.update(com.dante.zeekrcapabilitylab.preflight.obj("message" to "远程服务未启动："+it.javaClass.simpleName))}
        }) {Text("开启远程实验会话 · 4 小时")}
        if(!paired)Text("先完成上方私人配对。",style=MaterialTheme.typography.bodySmall)
        if(!parked)Text("先勾选左侧停车和 USB 确认。",style=MaterialTheme.typography.bodySmall)
    } else {
        Text("会话 "+state.string("sessionId").take(8)+" · 剩余 "+state.number("remainingSeconds")/60+" 分钟")
        Text(if(state.flag("networkConnected"))"电脑连接通道正常" else "等待网络连接；当前实验按本地规则收尾")
        engine?.let {
            Text("当前阶段："+it.string("phase"))
            if(it.string("commandId").isNotEmpty())Text("任务 "+it.string("commandId").take(8)+" · "+it.string("outcome"),style=MaterialTheme.typography.bodySmall)
        }
        Button(onClick={context.startService(Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.END))},
            colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {Text("结束会话并停止测试")}
    }
    Text(state.string("message"),color=MaterialTheme.colorScheme.primary)
    Text("无需安装更新权限。P2 真实后视镜测试使用 App 已有的悬浮窗权限；缺失时会报告原因。离开本页会停止实验；断网后不会开始下一轮。",style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
}
