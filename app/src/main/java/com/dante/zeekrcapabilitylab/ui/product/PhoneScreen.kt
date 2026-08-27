package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.transfer.PhoneAddress
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import kotlinx.coroutines.launch

@Composable
fun PhoneScreen() {
    val scope = rememberCoroutineScope()
    val connection by TransferRepository.connection.collectAsState()
    val tasks by TransferRepository.tasks.collectAsState()
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
        Text("手机传输", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            when {
                connection.connected -> "已连接：${endpoint?.phoneName}"
                endpoint != null -> "已配对：${endpoint.phoneName} · 当前未连接"
                else -> "尚未配对手机"
            },
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (endpoint != null && !showPairing) {
                    Text("已配对手机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${endpoint.phoneName} · ${PhoneAddress(endpoint.host, endpoint.port).displayValue}")
                    Text("六位码只在首次配对时使用。以后打开热点和手机接收服务即可自动重连。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                message = if (TransferRepository.checkConnection()) "连接正常" else "手机未连接，请确认手机接收服务正在运行"
                                busy = false
                            }
                        }, enabled = !busy) { Text("立即重连") }
                        OutlinedButton(onClick = { showPairing = true }, enabled = !busy) { Text("重新配对") }
                    }
                } else {
                    Text(
                        if (endpoint == null) "首次配对手机" else "重新配对手机",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("手机和车机需连接同一热点或可信局域网。先在手机 OpenAVM Companion 中启动接收并生成六位配对码。")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it }, label = { Text("手机地址（IP 或 IP:端口）") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位配对码") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                val result = TransferRepository.pair(host, code)
                                message = result.fold({ "配对成功，以后无需再次输入六位码" }, { it.message ?: "配对失败" })
                                if (result.isSuccess) code = ""
                                busy = false
                            }
                        }, enabled = !busy && host.isNotBlank() && code.length == 6) { Text("配对") }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val result = TransferRepository.discover()
                                result.onSuccess { host = it.displayValue }
                                message = result.fold(
                                    { "已找到手机：${it.displayValue}" },
                                    { it.message ?: "未自动找到，请输入手机地址" },
                                )
                                busy = false
                            }
                        }, enabled = !busy) { Text("自动查找") }
                        if (endpoint != null) {
                            OutlinedButton(onClick = { showPairing = false }, enabled = !busy) { Text("取消") }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("传输队列", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = TransferRepository::clearFinished, enabled = tasks.any { it.state in TransferRepository.TERMINAL }) { Text("清理已结束") }
        }
        if (tasks.isEmpty()) Text("暂无传输任务。请在录像回看中选择“发送到手机”。")
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
                            OutlinedButton(onClick = { TransferRepository.cancel(task.id) }) { Text("取消传输") }
                        }
                        if (task.state == TransferTaskState.FAILED) {
                            OutlinedButton(onClick = { TransferRepository.retry(task.id) }) { Text("重试") }
                        }
                    }
                }
            }
        }
        Text("Alpha 说明：传输采用带配对认证的明文 HTTP，只应在可信手机热点或私人局域网使用。手机或车机进入后台时服务会继续运行；网络中断后可续传。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun stateLabel(state: TransferTaskState, reason: String?): String = when (state) {
    TransferTaskState.QUEUED -> "等待发送"
    TransferTaskState.PREPARING -> "正在计算完整性校验"
    TransferTaskState.UPLOADING -> "正在传输"
    TransferTaskState.COMMITTING -> "手机正在校验并保存"
    TransferTaskState.WAITING_RETRY -> "等待恢复：${reason.orEmpty()}"
    TransferTaskState.CANCEL_PENDING -> "正在取消并清理手机临时文件"
    TransferTaskState.COMPLETED -> "传输完成"
    TransferTaskState.CANCELLED -> "已取消"
    TransferTaskState.FAILED -> "失败：${reason.orEmpty()}"
}
