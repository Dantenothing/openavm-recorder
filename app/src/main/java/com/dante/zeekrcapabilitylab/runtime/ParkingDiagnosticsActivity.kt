package com.dante.zeekrcapabilitylab.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.diagnostic.AwayJournal
import com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticUpload
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Developer-only entry. Opening this page does not enable a session or acquire resources. */
class ParkingDiagnosticsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=darkColorScheme()){Surface{Panel()}}}}
    @Composable private fun Panel() {
        val ui by C0ParkingService.state.collectAsState()
        val scope=rememberCoroutineScope()
        var message by remember{mutableStateOf("")}
        var busy by remember{mutableStateOf(false)}
        var parked by remember{mutableStateOf(false)}
        var holdCpu by remember{mutableStateOf(false)}
        var role by remember{mutableStateOf(RecordingSourceRole.SURROUND)}
        fun copy(value:String){getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM parking diagnostic",value));message="已复制，一次粘贴即可；不含视频。"}
        fun collect(c0:Boolean,upload:Boolean){
            if(busy)return;busy=true
            scope.launch {
                val result=withContext(Dispatchers.IO){runCatching {
                    val value=if(c0)C0Store(this@ParkingDiagnosticsActivity).reportForUser() ?: error("还没有后台实验记录")
                        else {
                            val fault=ShortRecorderDiagnostics.loadLastFault(this@ParkingDiagnosticsActivity)
                            if(upload)AwayJournal.cloudReport(fault) else AwayJournal.report(fault)
                        }
                    if(upload){DiagnosticUpload.submit(this@ParkingDiagnosticsActivity,value.toString().toByteArray());"已放入诊断上传队列；在重构体检页查看回执。"}
                    else value.toString()
                }}
                result.onSuccess{if(upload)message=it else copy(it)}.onFailure{message=it.message?.take(160) ?: "读取失败"}
                busy=false
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("离车与后台相机诊断 · ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.headlineSmall);TextButton(onClick={finish()}){Text("返回设置")}}
            Text("本版先做一轮普通录像离车复测。开始录像并确认计时后，再退到桌面、下车锁车；自然离车期间保持远程实验台关闭。",style=MaterialTheme.typography.bodyLarge)
            Card {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("A · 自然离车记录",style=MaterialTheme.typography.titleLarge)
                Text("只保存本机电源、录像和停止事件，每分钟补一条快照；不联网，不加唤醒锁，不改变现有停止规则。")
                Text("离车汇总会一并带上本轮录像停止原因与已保存的故障摘要，只需复制一次。",style=MaterialTheme.typography.bodySmall)
                Button(onClick={AwayJournal.startMarker();message="已标记。请回首页点击开始录像，确认计时在增加，再退到桌面并下车锁车。回来后只需复制一次离车汇总。"},enabled=!ui.active && !busy){Text("标记本次离车测试")}
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={collect(false,false)},enabled=!busy){Text("复制离车汇总 JSON")}
                    OutlinedButton(onClick={collect(false,true)},enabled=!busy && !ui.active){Text("上传离车汇总")}
                }
                Text("影子规则只生成诊断判断，不控制相机。上传由你点击后才进行。",style=MaterialTheme.typography.bodySmall)
            }}
            if(BuildConfig.C0_PARKING_EXPERIMENT_ENABLED) Card {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("C0 · 8 分钟后台相机实验",style=MaterialTheme.typography.titleLarge)
                Text("开始后关闭现有仅预览，先取 30 个新画面缓冲并释放相机；退到桌面、离车锁车后，云端预设流程在两次待命间隔后请求短时取帧。全程不录像、不上传图像，8 分钟到期收尾。")
                Text("这验证保持服务后能否重新取帧；不验证休眠唤醒、整车功耗或手机直播。需要先配对诊断接收器，不需要开启旧的 4 小时远程实验台。")
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected=role==RecordingSourceRole.SURROUND,onClick={role=RecordingSourceRole.SURROUND},label={Text("环视")},enabled=!ui.active)
                    FilterChip(selected=role==RecordingSourceRole.CABIN,onClick={role=RecordingSourceRole.CABIN},label={Text("Cabin")},enabled=!ui.active)
                }
                Row {Checkbox(holdCpu,{holdCpu=it},enabled=!ui.active);Text("实验期间保持 CPU 运行（最多 8 分钟，会改变耗电条件；首次可不勾选）",Modifier.padding(top=12.dp))}
                Row {Checkbox(parked,{parked=it},enabled=!ui.active);Text("车辆已停妥，允许本次短时后台相机及云端实验",Modifier.padding(top=12.dp))}
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick={message=C0ParkingService.start(this@ParkingDiagnosticsActivity,role,holdCpu) ?: "已启动。等状态变成 STANDBY 后退出桌面并锁车。"},enabled=parked && !ui.active && !busy){Text("开始 8 分钟实验")}
                    OutlinedButton(onClick={C0ParkingService.stop()},enabled=ui.active){Text("停止实验")}
                }
                Text("状态 ${ui.phase} · 剩余 ${ui.remainingSeconds} 秒 · 已关闭取帧 ${ui.captures} 次 · 云端应答 ${ui.replies} 次 · 释放 ${ui.cleanup}")
                ui.reason?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={collect(true,false)},enabled=!busy){Text("复制后台实验 JSON")}
                    OutlinedButton(onClick={collect(true,true)},enabled=!busy && !ui.active){Text("上传后台实验报告")}
                }
                Text("如果进程被系统结束，旧记录会保留；它不是后台能力通过。相机释放未确认时不继续重开。",style=MaterialTheme.typography.bodySmall)
            }}
            else Card {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("后台相机实验 · 暂未开放",style=MaterialTheme.typography.titleLarge)
                Text("本版只做上方的自然离车复测，先收齐录像停止证据。之前的后台实验报告仍可读取。")
                OutlinedButton(onClick={collect(true,false)},enabled=!busy){Text("复制之前的后台实验 JSON")}
            }}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.primary)
        }
    }
}
