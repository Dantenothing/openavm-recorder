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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.transfer.PhoneSoundRelay
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.transfer.PhonePairingCandidate
import com.dante.zeekrcapabilitylab.transfer.phoneFailure
import com.dante.zeekrcapabilitylab.transfer.phoneSecurityMessage
import com.dante.zeekrcapabilitylab.transfer.PhoneAddress
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.util.Utils
import io.github.dantenothing.avmtransfer.protocol.SoundOfferStates
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
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
    var showPairing by remember { mutableStateOf(endpoint?.securelyPaired != true) }
    var pendingIdentity by remember { mutableStateOf<PhonePairingCandidate?>(null) }
    var identityConfirmed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (endpoint != null) TransferRepository.reconnectInBackground() }
    LaunchedEffect(endpoint) {
        if (endpoint != null) showPairing = !endpoint.securelyPaired
    }

    pendingIdentity?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!busy) { pendingIdentity = null; identityConfirmed = false } },
            title = { Text(Utils.t("Verify phone identity", "核对手机身份")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Utils.t("Compare all fingerprint groups with the pairing page on your phone. Continue only if they match.", "请与手机配对页逐组核对完整指纹，全部一致后再继续。"))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(candidate.displayFingerprint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row {
                        Checkbox(checked = identityConfirmed, onCheckedChange = { identityConfirmed = it }, enabled = !busy)
                        Text(Utils.t("I checked every group on both screens.", "我已核对两块屏幕上的每一组指纹。"), modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                Button(enabled = identityConfirmed && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = TransferRepository.pair(candidate, code)
                            message = result.fold(
                                { Utils.t("Secure pairing complete. Future connections are automatic.", "安全配对完成，以后会自动连接。") },
                                { phoneSecurityMessage(phoneFailure(it).error) },
                            )
                            if (result.isSuccess) { code = ""; PhoneSoundRelay.onForeground() }
                        } finally { busy = false; pendingIdentity = null; identityConfirmed = false }
                    }
                }) { Text(Utils.t("Confirm and pair", "确认并配对")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingIdentity = null; identityConfirmed = false }) { Text(Utils.t("Cancel", "取消")) } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(Utils.t("Phone and vehicle tools", "手机与车机工具"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(Utils.t("The phone app is optional. Recording, playback and sound tools work on the vehicle with USB.", "手机 App 为可选项，车机配合 USB 即可录像、回看和制作音效。"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (developerModeEnabled && com.dante.zeekrcapabilitylab.BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) OutlinedButton(onClick = { context.startActivity(Intent(context, com.dante.zeekrcapabilitylab.preflight.PreflightActivity::class.java)) }) {
            Text(Utils.t("OpenAVM Preflight · hardware and preview baseline", "重构体检 · 硬件能力与预览基线"))
        }
        OutlinedButton(onClick = { context.startActivity(Intent(context, VehicleSoundEditorActivity::class.java)) }) {
            Text(Utils.t("Make a lock / unlock sound on this vehicle", "在车机制作上锁 / 解锁音效"))
        }
        Text(
            when {
                endpoint != null && !endpoint.securelyPaired -> phoneSecurityMessage(com.dante.zeekrcapabilitylab.transfer.PhoneSecurityError.SECURE_PAIRING_REQUIRED)
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                message = if (TransferRepository.checkConnection()) Utils.t("Connected", "连接正常") else TransferRepository.connection.value.message
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val result = TransferRepository.inspectPairing(host)
                                    result.onSuccess { pendingIdentity = it; identityConfirmed = false }
                                        .onFailure { message = phoneSecurityMessage(phoneFailure(it).error) }
                                } finally { busy = false }
                            }
                        }, enabled = !busy && host.isNotBlank() && code.length == 6) { Text(Utils.t("Verify and pair", "核对并配对")) }
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
                else if (connection.securityError != null) Text(phoneSecurityMessage(connection.securityError!!), color = MaterialTheme.colorScheme.error)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Utils.t("Phone sound relay", "手机音效中继"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                "Sounds sent from the phone are checked and saved to the vehicle USB. Select the new sound in the vehicle settings after transfer.",
                "手机发来的音效会经过校验并保存到车机 USB，传输完成后请到原车设置选择新音效。",
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text(Utils.t("Phone transfers use encrypted connections tied to the phone identity you confirmed. Update both apps and complete secure pairing once; interrupted transfers can resume.", "手机传输通过加密连接进行，并绑定您确认的手机身份。请更新两端应用并完成一次安全配对；中断后可续传。"), style = MaterialTheme.typography.bodySmall)
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
        "Saved to USB. Select it in vehicle settings; the sound list may refresh after leaving and returning, or reconnecting USB once file operations finish.",
        "已保存到 USB，请到原车设置选择；列表可能需要离车再回车，或等文件操作结束后重新连接 USB 才刷新。",
    )
    SoundOfferStates.CANCELLED -> Utils.t("Cancelled", "已取消")
    else -> Utils.t("{0}: {1}", "{0}：{1}", state, message.orEmpty())
}
