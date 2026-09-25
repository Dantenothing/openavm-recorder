package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.R
import com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService
import com.dante.zeekrcapabilitylab.mirror.MirrorReturnMode
import com.dante.zeekrcapabilitylab.mirror.MirrorReturnSettings
import com.dante.zeekrcapabilitylab.util.Utils

internal fun MirrorReturnMode.summary(): String = when (this) {
    MirrorReturnMode.OFF -> Utils.t("Exit on screen-off", "熄屏后退出悬浮窗")
    MirrorReturnMode.LOGO -> Utils.t("Show Logo · tap for preview", "显示 Logo · 点击恢复预览")
    MirrorReturnMode.PREVIEW -> Utils.t("Restore full window · preview only", "恢复完整悬浮窗 · 仅预览")
    MirrorReturnMode.RECORD -> Utils.t("Restore full window · record automatically", "恢复完整悬浮窗 · 自动录像")
}

@Composable
internal fun MirrorReturnSettingsEntry() {
    if (!BuildConfig.MIRROR_RETURN_ENABLED) return
    val context = LocalContext.current
    val store = remember(context) { MirrorReturnSettings(context) }
    var mode by remember { mutableStateOf(store.selected) }
    var showing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("return-settings-entry")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Utils.t("When you return", "回车后的行为"), style = MaterialTheme.typography.titleMedium)
                Text(mode.summary(), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { showing = true }) { Text(Utils.t("Change", "更改")) }
        }
    }
    if (showing) MirrorReturnSetupDialog(store, installationPrompt = false, onDismiss = { showing = false }, onSaved = {
        mode = store.selected; showing = false
    })
}

/** These choices only apply on a future sleep/return cycle. No camera starts from this dialog. */
@Composable
internal fun MirrorReturnSetupDialog(store: MirrorReturnSettings, installationPrompt: Boolean,
                                     onDismiss: () -> Unit, onSaved: () -> Unit) {
    var retained by rememberSaveable { mutableStateOf(store.selected.retained) }
    var full by rememberSaveable { mutableStateOf(store.selected.automatic) }
    // Each installation asks for a fresh explicit recording choice. Never pre-check a previous opt-in.
    var autoRecord by rememberSaveable { mutableStateOf(!installationPrompt && store.selected == MirrorReturnMode.RECORD) }
    val mode = when { !retained -> MirrorReturnMode.OFF; !full -> MirrorReturnMode.LOGO
        autoRecord -> MirrorReturnMode.RECORD; else -> MirrorReturnMode.PREVIEW }
    fun save(value: MirrorReturnMode) {
        store.save(value)
        FloatingMirrorService.returnSettingsChanged()
        onSaved()
    }
    Dialog(onDismissRequest = { if (!installationPrompt) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !installationPrompt, dismissOnClickOutside = !installationPrompt)) {
        Surface(Modifier.padding(18.dp).widthIn(max = 800.dp).fillMaxWidth().heightIn(max = 620.dp)
            .testTag("return-setup-dialog"), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.avm_launcher_foreground), contentDescription = null, Modifier.size(68.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(Utils.t("Ready when you return", "回车后，按你的习惯恢复"),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (installationPrompt) Utils.t("Confirm once after each installation. Change this anytime in Settings.",
                            "每次安装后确认一次，也可以随时在设置中更改。") else Utils.t("Applies the next time you leave and return.",
                            "下次离车再回车时生效。"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Utils.t("Keep a floating entry after screen-off", "离车熄屏后保留悬浮入口"), fontWeight = FontWeight.SemiBold)
                            Text(Utils.t("Off: reopen OpenAVM when you return.", "关闭后，回车时需要重新打开 OpenAVM。"), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(retained, { retained = it }, Modifier.testTag("return-retain"))
                    }
                    if (retained) {
                        Text(Utils.t("Show on return", "回车后显示"), fontWeight = FontWeight.SemiBold)
                        ReturnOption(!full, Utils.t("Logo", "Logo 入口"),
                            Utils.t("Large OpenAVM logo. Tap to restore preview; long-press for actions.",
                                "醒目的 OpenAVM 车标，点一下恢复预览，长按打开菜单。"), "return-logo") { full = false; autoRecord = false }
                        ReturnOption(full, Utils.t("Full floating window", "完整悬浮窗"),
                            Utils.t("Restore the live picture after the screen turns on and unlocks.",
                                "亮屏并解锁后，自动恢复实时画面。"), "return-full") { full = true }
                        if (full) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Utils.t("Also start recording", "同时自动开始录像"), fontWeight = FontWeight.SemiBold)
                                    Text(Utils.t("Surround · Normal recording · your existing storage settings. Off means preview only.",
                                        "环视 · 普通录像 · 沿用当前存储设置。关闭时只预览，不保存视频。"), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(autoRecord, { autoRecord = it }, Modifier.testTag("return-auto-record"))
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(mode.summary(), Modifier.fillMaxWidth().padding(14.dp).testTag("return-summary"), fontWeight = FontWeight.SemiBold)
                    }
                    Text(Utils.t("Requires camera and overlay permissions with Floating mirror enabled. Saving does not start recording now. If the camera is unavailable, the entry stays so you can retry manually.",
                        "需要开启“悬浮后视镜”并授予相机和悬浮窗权限。保存不会立即录像；相机未就绪时保留入口，供你手动重试。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Utils.t("Closing the entry cancels automatic return for that session. Reopen OpenAVM after a head-unit restart.",
                        "主动关闭入口后，当次不会自动回来；车机重启后需重新打开 OpenAVM。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (installationPrompt) save(MirrorReturnMode.LOGO) else onDismiss() }, Modifier.testTag("return-cancel")) {
                        Text(if (installationPrompt) Utils.t("Use Logo for now", "先用 Logo 入口") else Utils.t("Cancel", "取消"))
                    }
                    Button(onClick = { save(mode) }, Modifier.testTag("return-save")) { Text(Utils.t("Save settings", "保存设置")) }
                }
            }
        }
    }
}

@Composable
private fun ReturnOption(selected: Boolean, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
