package com.dante.zeekrcheck

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode

@Composable internal fun LanguageSettings() {
    val context = LocalContext.current
    AssistantCard {
        UiText("语言 / Language", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        UiText("车控、影像和桌面卡片使用同一种语言。", color = AssistantMuted)
        listOf(PhoneLanguageMode.SYSTEM, PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH).forEach { mode ->
            OutlinedButton(onClick = {
                PhoneLanguage.selectMode(mode)
                VehicleWidgetProvider.updateAll(context)
            }, modifier = Modifier.fillMaxWidth().testTag("language_${mode.storedValue}")) {
                UiText((if (PhoneLanguage.mode == mode) "✓  " else "") + when (mode) {
                    PhoneLanguageMode.SYSTEM -> "跟随系统 / System"
                    PhoneLanguageMode.SIMPLIFIED_CHINESE -> "简体中文"
                    else -> "English"
                }, raw = true)
            }
        }
    }
}

@Composable internal fun RenameVehicleDialog(initialName: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = onClose, title = { UiText("车辆昵称") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 20) name = it },
                label = { UiText("给你的车起个名字") }, singleLine = true,
                supportingText = { UiText("${name.length}/20") }, modifier = Modifier.testTag("vehicle_nickname"))
            UiText("首页和桌面卡片会一起更新。", color = AssistantMuted)
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("save_nickname")) { UiText("保存") }
    }, dismissButton = { TextButton(onClick = onClose) { UiText("取消") } })
}

internal object BeginnerGuide {
    const val VERSION = 5
    const val STEPS = 7
    fun preferences(context: Context) = context.getSharedPreferences("openavm_beginner", Context.MODE_PRIVATE)
    fun complete(context: Context) = preferences(context).edit().putInt("completed", VERSION).apply()
    fun position(context: Context) = preferences(context).getInt("step", 0).coerceIn(0, STEPS - 1)
    fun savePosition(context: Context, step: Int) = preferences(context).edit().putInt("step", step.coerceIn(0, STEPS - 1)).apply()
}

/** Navigation only: reading the guide never enables a rule, grants a permission or sends a command. */
@Composable internal fun BeginnerGuideScreen(connected: Boolean, homeSet: Boolean, onNavigate: (String) -> Unit,
    onDone: () -> Unit, onFinish: () -> Unit = onDone, initialStep: Int = 0, onStepChanged: (Int) -> Unit = {}) {
    var step by rememberSaveable { mutableIntStateOf(initialStep.coerceIn(0, BeginnerGuide.STEPS - 1)) }
    var directory by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val titles = listOf("欢迎使用 OpenAVM", "连接你的车辆", "告诉助手哪里是家", "设好舒适偏好", "把常用操作放到桌面", "让自动化可靠运行", "连接车机录像（可选）")
    fun moveTo(value: Int) { step = value; onStepChanged(value) }
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UiText("新手指南", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone, modifier = Modifier.testTag("guide_later")) { UiText("稍后继续") }
        }
        LinearProgressIndicator(progress = { (step + 1).toFloat() / titles.size }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UiText("${step + 1} / ${titles.size}", color = AssistantGreen, modifier = Modifier.weight(1f))
            TextButton(onClick = { directory = true }, modifier = Modifier.testTag("guide_directory")) { UiText("选择步骤") }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).testTag("guide_body"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            UiText(titles[step], fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("guide_title"))
            if (step == 1 && connected || step == 2 && homeSet) UiText(if (step == 1) "车辆已连接" else "家的位置已保存", color = AssistantGreen, fontWeight = FontWeight.SemiBold)
            AssistantCard { when (step) {
            0 -> {
                UiText("看车况、提前备车、停车守护，把每天用车的几件事放在一起。")
                UiText("先用影像，云端连接可稍后设置")
                UiText("未配置不影响车机配对、录像传输、播放和导出。")
                Button(onClick = { onNavigate("connection") }, modifier = Modifier.fillMaxWidth()) { UiText("连接车机录像（可选）") }
                UiText("不必一次完成。进度会自动保存，随时在「更多 → 新手指南」继续。", color = AssistantMuted)
                FeatureRow("语言 / Language", "简体中文 / English") { onNavigate("language") }
            }
            1 -> {
                UiText(if (connected) "已经可以读取车况，直接下一步即可。" else "登录极氪账号 → 选择车辆。guest 账号需要主账号已授予车辆权限。")
                UiText("使用云端车况和控制前，请导入你自行准备且有权使用的连接配置，再登录自己的极氪账号。OpenAVM 不内置或下载厂商连接参数。")
                UiText("未配置不影响车机配对、录像传输、播放和导出。")
                UiText("登录加密保存在这台手机；换手机时需要重新登录，不会随安装包带过来。", color = AssistantMuted)
                Button(onClick = { onNavigate("account") }, modifier = Modifier.fillMaxWidth()) { UiText(if (connected) "查看连接" else "去连接车辆") }
            }
            2 -> {
                UiText("输入部分地址并选择匹配结果，或把手机当前位置设为家。家的范围可调整，容忍车辆定位偏移。")
                UiText("保存地址后，在停车守护里分别开启「离家开启」和「到家关闭」。保存地址本身不会开启规则。")
                UiText("手动关闭哨兵后，本次停车不会再自动开启。", color = AssistantMuted)
                Button(onClick = { onNavigate("places") }, modifier = Modifier.fillMaxWidth()) { UiText("设置家的位置") }
            }
            3 -> {
                UiText("选好目标温度、最长时长，以及是否允许座椅通风或加热。保存偏好不会启动备车。")
                UiText("点一次开始，再点同一个按钮停止。卡片会显示进度；收到车辆回传后，才会确认结果。")
                UiText("琥珀气流表示预热，蓝色表示预冷。车温旁的时间告诉你读数有多新。", color = AssistantMuted)
                Button(onClick = { onNavigate("preferences") }, modifier = Modifier.fillMaxWidth()) { UiText("设置备车偏好") }
            }
            4 -> {
                UiText("先预览，再选适合桌面的大小。小卡片有备车、找车、车锁、哨兵；大卡片还显示更多车况。")
                UiText("点图标直接操作，进度留在卡片上；箭头打开应用。尾门按钮只解锁，需要时到车尾按实体按键。")
                UiText("顶部刷新只读取车况。温度旁的刷新按钮单独更新车温，可能短暂开启空调；再次点击可结束取温。后台刷新只读车况。", color = AssistantMuted)
                Button(onClick = { onNavigate("widgets") }, modifier = Modifier.fillMaxWidth()) { UiText("选择桌面卡片") }
            }
            5 -> {
                UiText("按后台设置页的提示检查通知、电池和预约权限，再启用需要的守护或预约。")
                UiText("省电限制和网络可能延迟更新。刷新成功表示取到了云端记录，不保证车辆刚刚测量；请一起看数据时间。")
                UiText("换主力手机时，先暂停旧手机的预约和自动守护，避免两台手机重复执行。", color = AssistantMuted)
                Button(onClick = { onNavigate("background") }, modifier = Modifier.fillMaxWidth()) { UiText("检查后台设置") }
            }
            else -> {
                UiText("在车旁时再设置即可。手机和车机连同一网络，传送已有录像，不需要私人服务器。")
                com.dante.zeekrbridge.ui.RecorderConnectionGuide()
                Button(onClick = { onNavigate("connection") }, modifier = Modifier.fillMaxWidth().testTag("guide_recorder")) { UiText("连接车机录像（可选）") }
                UiText("首次配对后会保存连接。传完可停止接收，已收到的录像离线也能播放。", color = AssistantMuted)
            }
            } }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { moveTo(step - 1) }, enabled = step > 0, modifier = Modifier.weight(1f).testTag("guide_previous")) { UiText("上一步") }
            Button(onClick = { if (step < titles.lastIndex) moveTo(step + 1) else onFinish() }, modifier = Modifier.weight(1f).testTag("guide_next")) {
                UiText(if (step < titles.lastIndex) "下一步" else "回到首页")
            }
        }
    }
    if (directory) AlertDialog(onDismissRequest = { directory = false }, title = { UiText("选择步骤") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            titles.forEachIndexed { index, title ->
                TextButton(onClick = { moveTo(index); directory = false }, modifier = Modifier.fillMaxWidth().testTag("guide_step_$index")) {
                    UiText("${index + 1}  ·  ${ui(title)}", raw = true, modifier = Modifier.fillMaxWidth(), fontWeight = if (index == step) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { directory = false }) { UiText("返回") } })
}
