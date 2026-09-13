package com.dante.zeekrcapabilitylab.ui.product

import android.content.Intent
import com.dante.zeekrcapabilitylab.sound.VehicleSoundEditorActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.transfer.PhoneSoundRelay
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.transfer.PhoneAddress
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.util.Utils
import io.github.dantenothing.avmtransfer.protocol.SoundOfferStates
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import kotlinx.coroutines.launch

@Composable
fun PhoneScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val developerModeEnabled = remember { SettingsStore.get(context).developerModeEnabled }
    val connection by TransferRepository.connection.collectAsState()
    val tasks by TransferRepository.tasks.collectAsState()
    val soundTasks by PhoneSoundRelay.tasks.collectAsState()
    var mountedUsb by remember { mutableStateOf(UsbExportVolumeResolver.mountedTargets(com.dante.zeekrcapabilitylab.ZeekrApp.appContext)) }
    val endpoint = connection.endpoint
    var host by remember(endpoint) {
        mutableStateOf(endpoint?.let { PhoneAddress(it.host, it.port).displayValue }.orEmpty())
    }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showPairing by remember { mutableStateOf(endpoint == null) }

    LaunchedEffect(Unit) { if (endpoint != null) TransferRepository.reconnectInBackground() }
    LaunchedEffect(endpoint) {
        if (endpoint != null) showPairing = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(Utils.t("Phone and vehicle tools", "手机与车机工具"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { context.startActivity(Intent(context, VehicleSoundEditorActivity::class.java)) }) {
            Text(Utils.t("Make a lock / unlock sound on this vehicle", "在车机制作上锁 / 解锁音效"))
        }
        Text(
            when {
                connection.connected -> Utils.t("Connected: {0}", "已连接：{0}", endpoint?.phoneName)
                endpoint != null -> Utils.t("Paired: {0} · currently offline", "已配对：{0} · 当前未连接", endpoint.phoneName)
                else -> Utils.t("No phone paired", "尚未配对手机")
            },
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (endpoint != null && !showPairing) {
                    Text(Utils.t("Paired phone", "已配对手机"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${endpoint.phoneName} · ${PhoneAddress(endpoint.host, endpoint.port).displayValue}")
                    Text(Utils.t("The six-digit code is only used for first-time pairing. Later, start the hotspot and phone receiver to reconnect automatically.", "六位码只在首次配对时使用。以后打开热点和手机接收服务即可自动重连。"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                message = if (TransferRepository.checkConnection()) Utils.t("Connected", "连接正常") else Utils.t("Phone unavailable. Make sure the receiver service is running on the phone.", "手机未连接，请确认手机接收服务正在运行")
                                busy = false
                            }
                        }, enabled = !busy) { Text(Utils.t("Reconnect now", "立即重连")) }
                        OutlinedButton(onClick = { showPairing = true }, enabled = !busy) { Text(Utils.t("Pair again", "重新配对")) }
                    }
                } else {
                    Text(
                        if (endpoint == null) Utils.t("Pair a phone", "首次配对手机") else Utils.t("Pair phone again", "重新配对手机"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(Utils.t("Connect the phone and head unit to the same hotspot or trusted local network. Start receiving in OpenAVM Companion and generate a six-digit pairing code.", "手机和车机需连接同一热点或可信局域网。先在手机 OpenAVM Companion 中启动接收并生成六位配对码。"))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it }, label = { Text(Utils.t("Phone address (IP or IP:port)", "手机地址（IP 或 IP:端口）")) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(Utils.t("Six-digit pairing code", "六位配对码")) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                val result = TransferRepository.pair(host, code)
                                message = result.fold({ Utils.t("Paired successfully. You will not need to enter the code again.", "配对成功，以后无需再次输入六位码") }, { Utils.t("Pairing failed. Check the address and pairing code.", "配对失败，请检查地址和配对码") })
                                if (result.isSuccess) {
                                    code = ""
                                    PhoneSoundRelay.onForeground()
                                }
                                busy = false
                            }
                        }, enabled = !busy && host.isNotBlank() && code.length == 6) { Text(Utils.t("Pair", "配对")) }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val result = TransferRepository.discover()
                                result.onSuccess { host = it.displayValue }
                                message = result.fold(
                                    { Utils.t("Phone found: {0}", "已找到手机：{0}", it.displayValue) },
                                    { Utils.t("Phone not found automatically. Enter the address shown on the phone.", "未自动找到，请输入手机地址") },
                                )
                                busy = false
                            }
                        }, enabled = !busy) { Text(Utils.t("Find automatically", "自动查找")) }
                        if (endpoint != null) {
                            OutlinedButton(onClick = { showPairing = false }, enabled = !busy) { Text(Utils.t("Cancel", "取消")) }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Utils.t("Phone sound relay", "手机音效中继"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    mountedUsb = UsbExportVolumeResolver.mountedTargets(com.dante.zeekrcapabilitylab.ZeekrApp.appContext)
                    PhoneSoundRelay.onStorageChanged()
                }) { Text(Utils.t("Refresh USB", "刷新 USB")) }
                OutlinedButton(
                    onClick = { PhoneSoundRelay.clearFinished() },
                    enabled = soundTasks.any { it.state in SoundOfferStates.terminal },
                ) {
                    Text(Utils.t("Clear finished", "清理已结束"))
                }
                if (developerModeEnabled) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(PhoneSoundRelay.reportJson())) }) {
                        Text(Utils.t("Copy diagnostic report", "复制诊断报告"))
                    }
                }
            }
        }
        Text(
            Utils.t(
                "Phone offers are downloaded with pairing authentication, validated locally, then installed only into /Lock Status Tones/ and /解闭锁音效/. No recording directory is writable here.",
                "手机任务会经过配对认证下载和本机校验，只写入 /Lock Status Tones/ 与 /解闭锁音效/；此功能不会写入任何录像目录。",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (soundTasks.isEmpty()) {
            Text(Utils.t("No sound tasks. Use “Send to vehicle USB” in the Companion sound maker.", "暂无音效任务。请在 Companion 音效制作器中使用“发送到车机 USB”。"))
        }
        soundTasks.sortedByDescending { it.createdAt }.forEach { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(task.offer.fileName, fontWeight = FontWeight.SemiBold)
                    Text(soundStateLabel(task.state, task.message), style = MaterialTheme.typography.bodySmall)
                    Text(
                        Utils.t(
                            "Target: {0} · {1}", "目标：{0} · {1}", task.targetDescription ?: Utils.t("Not selected", "未选择"), task.offer.purpose),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (task.state == SoundOfferStates.WAITING_FOR_USB_SELECTION) {
                        Text(Utils.t("Choose USB", "选择 USB"), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            mountedUsb.forEach { usb ->
                                OutlinedButton(onClick = { PhoneSoundRelay.selectUsb(task.offer.offerId, usb.storageUuid) }) {
                                    Text("${usb.description} · ${usb.storageUuid}")
                                }
                            }
                        }
                    }
                    task.directories.forEach { directory ->
                        Text("/${directory.directoryName}/ · ${if (directory.finalVerified) "SHA ✓" else directory.error ?: "pending"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.state == SoundOfferStates.FAILED_RECOVERABLE || task.state == SoundOfferStates.WAITING_FOR_USB) {
                            OutlinedButton(onClick = { PhoneSoundRelay.retry(task.offer.offerId) }) { Text(Utils.t("Retry", "重试")) }
                        }
                        if (task.state !in SoundOfferStates.terminal) {
                            OutlinedButton(onClick = { PhoneSoundRelay.cancel(task.offer.offerId) }) { Text(Utils.t("Cancel", "取消")) }
                        }
                    }
                }
            }
        }

        VehicleSoundToolbox(
            refreshToken = soundTasks.maxOfOrNull { it.updatedAt } ?: 0L,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Utils.t("Transfer queue", "传输队列"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = TransferRepository::clearFinished, enabled = tasks.any { it.state in TransferRepository.TERMINAL }) { Text(Utils.t("Clear finished", "清理已结束")) }
        }
        if (tasks.isEmpty()) Text(Utils.t("No transfer tasks. Choose “Send to phone” from the recording library.", "暂无传输任务。请在录像回看中选择“发送到手机”。"))
        tasks.sortedByDescending { it.createdAt }.forEach { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(task.fileName, fontWeight = FontWeight.SemiBold)
                    Text(stateLabel(task.state, task.reason), style = MaterialTheme.typography.bodySmall)
                    if (task.totalChunks > 0 && task.state !in TransferRepository.TERMINAL) {
                        LinearProgressIndicator(progress = { task.uploadedChunks.toFloat() / task.totalChunks }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.state !in TransferRepository.TERMINAL && task.state != TransferTaskState.CANCEL_PENDING) {
                            OutlinedButton(onClick = { TransferRepository.cancel(task.id) }) { Text(Utils.t("Cancel transfer", "取消传输")) }
                        }
                        if (task.state == TransferTaskState.FAILED) {
                            OutlinedButton(onClick = { TransferRepository.retry(task.id) }) { Text(Utils.t("Retry", "重试")) }
                        }
                    }
                }
            }
        }
        Text(Utils.t("Security: Transfers use pairing-authenticated, unencrypted HTTP. Only use a trusted phone hotspot or private local network. The service continues while either app is in the background, and interrupted transfers can resume.", "安全说明：传输采用带配对认证的明文 HTTP，只应在可信手机热点或私人局域网使用。手机或车机进入后台时服务会继续运行；网络中断后可续传。"), style = MaterialTheme.typography.bodySmall)
    }
}

private fun stateLabel(state: TransferTaskState, reason: String?): String = when (state) {
    TransferTaskState.QUEUED -> Utils.t("Waiting to send", "等待发送")
    TransferTaskState.PREPARING -> Utils.t("Calculating integrity checksum", "正在计算完整性校验")
    TransferTaskState.UPLOADING -> Utils.t("Transferring", "正在传输")
    TransferTaskState.COMMITTING -> Utils.t("Phone is verifying and saving", "手机正在校验并保存")
    TransferTaskState.WAITING_RETRY -> Utils.t("Waiting to resume: {0}", "等待恢复：{0}", reason.orEmpty())
    TransferTaskState.CANCEL_PENDING -> Utils.t("Cancelling and removing the temporary phone file", "正在取消并清理手机临时文件")
    TransferTaskState.COMPLETED -> Utils.t("Transfer complete", "传输完成")
    TransferTaskState.CANCELLED -> Utils.t("Cancelled", "已取消")
    TransferTaskState.FAILED -> Utils.t("Failed: {0}", "失败：{0}", reason.orEmpty())
}

private fun soundStateLabel(state: String, message: String?): String = when (state) {
    SoundOfferStates.QUEUED -> Utils.t("Queued", "等待处理")
    SoundOfferStates.DOWNLOADING -> Utils.t("Downloading and checking WAV", "正在下载并校验 WAV")
    SoundOfferStates.WAITING_FOR_USB -> Utils.t("Waiting for the bound USB", "等待目标 USB")
    SoundOfferStates.WAITING_FOR_USB_SELECTION -> Utils.t("Multiple USB drives found; choose one", "发现多个 USB，请选择一个")
    SoundOfferStates.INSTALLING -> Utils.t("Installing to both sound folders", "正在写入中英文双目录")
    SoundOfferStates.COMPLETED -> Utils.t(
        "Installed and verified in both folders. Open Vehicle settings and select the new custom lock/unlock sound. If it is missing, leave and reopen that page or restart the display.",
        "中英文双目录写入并校验完成。请打开车辆设置并选择新的自定义解闭锁音效；如果暂未显示，请退出后重新进入该页面或重启车机屏幕。",
    )
    SoundOfferStates.CANCELLED -> Utils.t("Cancelled", "已取消")
    else -> Utils.t("{0}: {1}", "{0}：{1}", state, message.orEmpty())
}
