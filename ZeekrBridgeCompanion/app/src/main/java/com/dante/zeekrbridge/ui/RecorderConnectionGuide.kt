package com.dante.zeekrbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared by first-run guidance and connection details. Contains no commands or service effects. */
@Composable fun RecorderConnectionGuide() {
    val steps = listOf(
        t("1 · Use the same network", "1 · 手机和车机连到同一网络") to
            t("Connect the car to your phone hotspot, or put both devices on the same trusted Wi-Fi.",
                "车机加入手机热点，或两边连接同一个可信 Wi-Fi。"),
        t("2 · Start receiving on this phone", "2 · 在手机启动接收") to
            t("Update both apps to the secure version. In connection details, choose Open secure pairing to show the full fingerprint and six-digit code.",
                "先将两端更新为安全版。在手机连接详情点「开启安全配对」，查看完整指纹和六位码。"),
        t("3 · Enter the code, then verify identity", "3 · 填写配对码，再核对手机身份") to
            t("On Recorder → Phone and vehicle tools, find this phone or enter its address. Enter the six-digit code and choose Verify and pair. Compare every fingerprint group with this screen before Confirm and pair. If different, cancel. Later reconnects use the saved pairing.",
                "在车机「手机与车机工具」自动查找或输入本机地址，填入六位码，点「核对并配对」。逐组核对两端完整指纹一致，再点「确认并配对」；不一致就取消。以后使用已保存的配对重连。"),
        t("4 · Send an existing recording", "4 · 发送一段已有录像") to
            t("Choose a recording on the car and send it to the phone. When transfer finishes, open Media here to play it. Keep both devices on the network until it finishes.",
                "在车机录像列表选择一段录像并发送到手机，完成后在手机「影像」里播放。传输期间保持同网和接收服务运行。"),
        t("5 · Bring the route along", "5 · 一起回看行车轨迹") to
            t("For new recordings, enable Location & speed and Vehicle data with recordings on the car. In the player's send panel, select Include route & vehicle data. On either device, open the route during playback; choose Street map to load roads online, or keep the local trace offline. Tap a recorded route point to jump to that part of the video. Old videos without recorded data still play normally.",
                "录制新视频前，在车机开启「位置与速度」和「随录像记录车辆数据」。通过播放器发送时，勾选「附带轨迹与车辆数据」。两端回放时均可打开轨迹，选择「道路地图」联网加载道路，或离线查看本地轨迹。点一下已记录的轨迹点即可跳到对应视频。没有数据的旧录像仍可正常播放。"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEach { (title, description) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
