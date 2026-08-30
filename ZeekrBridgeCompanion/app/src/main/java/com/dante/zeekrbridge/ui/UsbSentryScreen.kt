package com.dante.zeekrbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.MediaExportQueue
import com.dante.zeekrbridge.core.MediaExportState
import com.dante.zeekrbridge.sentry.UsbSentryRepository
import com.dante.zeekrbridge.sentry.UsbSentryScanResult
import com.dante.zeekrbridge.sentry.UsbSentryVideo
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun UsbSentryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportJobs by MediaExportQueue.jobs.collectAsState()
    var treeUri by remember { mutableStateOf(loadSavedTree(context)) }
    var result by remember { mutableStateOf<UsbSentryScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var playback by remember { mutableStateOf<UsbSentryVideo?>(null) }
    var exportSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var preparing by remember { mutableStateOf<UsbSentryVideo?>(null) }
    var preparedBytes by remember { mutableLongStateOf(0L) }
    var preparationJob by remember { mutableStateOf<Job?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var preparedCopyBytes by remember { mutableLongStateOf(0L) }
    val hasProtectedPreparedCopies = exportJobs.any { job ->
        job.state != MediaExportState.COMPLETED &&
            job.plan.clips.any { it.filePath.contains("usb-sentry-imports", ignoreCase = true) }
    }

    fun scan(uri: Uri) {
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanning = true
        status = t("Scanning the selected USB…", "正在扫描所选 USB…")
        scanJob = scope.launch {
            try {
                val scanResult = UsbSentryRepository.scan(context, uri)
                if (generation == scanGeneration) {
                    result = scanResult
                    status = t(
                        "Found ${scanResult.videos.size} video files after checking ${scanResult.scannedDocuments} items.",
                        "已检查 ${scanResult.scannedDocuments} 项，找到 ${scanResult.videos.size} 个视频文件。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == scanGeneration) {
                    result = null
                    status = t(
                        "USB scan failed: ${error.message ?: error.javaClass.simpleName}",
                        "USB 扫描失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            } finally {
                if (generation == scanGeneration) {
                    scanning = false
                    scanJob = null
                }
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            treeUri = uri
            saveTree(context, uri)
            scan(uri)
        }
    }

    LaunchedEffect(Unit) {
        preparedCopyBytes = UsbSentryRepository.preparedCopyBytes(context)
        treeUri?.let(::scan)
    }

    fun prepareForExport(video: UsbSentryVideo) {
        preparationJob?.cancel()
        preparing = video
        preparedBytes = 0L
        status = t(
            "Preparing ${video.displayName} for a reliable export…",
            "正在准备 ${video.displayName}，完成后即可可靠导出…",
        )
        preparationJob = scope.launch {
            try {
                val prepared = UsbSentryRepository.materialize(context, video) { copied, _ -> preparedBytes = copied }
                exportSegment = prepared.segment
                preparedCopyBytes = UsbSentryRepository.preparedCopyBytes(context)
                preparing = null
                preparationJob = null
                status = t(
                    "Prepared. Choose the time range and one or more directions.",
                    "准备完成，请选择时间范围和一个或多个方向。",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                status = t(
                    "Cannot prepare this video: ${error.message ?: error.javaClass.simpleName}",
                    "无法准备此视频：${error.message ?: error.javaClass.simpleName}",
                )
                preparing = null
                preparationJob = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(onClick = onBack) { Text(t("Back", "返回")) }
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(
                        t("Zeekr Sentry USB", "Zeekr 哨兵 USB"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        t(
                            "Read factory four-lane 360° recordings directly from a USB drive.",
                            "直接读取 USB 中的原厂四路 360° 哨兵录像。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t("USB access", "USB 访问"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        t(
                            "OpenAVM only reads videos you authorize. It never deletes, formats, or cleans Android, LOST.DIR, recordings, or unknown folders.",
                            "OpenAVM 只读取你授权的视频；绝不会删除、格式化或清理 Android、LOST.DIR、录像或未知目录。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { treePicker.launch(null) }) {
                            Text(if (treeUri == null) t("Choose USB", "选择 USB") else t("Change USB", "更换 USB"))
                        }
                        OutlinedButton(
                            enabled = treeUri != null && !scanning,
                            onClick = { treeUri?.let(::scan) },
                        ) { Text(t("Refresh", "重新扫描")) }
                    }
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                    if (result?.truncated == true) {
                        Text(
                            t(
                                "The safety scan limit was reached; choose the Sentry folder directly for a complete list.",
                                "已达到安全扫描上限；请直接选择哨兵录像目录以获得完整列表。",
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (preparedCopyBytes > 0L) {
                        Text(
                            t(
                                "Prepared USB copies: ${formatUsbBytes(preparedCopyBytes)}",
                                "已准备的 USB 副本：${formatUsbBytes(preparedCopyBytes)}",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = preparing == null && !hasProtectedPreparedCopies,
                            onClick = {
                                scope.launch {
                                    val removed = UsbSentryRepository.clearPreparedCopies(context)
                                    preparedCopyBytes = UsbSentryRepository.preparedCopyBytes(context)
                                    status = t(
                                        "Cleared ${formatUsbBytes(removed)} of prepared USB copies.",
                                        "已清理 ${formatUsbBytes(removed)} 的 USB 准备副本。",
                                    )
                                }
                            },
                        ) { Text(t("Clear prepared copies", "清理准备副本")) }
                        if (hasProtectedPreparedCopies) {
                            Text(
                                t(
                                    "Finish or dismiss active/retriable USB exports before cleanup.",
                                    "请先完成或隐藏仍可重试的 USB 导出任务，再进行清理。",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        if (preparing != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("Preparing export", "正在准备导出"), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = {
                                val total = preparing?.sizeBytes ?: 0L
                                if (total > 0L) (preparedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatUsbBytes(preparedBytes)} / ${formatUsbBytes(preparing?.sizeBytes ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = {
                            preparationJob?.cancel()
                            preparationJob = null
                            preparing = null
                            status = t("Preparation cancelled", "已取消准备")
                        }) { Text(t("Cancel", "取消")) }
                    }
                }
            }
        }
        item { MediaExportQueueCard() }
        val videos = result?.videos.orEmpty()
        if (!scanning && treeUri != null && videos.isEmpty()) {
            item {
                Text(
                    t("No MP4/MOV videos were found in the selected tree.", "所选目录中没有找到 MP4/MOV 视频。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(videos, key = { it.documentId }) { video ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(video.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            formatUsbBytes(video.sizeBytes),
                            video.lastModifiedMs.takeIf { it > 0L }?.let(::formatUsbDate),
                            video.relativePath.takeIf { it != video.displayName },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val resolved = runCatching { UsbSentryRepository.probe(context, video) }.getOrDefault(video)
                                if (resolved.width != null && resolved.height != null && !resolved.isConfirmedFourLane) {
                                    status = t(
                                        "${video.displayName} is ${resolved.width}×${resolved.height}, not a supported four-lane 360° composite.",
                                        "${video.displayName} 为 ${resolved.width}×${resolved.height}，不是受支持的四路 360° 合成格式。",
                                    )
                                } else {
                                    playback = resolved
                                }
                            }
                        }) { Text(t("View", "查看")) }
                        OutlinedButton(
                            enabled = preparing == null,
                            onClick = { prepareForExport(video) },
                        ) { Text(t("Save / Export", "保存 / 导出")) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    playback?.let { video ->
        UsbSentryPlaybackDialog(
            uri = video.uri,
            displayName = video.displayName,
            durationMs = video.durationMs,
            originalWidth = video.width ?: 1280,
            originalHeight = video.height ?: 5140,
            onDismiss = { playback = null },
        )
    }
    exportSegment?.let { segment ->
        MediaExportDialog(
            segments = listOf(segment),
            onDismiss = { exportSegment = null },
        )
    }
}

private fun loadSavedTree(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    .getString(KEY_TREE, null)
    ?.let(Uri::parse)

private fun saveTree(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
}

private fun formatUsbBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatUsbDate(epochMs: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(epochMs))

private const val PREFS = "usb_sentry"
private const val KEY_TREE = "tree_uri"
