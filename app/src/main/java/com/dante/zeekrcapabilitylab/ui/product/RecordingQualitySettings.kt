package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.product.*
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.util.Utils

@Composable
fun RecordingQualitySettings() {
    val context = LocalContext.current
    val store = remember { RecordingQualityStore(context) }
    Text(Utils.t("Quality by camera", "各摄像头画质"), style = MaterialTheme.typography.titleSmall)
    RecordingSourceRole.entries.forEach { role ->
        var selected by remember(role) { mutableStateOf(store.get(role)) }
        var expanded by remember(role) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when(role) {
                RecordingSourceRole.SURROUND -> Utils.t("Surround", "环视")
                RecordingSourceRole.CABIN -> Utils.t("Cabin", "车内")
                RecordingSourceRole.IR -> Utils.t("Infrared", "红外")
            }, Modifier.padding(top = 16.dp))
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text("${selected.label()} · ${selected.fps} fps ▾") }
                DropdownMenu(expanded, { expanded = false }) {
                    RecordingQuality.entries.forEach { value ->
                        DropdownMenuItem(text = { Text("${value.label()} · ${value.fps} fps") }, onClick = {
                            selected = value; store.set(role, value); expanded = false
                        })
                    }
                }
            }
        }
    }
    Text(Utils.t("Applies to the next normal recording. Original keeps existing settings; Balanced requests about 71% of the bitrate, Save space 50%. Resolution and time lapse stay unchanged. Actual frame rate depends on the camera.",
        "下次普通录像生效。原画沿用现有参数；均衡请求约 71% 码率，节省空间请求 50% 码率。分辨率及延时录像不变，实际帧率取决于摄像头。"), style = MaterialTheme.typography.bodySmall)
}
