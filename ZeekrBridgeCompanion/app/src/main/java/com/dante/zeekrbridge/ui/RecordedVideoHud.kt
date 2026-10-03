package com.dante.zeekrbridge.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.saveable.rememberSaveable
import com.dante.zeekrbridge.player.RecordedDriveFrame
import io.github.dantenothing.drivemap.DriveHud
import io.github.dantenothing.drivemap.DriveHudModel
import io.github.dantenothing.drivemap.DriveHudObservation

/** One persistent display preference. All observations come from the verified recorded bundle. */
@Composable
internal fun RecordedVideoHud(frame: RecordedDriveFrame, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("recorded_drive_ui", 0) }
    var visible by remember { mutableStateOf(preferences.getBoolean("hud_visible", true)) }
    val hud = remember(frame) { phoneHudFrame(frame) }
    DriveHud(hud, visible, {
        visible = it
        preferences.edit().putBoolean("hud_visible", it).apply()
    }, modifier, chinese = PhoneLanguage.locale.language == "zh", compact = compact)
}

private fun phoneHudFrame(frame: RecordedDriveFrame) = DriveHudModel.frame(frame.elapsedMs,
    frame.vehicle?.values?.mapValues { (_, value) -> DriveHudObservation(value.readElapsedMs, value.number, value.text, value.status) }.orEmpty(),
    frame.gps?.speedMps?.toDouble(), frame.gps?.elapsedMs)

@Composable
internal fun RecordedPedalDetails(frame: RecordedDriveFrame) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hud = remember(frame) { phoneHudFrame(frame) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(t("Pedal details", "踏板详情")) }
        if (expanded) {
            Text(t("Unverified raw values · not percentages", "待验证原始值 · 不代表百分比"))
            fun raw(value: Double?) = value?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "—"
            Text(t("Brake depth {0} · accelerator {1}", "制动深度 {0} · 油门 {1}", raw(hud.brakeDepth.raw), raw(hud.acceleratorDepth.raw)))
        }
    }
}
