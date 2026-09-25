package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcapabilitylab.sharing.*
import com.dante.zeekrcapabilitylab.util.Utils
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun BrowserShareDialog(selection: ShareSelection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val state by BrowserShareManager.state.collectAsState()
    var started by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var includeJson by remember { mutableStateOf(false) }
    val sizes by produceState<Map<String, Long>?>(null, selection) {
        value = withContext(Dispatchers.IO) { selection.files.associate { it.absolutePath to it.length() } }
    }
    LaunchedEffect(sizes) {
        sizes?.let { if (BrowserShareManager.limits.accepts(it.values.toList())) chosen = it.keys.toSet() }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.phase) {
        while (state.phase == SharePhase.ACTIVE) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) BrowserShareManager.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); BrowserShareManager.stop() }
    }
    val files = selection.files.filter { it.absolutePath in chosen }
    val selectedSizes = files.map { sizes?.get(it.absolutePath) ?: 0L }
    val allowed = sizes != null && selectedSizes.all { it > 0 } && BrowserShareManager.limits.accepts(selectedSizes)
    val active = started && state.phase == SharePhase.ACTIVE
    val working = state.phase in setOf(SharePhase.PREPARING, SharePhase.CLOSING)
    val bitmap by produceState<Bitmap?>(null, state.url) {
        value = state.url?.let { url -> withContext(Dispatchers.Default) { qr(url) } }
    }
    fun dismiss() { BrowserShareManager.stop(); onDismiss() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(Utils.t("Browser download", "浏览器下载")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(min = 360.dp, max = 620.dp)) {
                Text(Utils.t("Connect the car to your phone hotspot or the same Wi-Fi. Keep this dialog open. No phone app is required.",
                    "车机连接手机热点或同一 Wi-Fi，并保持此窗口打开。手机无需安装 App。"))
                if (active) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        bitmap?.let { Image(it.asImageBitmap(), Utils.t("Scan to download", "扫码下载"), Modifier.size(176.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Utils.t("Scan to download", "扫码下载"), style = MaterialTheme.typography.titleMedium)
                            Text(Utils.t("Expires in {0} min", "{0} 分钟后到期", ((state.expiresAtMs - now).coerceAtLeast(0) + 59_999) / 60_000))
                            Text(Utils.t("Anyone with this link on the same network can download the selected files.", "同一网络中持有此链接的人可以下载所选文件。"), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { state.url?.let { clipboard.setText(AnnotatedString(it)) } }) {
                                Text(Utils.t("Copy link", "复制链接"))
                            }
                        }
                    }
                    Text(state.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                } else if (working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.phase == SharePhase.CLOSING) Utils.t("Finishing active downloads…", "正在结束下载…")
                        else Utils.t("Checking selected files…", "正在检查所选文件…"))
                } else {
                    if (started && state.phase == SharePhase.FAILED) {
                        Text(when (state.problem) {
                            ShareProblem.STOP_RECORDING -> Utils.t("Stop recording and wait for the camera to finish closing first.", "请先停止录像，等待相机关闭完成。")
                            ShareProblem.WIFI_REQUIRED -> Utils.t("Connect the car to a phone hotspot or Wi-Fi first.", "请先将车机连接手机热点或 Wi-Fi。")
                            else -> Utils.t("Some files are unavailable or changed. Reconnect the USB and refresh the library.", "部分文件不可用或已变化，请重新连接 USB 并刷新记录。")
                        }, color = MaterialTheme.colorScheme.error)
                    } else if (started && state.phase == SharePhase.ENDED) {
                        Text(Utils.t("Sharing ended. Create a new link to continue.", "分享已结束，可重新生成链接。"))
                    }
                    Text(Utils.t("Select up to 64 files, 8 GiB total. Links expire after 15 minutes.", "最多选择 64 个分段，总计 8 GiB；链接 15 分钟后失效。"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(enabled = sizes != null, onClick = {
                            chosen = if (chosen.size == selection.files.size) emptySet() else selection.files.map { it.absolutePath }.toSet()
                        }) { Text(Utils.t("Select all", "全选")) }
                        Text(Utils.t("{0} selected · {1} MiB", "已选 {0} 个 · {1} MiB", files.size, selectedSizes.sum() / (1024 * 1024)), Modifier.align(Alignment.CenterVertically))
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                        items(selection.files, key = { it.absolutePath }) { file ->
                            val selected = file.absolutePath in chosen
                            fun toggle() { chosen = if (selected) chosen - file.absolutePath else chosen + file.absolutePath }
                            Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(selected, { toggle() })
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodySmall)
                                    Text("${(sizes?.get(file.absolutePath) ?: 0L) / (1024 * 1024)} MiB", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Row(Modifier.clickable { includeJson = !includeJson }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeJson, { includeJson = it })
                        Text(Utils.t("Include available JSON metadata", "附带可用的 JSON 信息"))
                    }
                }
            }
        },
        confirmButton = {
            if (!active) TextButton(enabled = allowed && !working && !BrowserShareManager.busy, onClick = {
                started = true
                BrowserShareManager.start(context, selection, files, includeJson, SharePageLabels(
                    Utils.t("OpenAVM downloads", "OpenAVM 视频下载"),
                    Utils.t("Download individual MP4 segments. Optional JSON is listed separately. Keep the car's sharing dialog open.", "逐个下载 MP4 分段；可选 JSON 单独列出。请保持车机分享窗口打开。"),
                    Utils.t("Download MP4", "下载 MP4"), Utils.t("Download JSON", "下载 JSON")))
            }) { Text(Utils.t("Create download link", "生成下载链接")) }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(Utils.t("Close", "关闭")) } },
    )
}

private fun qr(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 384, 384)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
