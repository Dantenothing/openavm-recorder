package com.dante.zeekrcapabilitylab.preflight.remote

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.serialization.json.JsonObject

@Composable
internal fun RemoteLabPanel(parked:Boolean,testActive:Boolean,paired:Boolean) {
    val context=LocalContext.current
    val state by LabUi.state.collectAsState()
    val armed=state.flag("armed")
    val engine=state["engine"] as? JsonObject
    var updates by rememberSaveable {mutableStateOf(BuildConfig.RECORDER_SUPPORT_ENABLED)}
    var installAllowed by remember {mutableStateOf(context.packageManager.canRequestPackageInstalls())}
    var installHelp by rememberSaveable {mutableStateOf<String?>(null)}
    var showInstallAlternatives by rememberSaveable {mutableStateOf(false)}
    // Attempt the documented Intent even when resolveActivity is filtered by package visibility.
    // Opening a settings page is never evidence that installation permission was granted.
    fun openInstallSettings(action:String, forPackage:Boolean, route:String) {
        val intent=Intent(action).apply {if(forPackage)data=Uri.parse("package:"+context.packageName)}
        val result=runCatching {context.startActivity(intent)}
        showInstallAlternatives=true
        installHelp=if(result.isSuccess) Utils.t(
            "Settings was requested. Enable installation for OpenAVM, then return. If no page appeared or no switch is available, try another settings entry or continue with diagnostics only.",
            "已请求打开设置。允许 OpenAVM 安装更新后返回；若没有打开页面或找不到开关，可尝试其他设置入口，或先仅开启诊断。")
        else Utils.t(
            "This system could not open that settings page. Try another entry below, or continue with diagnostics only. Downloading an APK and allowing OpenAVM to install it are separate steps.",
            "此系统无法打开该设置页。可尝试下面的其他入口，或先仅开启诊断。下载 APK 和允许 OpenAVM 安装它是两个不同步骤。")
        EventLogger.logEvent(Categories.SYSTEM,"SUPPORT_INSTALL_SETTINGS_REQUEST",payload=mapOf(
            "route" to route,"launch" to if(result.isSuccess) "REQUEST_ACCEPTED" else "FAILED",
            "errorType" to (result.exceptionOrNull()?.javaClass?.simpleName ?: "NONE")))
    }
    LaunchedEffect(armed,state.string("message")) {installAllowed=context.packageManager.canRequestPackageInstalls()}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)installAllowed=context.packageManager.canRequestPackageInstalls()
        }
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    HorizontalDivider()
    Text(if(BuildConfig.RECORDER_SUPPORT_ENABLED) "远程诊断会话" else "远程实验台",style=MaterialTheme.typography.titleLarge)
    Text(if(BuildConfig.RECORDER_SUPPORT_ENABLED) Utils.t("One connection for up to four hours: bounded tests, automatic reports and optional signed App updates. An update returns to this page; interrupted tests are not replayed.",
        "一次连接最长 4 小时：批量检查、自动报告，并可选择接收签名更新。更新后返回本页，已中断的测试不会自行重跑。")
        else "停车并保持车机清醒、联网和本页打开。电脑可以逐条下发实验、调整下一轮参数、查看进度和取消测试；同一版本无需反复安装。")
    if(!armed) {
        if(BuildConfig.RECORDER_SUPPORT_ENABLED) {
            Row { Checkbox(updates,{updates=it});Text(Utils.t("Allow OpenAVM support updates during this session", "允许在本次会话内更新 OpenAVM 诊断版"),Modifier.padding(top=10.dp)) }
            if(updates && !installAllowed) {
                OutlinedButton(onClick={openInstallSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,true,"APP_SOURCE")}) {
                    Text(Utils.t("Allow App updates once", "先允许此 App 安装更新"))
                }
                installHelp?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                if(showInstallAlternatives) {
                    OutlinedButton(onClick={openInstallSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,false,"SOURCE_LIST")}) {
                        Text(Utils.t("Open installation-source settings", "打开安装来源设置列表"))
                    }
                    OutlinedButton(onClick={openInstallSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true,"APP_DETAILS")}) {
                        Text(Utils.t("Open OpenAVM App info", "打开 OpenAVM 应用信息"))
                    }
                }
                TextButton(onClick={updates=false;installHelp=null;showInstallAlternatives=false}) {
                    Text(Utils.t("Continue with diagnostics only", "先仅开启诊断，不启用自动更新"))
                }
                Text(Utils.t("Android may still require confirmation. Unattended updating is confirmed only after an actual update succeeds.",
                    "系统仍可能要求确认；实际更新成功前，不会显示为已验证的无人值守更新。"),style=MaterialTheme.typography.bodySmall)
            }
            if(!updates)Text(Utils.t("Diagnostics and automatic report upload remain available. This session will not install App updates.",
                "仍可进行诊断和自动上传报告；本次会话不会安装 App 更新。"),style=MaterialTheme.typography.bodySmall)
        }
        Button(enabled=paired && parked && !testActive && !state.flag("running") && (!updates || installAllowed),onClick={
            runCatching {ContextCompat.startForegroundService(context,Intent(context,RemoteLabService::class.java)
                .setAction(RemoteLabService.START).putExtra("parked",true).putExtra("allowUpdates",updates))}
                .onFailure {LabUi.update(com.dante.zeekrcapabilitylab.preflight.obj("message" to "远程服务未启动："+it.javaClass.simpleName))}
        }) {Text("开启远程实验会话 · 4 小时")}
        if(!paired)Text("先完成上方私人配对。",style=MaterialTheme.typography.bodySmall)
        if(!parked)Text("先勾选左侧停车确认。",style=MaterialTheme.typography.bodySmall)
    } else {
        Text("会话 "+state.string("sessionId").take(8)+" · 剩余 "+state.number("remainingSeconds")/60+" 分钟")
        Text(if(state.flag("networkConnected"))"电脑连接通道正常" else "等待网络连接；当前实验按本地规则收尾")
        engine?.let {
            Text("当前阶段："+it.string("phase"))
            if(it.string("commandId").isNotEmpty())Text("任务 "+it.string("commandId").take(8)+" · "+it.string("outcome"),style=MaterialTheme.typography.bodySmall)
        }
        if(state.flag("allowUpdates")) {
            val update=state["update"] as? JsonObject
            Text(Utils.t("Support updates enabled for this session", "本次会话已允许诊断版更新"),style=MaterialTheme.typography.bodySmall)
            if(update?.string("phase")=="WAITING_USER") {
                Text(Utils.t("This system requires installation confirmation. Automatic tests are paused.", "系统需要确认安装，自动检查已暂停。"))
                Button(onClick={context.startService(Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.CONFIRM_UPDATE))}) {
                    Text(Utils.t("Open system confirmation", "打开系统安装确认"))
                }
            }
        }
        Button(onClick={context.startService(Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.END))},
            colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {Text("结束会话并停止测试")}
    }
    Text(state.string("message"),color=MaterialTheme.colorScheme.primary)
    Text(if(BuildConfig.RECORDER_SUPPORT_ENABLED) Utils.t("Keep the vehicle awake and this page visible. Leaving or ending the session stops tests. Updates accept only this App with the same signing identity; no remote scripts or recording deletion.",
        "保持车机清醒并停在本页；离开或结束会话会停止检查。更新仅接受同包名、同签名的诊断版，不接受远程脚本或删除录像。")
        else "无需安装更新权限。P2 真实后视镜测试使用 App 已有的悬浮窗权限；缺失时会报告原因。离开本页会停止实验；断网后不会开始下一轮。",style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
}
