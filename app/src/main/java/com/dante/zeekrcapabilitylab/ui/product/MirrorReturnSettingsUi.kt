package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dante.zeekrcapabilitylab.product.RecordingSetupOptions
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannels
import com.dante.zeekrcapabilitylab.telemetry.RecordingTelemetry
import com.dante.zeekrcapabilitylab.util.Utils

internal fun MirrorReturnMode.summary(): String = when (this) {
    MirrorReturnMode.OFF -> Utils.t("Exit on screen-off", "熄屏后退出悬浮窗")
    MirrorReturnMode.LOGO -> Utils.t("Show Logo · tap for preview", "显示 Logo · 点击恢复预览")
    MirrorReturnMode.PREVIEW -> Utils.t("Restore full window · preview only", "恢复完整悬浮窗 · 仅预览")
    MirrorReturnMode.RECORD -> Utils.t("Restore full window · record automatically", "恢复完整悬浮窗 · 自动录像")
}

@Composable
internal fun MirrorReturnSettingsEntry(onSaved: () -> Unit = {}) {
    if (!BuildConfig.MIRROR_RETURN_ENABLED) return
    val context = LocalContext.current
    val store = remember(context) { MirrorReturnSettings(context) }
    var mode by remember { mutableStateOf(store.selected) }
    var showing by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("return-settings-entry")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Utils.t("Return, recording & playback", "回车、录像与回放"), style = MaterialTheme.typography.titleMedium)
                Text(mode.summary(), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { showing = true }) { Text(Utils.t("Change", "更改")) }
        }
    }
    if (showing) MirrorReturnSetupDialog(store, installationPrompt = false, onDismiss = { showing = false }, onSaved = {
        mode = store.selected; showing = false; onSaved()
    })
}

/** Saves preferences for future recording/return and playback. Never starts a camera or telemetry. */
@Composable
internal fun MirrorReturnSetupDialog(store: MirrorReturnSettings, installationPrompt: Boolean,
                                     onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore.get(context) }
    val initial = remember(settings) { settings.recordingSetupOptions }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var retained by rememberSaveable { mutableStateOf(store.selected.retained) }
    var full by rememberSaveable { mutableStateOf(store.selected.automatic) }
    // Each installation asks for a fresh explicit recording choice. Never pre-check a previous opt-in.
    var autoRecord by rememberSaveable { mutableStateOf(!installationPrompt && store.selected == MirrorReturnMode.RECORD) }
    var cabin by rememberSaveable { mutableStateOf(initial.frontCabin) }
    var infrared by rememberSaveable { mutableStateOf(initial.rearCabin) }
    var vehicleHud by rememberSaveable { mutableStateOf(initial.vehicleHud) }
    var gpsRoute by rememberSaveable { mutableStateOf(initial.gpsRoute) }
    var playbackMap by rememberSaveable { mutableStateOf(initial.playbackMap) }
    var saveRejected by remember { mutableStateOf(false) }
    var gpsPermission by remember { mutableStateOf(RecordingTelemetry.hasPermission(context)) }
    var gpsPermissionDenied by remember { mutableStateOf(false) }
    val recorder by CameraRecordingService.state.collectAsState()
    val channels by RecordingChannels.state.collectAsState()
    val floating by FloatingMirrorService.state.collectAsState()
    val busy = floating.busy || CameraRecordingService.isRunning() || RecorderCommandPolicy.isActive(recorder.status) ||
        channels.any { it.cleanupPending || it.cleanupUnconfirmed || it.status in setOf("STARTING", "RECEIVING", "WRITING", "STOPPING") }
    fun recordingBusyNow(): Boolean = FloatingMirrorService.state.value.busy || CameraRecordingService.isRunning() ||
        RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status) ||
        RecordingChannels.state.value.any { it.cleanupPending || it.cleanupUnconfirmed ||
            it.status in setOf("STARTING", "RECEIVING", "WRITING", "STOPPING") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        gpsPermission = RecordingTelemetry.hasPermission(context)
        gpsPermissionDenied = !gpsPermission
        if (gpsPermission && !recordingBusyNow()) gpsRoute = true
    }
    fun requestGps() {
        if (recordingBusyNow()) return
        if (RecordingTelemetry.hasPermission(context)) { gpsPermission = true; gpsRoute = true }
        else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    val mode = when { !retained -> MirrorReturnMode.OFF; !full -> MirrorReturnMode.LOGO
        autoRecord -> MirrorReturnMode.RECORD; else -> MirrorReturnMode.PREVIEW }
    fun save() {
        if (gpsRoute && !initial.gpsRoute && !RecordingTelemetry.hasPermission(context)) {
            gpsRoute = false; gpsPermission = false; gpsPermissionDenied = true
            return
        }
        val options = RecordingSetupOptions(cabin, infrared, vehicleHud, gpsRoute, playbackMap)
        if (!settings.saveRecordingSetupOptions(options, recordingBusyNow())) { saveRejected = true; return }
        store.save(mode)
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
                        Text(if (page == 0) Utils.t("Ready when you return", "回车后，按你的习惯恢复")
                            else Utils.t("Record and replay your drive", "记录与回看行程"),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (page == 0) Utils.t("1 of 2 · Return behaviour", "第 1 / 2 步 · 回车后的行为")
                            else Utils.t("2 of 2 · Optional recording and playback", "第 2 / 2 步 · 可选录像与回放设置"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                key(page) { Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page == 0) {
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
                                    Text(Utils.t("Surround · Normal recording · your storage settings. Choose optional cameras and data on the next page.",
                                        "环视 · 普通录像 · 沿用当前存储设置。下一步可选择额外相机与记录数据。"), style = MaterialTheme.typography.bodySmall)
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
                    } else {
                        Text(Utils.t("These choices apply to future recordings, including automatic return. Nothing starts when you save.",
                            "这些选择用于后续录像，也适用于回车自动录像。保存不会立即开始任何记录。"), style = MaterialTheme.typography.bodyMedium)
                        SetupToggle(cabin, { cabin = it }, !busy, "setup-cabin", Utils.t("Cabin camera", "舱内摄像头"),
                            Utils.t("Include the mapped cabin camera with normal surround recording.", "环视普通录像时，同时录制映射的舱内摄像头。"))
                        SetupToggle(infrared, { infrared = it }, !busy, "setup-infrared", Utils.t("Infrared camera", "红外摄像头"),
                            Utils.t("Include infrared with normal surround recording. Its view depends on your camera mapping.", "环视普通录像时同时录制红外；实际视角取决于相机映射。"))
                        Text(Utils.t("Extra camera files use internal storage. Unavailable cameras do not stop surround recording.",
                            "额外相机的视频使用内部存储；不可用时不影响环视录像。"), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        SetupToggle(vehicleHud, { vehicleHud = it }, !busy, "setup-vehicle-hud", Utils.t("Record vehicle data for HUD", "记录 HUD 车辆数据"),
                            Utils.t("Save read-only speed, gear, indicators and braking with video. Missing readings remain unknown; GPS is separate.",
                                "随录像保存只读车速、挡位、转向与制动数据。读不到的保持未知；GPS 单独选择。"))
                        SetupToggle(gpsRoute, { if (it) requestGps() else { gpsRoute = false; gpsPermissionDenied = false } }, !busy,
                            "setup-gps-route", Utils.t("Record route and GPS speed", "记录轨迹与 GPS 速度"),
                            Utils.t("Optional location history saved with recordings. Requires location permission.", "可选的位置记录，随录像保存，需要位置权限。"))
                        if ((gpsRoute && !gpsPermission) || gpsPermissionDenied) {
                            Text(Utils.t("Location access is needed to record a route. Video and vehicle HUD data still work without it.",
                                "记录轨迹需要位置权限；没有权限仍可录像及记录车辆 HUD 数据。"), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("setup-gps-permission-message"))
                            TextButton(onClick = ::requestGps, enabled = !busy, modifier = Modifier.testTag("setup-gps-grant")) {
                                Text(Utils.t("Grant location access", "授予位置权限"))
                            }
                        }
                        HorizontalDivider()
                        SetupToggle(playbackMap, { playbackMap = it }, true, "setup-playback-map", Utils.t("Open map in playback", "回放时默认打开地图"),
                            Utils.t("Expand the recorded route and events. Street map stays an explicit online choice; the provider receives the viewed area.",
                                "展开已记录轨迹与事件。道路地图仍需手动选择联网，服务商会获知查看区域。"))
                        Text(Utils.t("A route needs GPS recorded at the time. This choice does not collect location or add a route to old videos.",
                            "轨迹需要录像时已有 GPS 记录；此选项不会采集位置，也不能为旧视频补出轨迹。"), style = MaterialTheme.typography.bodySmall)
                        Text(Utils.t("Review after each install or update; change anytime in Settings.", "每次安装或升级后确认；随时可在设置中更改。"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (busy) Text(Utils.t("Stop recording and wait for saving before changing cameras or recorded data. Playback preferences can still be changed.",
                            "请停止录像并等待保存完成，再修改相机或记录数据；仍可更改回放偏好。"), modifier = Modifier.testTag("setup-recording-busy"),
                            style = MaterialTheme.typography.bodySmall)
                        if (saveRejected) {
                            Text(Utils.t("Recording started while you were editing. Stop it before saving these recording changes, or keep the current recording choices.",
                                "编辑期间已开始录像。请停止后保存录像设置，或保留当前录像选择。"), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("setup-save-rejected"))
                            TextButton(onClick = {
                                val current = settings.recordingSetupOptions
                                cabin = current.frontCabin; infrared = current.rearCabin; vehicleHud = current.vehicleHud; gpsRoute = current.gpsRoute
                                saveRejected = false
                            }) { Text(Utils.t("Keep current recording choices", "保留当前录像选择")) }
                        }
                    }
                } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (page == 0) TextButton(onClick = {
                        if (installationPrompt) { retained = true; full = false; autoRecord = false; page = 1 } else onDismiss()
                    }, Modifier.testTag("return-cancel")) {
                        Text(if (installationPrompt) Utils.t("Use Logo for now", "先用 Logo 入口") else Utils.t("Cancel", "取消"))
                    } else Row {
                        TextButton(onClick = { page = 0 }, Modifier.testTag("return-back")) { Text(Utils.t("Back", "上一步")) }
                        if (!installationPrompt) TextButton(onClick = onDismiss, Modifier.testTag("return-cancel")) { Text(Utils.t("Cancel", "取消")) }
                    }
                    if (page == 0) Button(onClick = { page = 1 }, Modifier.testTag("return-next")) { Text(Utils.t("Next", "下一步")) }
                    else Button(onClick = ::save, Modifier.testTag("return-save")) { Text(Utils.t("Save settings", "保存设置")) }
                }
            }
        }
    }
}

@Composable
private fun SetupToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, tag: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
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
