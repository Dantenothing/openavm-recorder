package io.github.dantenothing.drivemap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Recorded observations only; ratios use a learned reference and never claim pedal calibration. */
@Composable
fun PlaybackHudStrip(frame: DriveHudFrame, scale: RelativePedalScale, showPeaks: Boolean,
    onShowPeaks: () -> Unit, modifier: Modifier = Modifier, chinese: Boolean = false) {
    fun t(en: String, zh: String) = if (chinese) zh else en
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFFF2F5FB), contentColor = Color(0xFF172235)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(frame.speedKmh?.let { "%.0f".format(Locale.ROOT, it) } ?: "—", fontSize = 34.sp)
                        Text(" km/h", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text(if (frame.speedSource == DriveHudSpeedSource.GPS) t("GPS speed", "GPS 速度") else t("Speed", "车速"), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(frame.gear ?: "—", fontSize = 28.sp)
                    Text(t("Gear", "挡位"), style = MaterialTheme.typography.labelSmall)
                }
                PedalMeter(t("Throttle", "油门"), scale.throttleFraction(frame.acceleratorDepth), frame.acceleratorDepth.raw,
                    scale.throttlePeak, showPeaks, Color(0xFF12A94B), Modifier.weight(1f), chinese)
                PedalMeter(t("Brake", "刹车"), scale.brakeFraction(frame.brakeDepth), frame.brakeDepth.raw,
                    scale.brakePeak, showPeaks, Color(0xFFE45050), Modifier.weight(1f), chinese)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("◀", fontSize = 28.sp, color = if (frame.turn in setOf("left", "hazard")) Color(0xFF10A84E) else Color(0xFFB9C2D1))
                    Text("▶", fontSize = 28.sp, color = if (frame.turn in setOf("right", "hazard")) Color(0xFF10A84E) else Color(0xFFB9C2D1))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Pedals relative to observed peak · not calibrated", "踏板相对历史峰值 · 尚未标定"),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64718A))
                TextButton(onClick = onShowPeaks, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) {
                    Text(if (showPeaks) t("Hide peaks", "隐藏峰值") else t("Show peaks", "显示峰值"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PedalMeter(title: String, fraction: Float?, raw: Double?, peak: Double, showPeaks: Boolean,
    tint: Color, modifier: Modifier, chinese: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(fraction?.let { "%.0f%%".format(Locale.ROOT, it * 100) + if (chinese) " 相对" else " rel." } ?: "—",
                style = MaterialTheme.typography.labelSmall)
        }
        LinearProgressIndicator(progress = { fraction ?: 0f }, modifier = Modifier.fillMaxWidth().height(8.dp),
            color = tint, trackColor = Color(0xFFDCE2EC))
        if (showPeaks) Text((raw?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.ROOT, it) } ?: "—") +
            (if (chinese) " / 峰值 " else " / peak ") + "%.2f".format(Locale.ROOT, peak), style = MaterialTheme.typography.labelSmall)
    }
}
