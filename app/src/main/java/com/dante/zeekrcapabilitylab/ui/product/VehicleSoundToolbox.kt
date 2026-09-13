package com.dante.zeekrcapabilitylab.ui.product

import android.media.MediaPlayer
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcapabilitylab.sound.VehicleSoundEditorActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.transfer.ZeekrSoundMirrorState
import com.dante.zeekrcapabilitylab.transfer.ZeekrSoundUsbEntry
import com.dante.zeekrcapabilitylab.transfer.ZeekrSoundUsbLibrary
import com.dante.zeekrcapabilitylab.transfer.ZeekrSoundUsbSnapshot
import com.dante.zeekrcapabilitylab.util.Utils
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun VehicleSoundToolbox(refreshToken: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val playerHolder = remember { arrayOfNulls<MediaPlayer>(1) }
    var snapshot by remember { mutableStateOf(ZeekrSoundUsbSnapshot()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ZeekrSoundUsbEntry?>(null) }

    fun stopPreview() {
        playerHolder[0]?.let { player ->
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.release() }
        }
        playerHolder[0] = null
        playingKey = null
    }

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            snapshot = ZeekrSoundUsbLibrary.scan(context)
            status = when {
                snapshot.errors.isNotEmpty() -> Utils.t(
                    "Sound scan completed with: {0}", "铃声扫描完成，但有异常：{0}", snapshot.errors.joinToString(" | ").take(240))
                snapshot.volumes.isEmpty() -> Utils.t("No removable USB detected.", "未检测到可移动 U 盘。")
                else -> Utils.t("USB sound list refreshed.", "U 盘铃声列表已刷新。")
            }
            busy = false
        }
    }

    fun togglePreview(sound: ZeekrSoundUsbEntry) {
        if (playingKey == sound.stableKey) {
            stopPreview()
            return
        }
        stopPreview()
        val path = sound.previewPath ?: return
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(path)
            player.setOnCompletionListener {
                runCatching { it.release() }
                if (playerHolder[0] === it) playerHolder[0] = null
                playingKey = null
            }
            player.setOnErrorListener { failed, what, extra ->
                runCatching { failed.release() }
                if (playerHolder[0] === failed) playerHolder[0] = null
                playingKey = null
                status = Utils.t(
                    "Preview stopped (USB/audio error {0}/{1}).", "试听已停止（U 盘/音频错误 {0}/{1}）。", what, extra)
                true
            }
            player.prepare()
            player.start()
        }.onSuccess {
            playerHolder[0] = player
            playingKey = sound.stableKey
        }.onFailure {
            runCatching { player.release() }
            status = Utils.t(
                "Preview failed: {0}", "试听失败：{0}", it.message ?: it.javaClass.simpleName)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopPreview()
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            playerHolder[0]?.let { player -> runCatching { player.release() } }
            playerHolder[0] = null
        }
    }
    LaunchedEffect(refreshToken) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.fillMaxWidth(0.72f)) {
                    Text(
                        Utils.t("Vehicle sound toolbox", "车机铃声工具箱"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        Utils.t(
                            "Lists only the two known USB custom-sound folders.",
                            "仅列出 U 盘上两个已知的自定义铃声目录。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(enabled = !busy, onClick = { refresh() }) {
                    Text(Utils.t("Refresh", "刷新"))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                stopPreview()
                context.startActivity(Intent(context, VehicleSoundEditorActivity::class.java))
            }) { Text(Utils.t("Make a sound from music / video", "用音乐 / 视频制作音效")) }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

            if (!busy && snapshot.volumes.isEmpty()) {
                Text(Utils.t("Insert the sound USB to view installed WAV files.", "插入铃声 U 盘后即可查看已安装 WAV。"))
            }
            snapshot.volumes.forEach { volume ->
                Text(
                    "${volume.description} · ${volume.storageUuid}",
                    fontWeight = FontWeight.SemiBold,
                )
                if (volume.sounds.isEmpty()) {
                    Text(Utils.t("No WAV files in the two sound folders.", "两个铃声目录中暂无 WAV。"))
                }
                volume.sounds.forEach { sound ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(sound.fileName, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    mirrorLabel(sound.mirrorState),
                                    formatSoundBytes(sound.totalBytes),
                                    sound.durationMs?.let(Utils::formatDuration),
                                ).joinToString(" · "),
                                color = if (sound.mirrorState == ZeekrSoundMirrorState.MIRRORED) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            sound.copies.forEach { copy ->
                                Text(
                                    "/${copy.directoryName}/ · " +
                                        if (copy.sha256 != null) "SHA-256 ✓" else Utils.t("read failed", "读取失败"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = sound.previewPath != null,
                                    onClick = { togglePreview(sound) },
                                ) {
                                    Text(
                                        if (playingKey == sound.stableKey) Utils.t("Stop", "停止")
                                        else Utils.t("Preview", "试听"),
                                    )
                                }
                                OutlinedButton(
                                    enabled = sound.deleteEligible && !busy,
                                    onClick = {
                                        stopPreview()
                                        pendingDelete = sound
                                    },
                                ) {
                                    Text(Utils.t("Delete", "删除"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                Utils.t(
                    "This shows USB files, not the sound currently selected by Zeekr. After installing a new WAV, the vehicle may refresh it only after you leave and re-enter.",
                    "这里显示的是 U 盘文件，并不代表极氪当前选中的铃声。新 WAV 写入后，车辆可能要在上下车后才刷新。",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    pendingDelete?.let { sound ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(Utils.t("Delete this USB sound?", "删除这个 U 盘铃声？")) },
            text = {
                Text(
                    Utils.t(
                        "OpenAVM will delete and verify {0} exact WAV copies named {1}. No recording folder is touched.", "OpenAVM 将删除并复核名为 {1} 的 {0} 个精确 WAV 副本；不会接触任何录像目录。", sound.copies.size, sound.fileName),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val result = ZeekrSoundUsbLibrary.delete(context, sound)
                        status = if (result.deleted) {
                            Utils.t(
                                "Deleted {0} sound copies. Vehicle settings may refresh after the next vehicle re-entry.", "已删除 {0} 个铃声副本；车辆设置可能在下次重新上车后刷新。", result.deletedCopies)
                        } else {
                            Utils.t(
                                "Sound was not deleted: {0} · {1}", "铃声未删除：{0} · {1}", result.errorCode, result.message.orEmpty())
                        }
                        snapshot = ZeekrSoundUsbLibrary.scan(context)
                        busy = false
                    }
                }) {
                    Text(Utils.t("Delete permanently", "永久删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }
}

private fun mirrorLabel(state: ZeekrSoundMirrorState): String = when (state) {
    ZeekrSoundMirrorState.MIRRORED -> Utils.t("Both folders match", "双目录一致")
    ZeekrSoundMirrorState.ENGLISH_ONLY -> Utils.t("English folder only", "仅英文目录")
    ZeekrSoundMirrorState.CHINESE_ONLY -> Utils.t("Chinese folder only", "仅中文目录")
    ZeekrSoundMirrorState.CONFLICT -> Utils.t("Folder copies differ", "双目录内容不一致")
}

private fun formatSoundBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
