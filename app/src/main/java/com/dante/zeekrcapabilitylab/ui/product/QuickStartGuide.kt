package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.util.Utils

/** Help is presentation only: opening, closing and paging never issue camera commands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickStartEntry() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("quick_start_v1", 0) }
    var compact by remember { mutableStateOf(preferences.getBoolean("dismissed", false)) }
    var showing by rememberSaveable { mutableStateOf(false) }
    if (compact) {
        TextButton(onClick = { showing = true }) { Text(Utils.t("Quick start & help", "新手教程与帮助")) }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Utils.t("New to OpenAVM?", "第一次使用 OpenAVM？"), fontWeight = FontWeight.SemiBold)
                Text(Utils.t("A short guide to USB recording, the floating mirror and saved events.",
                    "快速了解 USB 录像、悬浮后视镜与紧急视频。"), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showing = true }) { Text(Utils.t("Open guide", "查看教程")) }
                    TextButton(onClick = {
                        compact = true
                        preferences.edit().putBoolean("dismissed", true).apply()
                    }) { Text(Utils.t("Hide this tip", "收起提示")) }
                }
            }
        }
    }
    if (showing) QuickStartGuide(onDismiss = { showing = false })
}

@Composable
internal fun QuickStartGuide(onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf(
        Utils.t("1 · Get ready", "1 · 开始前") to Utils.t(
            "Park before setting up. Connect a writable USB drive and select USB preferred in Settings. On the recording page, check Storage destination before starting; if it shows Head unit, the video will use internal storage.\n\nThe Android phone app is optional. Camera availability depends on the vehicle and its firmware.",
            "请停车后设置。插入可写的 U 盘，在设置中选择“USB 优先”。开始前查看录像页的“录像存储位置”；显示“车机”时会使用内置存储。\n\n安卓手机 App 不是必装的。可用摄像头取决于车辆与固件。"),
        Utils.t("2 · Record and stop", "2 · 开始与停止录像") to Utils.t(
            "Choose Surround, Cabin or Infrared, then Normal or Time-lapse. Tap Start recording and check the recording status. A live picture marked Preview only is not being saved.\n\nTo finish, tap Stop and wait for saving to complete before removing USB. Recording after you leave depends on vehicle power; it is not guaranteed parking coverage.",
            "选择环视、车内或红外，再选择普通或延时。点击“开始录像”并确认录像状态；显示“仅预览”时，画面不会保存为录像。\n\n结束时点击“停止”，等保存完成再拔 U 盘。离车后的录像取决于车辆供电，不保证停车全程覆盖。"),
        Utils.t("3 · Use the floating mirror", "3 · 使用悬浮后视镜") to Utils.t(
            "Enable Floating mirror in Settings and allow overlay access if the vehicle provides it. The window appears when OpenAVM is in the background. Tap around the car logo for Front/Rear/Left/Right, or its centre for Cabin; the grid button shows four surround views.\n\nDrag the header to move and the lower corner to resize. Use Standard/Fisheye to change the view. The arrow hides the picture; closing the window does not stop recording. Switching to Cabin during recording changes the recording source.",
            "在设置中开启“悬浮后视镜”；车机提供权限页时允许悬浮显示。OpenAVM 进入后台后才显示窗口。点击车标四周选择前后左右，点中间切换车内；四宫格按钮显示四路环视。\n\n拖动标题移动，拉下角调整窗口大小，点击标准／鱼眼切换视角。箭头可收起画面；关闭窗口不会停止已开始的录像。录像时切到车内，录像源也会随之切换。"),
        Utils.t("4 · When you return", "4 · 回车后自动恢复") to Utils.t(
            "In Settings → When you return, choose Logo or Full floating window. Tap the Logo to restore preview; the full window restores it after the screen turns on and unlocks. To record as well, turn on Also start recording.\n\nIf Waiting for camera appears, allow up to 15 seconds. Check the recording status before relying on it. Saving settings does not start recording now, and stopping manually prevents another automatic start during that return.\n\nConfirm your choice again after an installation or update. If the head unit restarts or the system closes OpenAVM, reopen the app.",
            "在“设置 → 回车后的行为”选择 Logo 或完整悬浮窗。Logo 点一下恢复预览；完整悬浮窗会在亮屏解锁后自动恢复。想同时录像，再打开“同时自动开始录像”。\n\n看到“正在等待相机就绪”时，可等待最多 15 秒；请以实际录像状态为准。保存设置不会立即录像，手动停止后当次不会再次自动开始。\n\n每次安装或升级后重新确认一次；车机重启或系统关闭 OpenAVM 后，需要重新打开应用。"),
        Utils.t("5 · Keep an important moment", "5 · 保存重要时刻") to Utils.t(
            "During normal recording, tap Save emergency video on the home page, or SOS in the floating window or its menu. Recording continues. Available previous two segments, the current segment and the following segment are marked for protection.\n\nCurrent and following segments must finish before they can be saved. Check the result message, then find them in Library → Events. Protection prevents automatic cleanup; it does not replace a backup. Time-lapse does not support this live shortcut.",
            "普通录像时，点击首页“保存紧急视频”，或悬浮窗的“紧急”按钮／菜单。录像会继续，系统标记可用的前两段、当前段和后一段。\n\n当前段与后一段完成后才能保存。请看结果提示，再到“记录 → 紧急事件”查看。保护可防止自动清理，但不能代替备份；延时录像不支持此实时快捷操作。"),
        Utils.t("6 · Find and manage videos", "6 · 查找与管理录像") to Utils.t(
            "In Library, choose a category and Today, Yesterday or a date. Dates use the recording start time. Tap a recording to play it and choose its segments; one session may contain several files.\n\nUse Select to delete the visible recordings you choose, or each recording's menu. Stop recording and wait for saving before USB deletion. Factory Sentry videos are read-only. Use All dates to leave the date filter.",
            "在记录页选择分类，再选今天、昨天或指定日期；按录像开始日期归类。点开录像即可播放和选择分段，同一次录像可能包含多个文件。\n\n点“多选”删除当前列表中选中的录像，也可使用单条菜单。删除 USB 视频前请停止录像并等保存完成。原车哨兵视频只读。点击“全部日期”取消日期筛选。"),
        Utils.t("7 · Phone, sounds and help", "7 · 手机、音效与帮助") to Utils.t(
            "For phone transfer, connect the car to your Android phone's hotspot. Start receiving in OpenAVM Companion, then enter its address and pairing code under Phone / Tools. Pairing is remembered. For new recording formats, use a compatible Companion release.\n\nSound tools also work on the car: import local music/video, trim and save WAV to USB, then select it in the vehicle's settings. The factory list may refresh only after leaving and returning or reconnecting USB. Reopen this guide from Settings at any time.",
            "需要传手机时，让车机连接安卓手机热点。在 OpenAVM Companion 开启接收，再到“手机 / 工具”输入地址与配对码；配对会被记住。新录像格式需要兼容版本的 Companion。\n\n车机也可独立制作音效：导入本地音乐／视频，裁剪后将 WAV 保存至 USB，再到原车设置选择。原车列表可能要离车再回车或重新连接 USB 才刷新。随时可从设置重看本教程。"),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 800.dp).fillMaxWidth().heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Utils.t("Quick start & help", "新手教程与帮助"), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
                }
                LinearProgressIndicator(progress = { (page + 1f) / pages.size }, modifier = Modifier.fillMaxWidth())
                key(page) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(pages[page].first, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(pages[page].second, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = page > 0, onClick = { page = (page - 1).coerceAtLeast(0) }) {
                        Text(Utils.t("Previous", "上一步"))
                    }
                    Text("${page + 1} / ${pages.size}", Modifier.weight(1f).padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Button(onClick = { if (page == pages.lastIndex) onDismiss() else page++ }) {
                        Text(if (page == pages.lastIndex) Utils.t("Done", "完成") else Utils.t("Next", "下一步"))
                    }
                }
            }
        }
    }
}
