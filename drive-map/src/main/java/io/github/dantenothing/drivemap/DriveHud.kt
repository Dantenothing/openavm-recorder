package io.github.dantenothing.drivemap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Display only. Callers own the visibility choice and the live/recorded clock. */
@Composable
fun DriveHud(
    frame: DriveHudFrame,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chinese: Boolean = false,
    live: Boolean = false,
    compact: Boolean = false,
) {
    fun text(en: String, zh: String) = if (chinese) zh else en
    if (!visible) {
        Surface(modifier, color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(10.dp)) {
            TextButton(onClick = { onVisibilityChange(true) }) { Text("HUD", color = Color.White) }
        }
        return
    }
    if (compact) {
        Surface(modifier, color = Color.Black.copy(alpha = 0.80f), contentColor = Color.White, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((frame.speedKmh?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—") + " km/h",
                        style = MaterialTheme.typography.titleMedium)
                    Text(when (frame.speedSource) {
                        DriveHudSpeedSource.VEHICLE -> text("Vehicle", "车辆")
                        DriveHudSpeedSource.GPS -> "GPS"
                        DriveHudSpeedSource.UNAVAILABLE -> text("Unavailable", "未知")
                    }, style = MaterialTheme.typography.labelSmall)
                }
                Text(frame.gear ?: "—", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { contentDescription = text("Gear", "挡位") + " " + (frame.gear ?: text("unavailable", "未知")) })
                Text(when (frame.turn) { "left" -> "←"; "right" -> "→"; "hazard" -> "↔"; "off" -> "·"; else -> "—" },
                    color = if (frame.turn in setOf("left", "right", "hazard")) Color(0xFF7DE6AA) else Color.White,
                    style = MaterialTheme.typography.titleLarge)
                Text(text("Brake ", "制动 ") + when (frame.brakePressed) { true -> text("ON", "踩下"); false -> text("off", "松开"); null -> "—" },
                    style = MaterialTheme.typography.labelSmall, color = if (frame.brakePressed == true) Color(0xFFFFBD9A) else Color.White)
                TextButton(onClick = { onVisibilityChange(false) }, contentPadding = PaddingValues(2.dp), modifier = Modifier.width(32.dp)
                    .semantics { contentDescription = text("Hide HUD", "隐藏 HUD") }) { Text("×", color = Color.White) }
            }
        }
        return
    }
    Surface(modifier, color = Color.Black.copy(alpha = 0.80f), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(frame.speedKmh?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                        Text("km/h", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text(when (frame.speedSource) {
                        DriveHudSpeedSource.VEHICLE -> text("Vehicle speed", "车辆速度")
                        DriveHudSpeedSource.GPS -> text("GPS speed", "GPS 速度")
                        DriveHudSpeedSource.UNAVAILABLE -> text("Speed unavailable", "速度未知")
                    }, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(frame.gear ?: "—", style = MaterialTheme.typography.headlineSmall)
                    Text(text("Gear", "挡位"), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (frame.turn) { "left" -> "←"; "right" -> "→"; "hazard" -> "↔"; "off" -> "·"; else -> "—" },
                        color = if (frame.turn in setOf("left", "right", "hazard")) Color(0xFF7DE6AA) else Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { contentDescription = when (frame.turn) {
                            "left" -> text("Left indicator", "左转向灯"); "right" -> text("Right indicator", "右转向灯")
                            "hazard" -> text("Hazard lights", "双闪"); "off" -> text("Indicators off", "转向灯关闭")
                            else -> text("Indicators unavailable", "转向灯未知")
                        } })
                    Text(text("Turn", "转向"), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onVisibilityChange(false) }, contentPadding = PaddingValues(3.dp), modifier = Modifier.width(40.dp)
                    .semantics { contentDescription = text("Hide HUD", "隐藏 HUD") }) { Text("×", color = Color.White) }
            }
            Text(text("Brake pedal: ", "制动踏板：") + when (frame.brakePressed) {
                true -> text("pressed", "已踩下"); false -> text("released", "已松开"); null -> "—"
            }, color = if (frame.brakePressed == true) Color(0xFFFFBD9A) else Color.White, style = MaterialTheme.typography.labelMedium)
            Text(text("Accelerator raw ", "油门原始值 ") + raw(frame.acceleratorDepth.raw) + "  ·  " +
                text("Brake raw ", "制动原始值 ") + raw(frame.brakeDepth.raw), style = MaterialTheme.typography.labelSmall)
            Text(text("Pedal scale unverified", "踏板量程待验证") + " · " + if (live) text("Live observations", "当前观察值") else text("Recorded observations", "录像观察值"),
                style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBCFD5))
        }
    }
}

private fun raw(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"
