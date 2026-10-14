package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.util.Utils

@Composable
fun RecordingChannelSettings() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val recorder by CameraRecordingService.state.collectAsState()
    var front by remember { mutableStateOf(settings.recordFrontCabin) }
    var rear by remember { mutableStateOf(settings.recordRearCabin) }
    val busy = CameraRecordingService.isRunning() || RecorderCommandPolicy.isActive(recorder.status)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Utils.t("Recording channels", "录像通道"), style = MaterialTheme.typography.titleMedium)
            Text(Utils.t("Surround stays on. Add optional cabin cameras to normal recording; the same choice applies when recording resumes after returning to the car.",
                "保留环视录像，可为普通录像增加车内摄像头；回车后恢复录像也沿用此选择。"), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Utils.t("Cabin camera", "舱内摄像头"), Modifier.weight(1f))
                Switch(front, { front = it; settings.setRecordFrontCabin(it) }, enabled = !busy,
                    modifier = Modifier.testTag("record-front-cabin"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Utils.t("Infrared camera", "红外摄像头"), Modifier.weight(1f))
                Switch(rear, { rear = it; settings.setRecordRearCabin(it) }, enabled = !busy,
                    modifier = Modifier.testTag("record-rear-cabin"))
            }
            Text(Utils.t("Cabin streams use a camera-declared size up to 720p, requesting 15 fps and 2–4 Mb/s each. All channels use the main recording's destination and quota. If a running camera or its storage fails, the recording group stops together; completed files remain available. Existing recordings stay in their original location. Time-lapse uses only its selected camera.",
                "车内每路使用相机支持的分辨率，最高 720p，请求 15 帧及 2–4 Mb/s。所有通道使用主录像的存储位置和容量上限。录像中的相机或存储发生故障时，整组停止，已完成的文件仍可查看。已有录像保留在原位置。延时录像仅录制所选相机。"), style = MaterialTheme.typography.bodySmall)
            if (busy) Text(Utils.t("Stop recording before changing channels.", "停止录像后可修改通道。"), style = MaterialTheme.typography.bodySmall)
            RecordingChannelStatus()
        }
    }
}

@Composable
fun RecordingChannelStatus(modifier: Modifier = Modifier) {
    val channels by RecordingChannels.state.collectAsState()
    if (channels.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        channels.sortedBy { RecordingChannelId.all.indexOf(it.channelId) }.forEach { channel ->
            val name = when (channel.channelId) {
                RecordingChannelId.FRONT_CABIN -> Utils.t("Cabin camera", "舱内摄像头")
                RecordingChannelId.REAR_CABIN -> Utils.t("Infrared camera", "红外摄像头")
                else -> Utils.t("Surround", "环视")
            }
            val phase = when (channel.status) {
                "WRITING" -> Utils.t("Writing video", "正在写入视频")
                "RECEIVING" -> Utils.t("Receiving frames · waiting for writes", "已收到画面 · 等待写入")
                "STOPPING" -> Utils.t("Finishing files", "正在完成文件")
                "STOPPED" -> Utils.t("Stopped", "已停止")
                "UNAVAILABLE" -> Utils.t("Unavailable", "不可用")
                "INTERRUPTED" -> Utils.t("Interrupted", "已中断")
                else -> Utils.t("Starting", "正在启动")
            }
            val destination = when (channel.storageKind) {
                RecordingStorageKind.USB_MEDIASTORE.name -> "USB"
                RecordingStorageKind.INTERNAL.name -> Utils.t("Head unit", "车机内部")
                else -> Utils.t("Storage unknown", "存储位置未知")
            }
            Text(name + " · " + phase + " · " + destination + " · " + Utils.t("{0} frames / {1} files", "{0} 帧 / {1} 文件", channel.writtenFrames, channel.completedFiles),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("channel-health-" + channel.channelId))
            if (channel.cleanupUnconfirmed) Text(Utils.t("Waiting for camera release; no automatic restart.", "等待相机释放，不会自动重启。"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            channel.errorCode?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (channels.any { it.status in setOf("UNAVAILABLE", "INTERRUPTED") && !it.cleanupUnconfirmed })
            Text(Utils.t("Check camera settings and storage before starting again. If it repeats, use Settings → Recording diagnostics → Export diagnostics file.",
                "重新开始前请检查相机设置和存储；若再次发生，请在“设置 → 录像诊断”中导出诊断文件。"), style = MaterialTheme.typography.bodySmall)
    }
}
