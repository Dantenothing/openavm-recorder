package com.dante.zeekrbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlaybackControls(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    speed: Float,
    muted: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onBackTen: () -> Unit,
    onForwardTen: () -> Unit,
    onTogglePlay: () -> Unit,
    onSpeed: (Float) -> Unit,
    onMute: () -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    val speedDescription = t("Playback speed", "播放速度")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Slider(
            value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlayerTime(positionMs), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(formatPlayerTime(durationMs), style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { speedMenu = true }, contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = speedDescription }) {
                    Text(speedLabel(speed), fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { candidate ->
                        DropdownMenuItem(text = { Text(speedLabel(candidate), fontWeight = if (candidate == speed) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSpeed(candidate); speedMenu = false })
                    }
                }
            }
            SkipButton(MediaIcons.BackTen, t("Back 10 seconds", "后退 10 秒"), onBackTen)
            FilledIconButton(onClick = onTogglePlay, shape = CircleShape, modifier = Modifier.size(60.dp)) {
                Icon(if (playing) MediaIcons.Pause else MediaIcons.Play,
                    if (playing) t("Pause", "暂停") else t("Play", "播放"), Modifier.size(28.dp))
            }
            SkipButton(MediaIcons.ForwardTen, t("Forward 10 seconds", "前进 10 秒"), onForwardTen)
            IconButton(onClick = onMute, modifier = Modifier.size(48.dp)) {
                Icon(if (muted) MediaIcons.Muted else MediaIcons.Volume,
                    if (muted) t("Unmute", "取消静音") else t("Mute", "静音"), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(28.dp))
            Text("10", modifier = Modifier.padding(top = 3.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun speedLabel(value: Float): String = if (value % 1f == 0f) "${value.toInt()}×" else "${value}×"
