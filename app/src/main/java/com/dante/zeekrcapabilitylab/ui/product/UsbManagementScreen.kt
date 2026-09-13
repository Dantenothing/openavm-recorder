package com.dante.zeekrcapabilitylab.ui.product

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.dante.zeekrcapabilitylab.diagnostic.UsbInventorySource
import com.dante.zeekrcapabilitylab.diagnostic.UsbSentryEvent
import com.dante.zeekrcapabilitylab.diagnostic.UsbStorageInventory
import com.dante.zeekrcapabilitylab.diagnostic.UsbStorageInventoryReport
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import com.dante.zeekrcapabilitylab.diagnostic.UsbVideoInventoryItem
import com.dante.zeekrcapabilitylab.util.Utils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun UsbManagementScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val developerModeEnabled = remember { SettingsStore.get(context).developerModeEnabled }
    var report by remember { mutableStateOf(UsbStorageInventory.loadLatest(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<UsbVideoInventoryItem>?>(null) }
    var pendingDelete by remember { mutableStateOf<List<UsbVideoInventoryItem>>(emptyList()) }
    var pendingBefore by remember { mutableStateOf<UsbStorageInventoryReport?>(null) }

    fun refresh(includeRaw: Boolean) {
        if (busy) return
        busy = true
        status = Utils.t("Scanning USB metadata…", "正在扫描 USB 元数据…")
        scope.launch {
            runCatching { UsbStorageInventory.collect(context, includeRaw) }
                .onSuccess {
                    report = it
                    selectedKeys = emptySet()
                    status = Utils.t("USB inventory refreshed.", "USB 盘点已刷新。")
                }
                .onFailure {
                    status = Utils.t(
                        "USB inventory failed: {0}", "USB 盘点失败：{0}", it.message ?: it.javaClass.simpleName)
                }
            busy = false
        }
    }

    val videoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) {
            Utils.t("Video access granted.", "视频读取权限已授予。")
        } else {
            Utils.t("Video access was not granted; capacity remains available.", "未授予视频读取权限，仍可查看容量。")
        }
        refresh(includeRaw = false)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        report?.let { before ->
            report = UsbStorageInventory.recordAllFilesSettingsLaunch(
                context = context,
                before = before,
                dispatchSucceeded = true,
                resultReturned = true,
            )
        }
        refresh(includeRaw = true)
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val requested = pendingDelete
        val before = pendingBefore
        pendingDelete = emptyList()
        pendingBefore = null
        selectedKeys = emptySet()
        if (before != null && requested.isNotEmpty()) {
            busy = true
            scope.launch {
                report = UsbStorageInventory.recordDeletionResult(
                    context = context,
                    before = before,
                    requested = requested,
                    userApproved = result.resultCode == Activity.RESULT_OK,
                )
                status = if (result.resultCode == Activity.RESULT_OK) {
                    Utils.t("System deletion finished; inventory refreshed.", "系统删除已完成，盘点已刷新。")
                } else {
                    Utils.t("System deletion was cancelled.", "系统删除已取消。")
                }
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh(includeRaw = false)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(onClick = onBack) { Text(Utils.t("Back", "返回")) }
                Column(Modifier.padding(start = 8.dp, top = 8.dp)) {
                    Text(
                        Utils.t("USB storage management", "USB 存储管理"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        Utils.t(
                            "Capacity, read-only factory Sentry inventory and OpenAVM storage controls",
                            "容量、只读原厂哨兵盘点与 OpenAVM 存储管理",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Utils.t("Safety boundary", "安全边界"), fontWeight = FontWeight.SemiBold)
                    Text(
                        Utils.t(
                            "Factory SentryMode is always read-only. Automatic cleanup and direct deletion are limited to freshly verified OpenAVM recordings under Download/OpenAVM; other visible videos require Android confirmation.",
                            "原厂 SentryMode 始终只读。自动清理与直接删除仅限 Download/OpenAVM 下重新验证归属的 OpenAVM 录像；其他可见视频需要 Android 系统确认。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        Utils.t(
                            "Use deletion only while parked and after Sentry recording has stopped.",
                            "仅在停车且哨兵录像停止后使用删除功能。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (status.isNotBlank()) {
            item { Text(status, style = MaterialTheme.typography.bodySmall) }
        }
        report?.let { current ->
            item { CapacityCard(current) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Utils.t("Access and refresh", "权限与刷新"), fontWeight = FontWeight.SemiBold)
                        Text(
                            Utils.t("Video permission", "视频读取权限") + ": " +
                                yesNo(current.videoReadPermissionGranted) + " · " +
                                Utils.t("Full USB access", "完整 USB 访问") + ": " +
                                yesNo(current.allFilesAccessGranted),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            Utils.t("App settings handler", "应用权限设置入口") + ": " +
                                yesNo(current.allFilesSettings.appSpecificHandlerAvailable) + " · " +
                                Utils.t("global handler", "全局入口") + ": " +
                                yesNo(current.allFilesSettings.globalHandlerAvailable),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (developerModeEnabled) current.directAccessProbe?.let { probe ->
                            Text(
                                Utils.t("Direct root list", "直接列出根目录") + ": " +
                                    yesNo(probe.rootListSucceeded) + " · " +
                                    "SentryMode: " + yesNo(probe.sentryListSucceeded) + " · " +
                                    "info: " + yesNo(probe.infoReadSucceeded) + " · " +
                                    "MP4: " + yesNo(probe.videoOpenSucceeded),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (probe.failures.isNotEmpty()) {
                                Text(
                                    probe.failures.joinToString(" · "),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { refresh(false) }) {
                                Text(Utils.t("Refresh", "刷新"))
                            }
                            if (!current.videoReadPermissionGranted) {
                                Button(enabled = !busy, onClick = {
                                    videoPermissionLauncher.launch(UsbStorageInventory.requiredVideoPermission())
                                }) {
                                    Text(Utils.t("Allow videos", "授权读取视频"))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!current.allFilesAccessGranted &&
                                (
                                    current.allFilesSettings.appSpecificHandlerAvailable ||
                                        current.allFilesSettings.globalHandlerAvailable
                                    )
                            ) {
                                OutlinedButton(enabled = !busy, onClick = {
                                    val intent = UsbStorageInventory.allFilesSettingsIntent(context)
                                    if (intent == null) {
                                        report = UsbStorageInventory.recordAllFilesSettingsLaunch(
                                            context = context,
                                            before = current,
                                            dispatchSucceeded = false,
                                            resultReturned = false,
                                            error = "NO_SETTINGS_HANDLER",
                                        )
                                        status = Utils.t(
                                            "This head unit has no all-files settings handler.",
                                            "车机没有可用的完整存储访问设置页面。",
                                        )
                                    } else {
                                        runCatching { allFilesLauncher.launch(intent) }
                                            .onSuccess {
                                                report = UsbStorageInventory.recordAllFilesSettingsLaunch(
                                                    context = context,
                                                    before = current,
                                                    dispatchSucceeded = true,
                                                    resultReturned = false,
                                                )
                                            }
                                            .onFailure { failure ->
                                                report = UsbStorageInventory.recordAllFilesSettingsLaunch(
                                                    context = context,
                                                    before = current,
                                                    dispatchSucceeded = false,
                                                    resultReturned = false,
                                                    error = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                                                )
                                                status = Utils.t(
                                                    "Could not open full USB access: {0}", "无法打开完整 USB 访问设置：{0}", failure.javaClass.simpleName)
                                            }
                                    }
                                }) {
                                    Text(Utils.t("Full inventory", "完整盘点"))
                                }
                            }
                            OutlinedButton(enabled = !busy, onClick = { refresh(true) }) {
                                Text(
                                    if (current.allFilesAccessGranted) {
                                        Utils.t("Deep scan", "深度扫描")
                                    } else {
                                        Utils.t("Try direct scan", "尝试直接扫描")
                                    },
                                )
                            }
                        }
                        if (developerModeEnabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "OpenAVM USB inventory",
                                            UsbStorageInventory.reportJson(current),
                                        ),
                                    )
                                    status = Utils.t("USB inventory report copied.", "USB 盘点报告已复制。")
                                }) {
                                    Text(Utils.t("Copy inventory report", "复制盘点报告"))
                                }
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "OpenAVM USB recording report",
                                            UsbFastTrackReportStore.reportJson(context),
                                        ),
                                    )
                                    status = Utils.t("USB recording report copied.", "USB 录像报告已复制。")
                                }) {
                                    Text(Utils.t("Recording report", "录像报告"))
                                }
                                TextButton(onClick = onOpenDiagnostics) {
                                    Text(Utils.t("Storage diagnostics", "存储诊断"))
                                }
                            }
                        }
                        current.mediaStoreQueryError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        current.rawScanError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (current.topLevelDirectories.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(Utils.t("Top-level USB usage", "USB 顶层目录占用"), fontWeight = FontWeight.SemiBold)
                            current.topLevelDirectories.take(12).forEach { directory ->
                                Text(
                                    "${directory.name} · ${formatBytes(directory.bytes)} · ${directory.fileCount} " +
                                        Utils.t("files", "个文件"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (current.rawScanTruncated) {
                                Text(
                                    Utils.t("Scan reached its 20,000-entry safety limit.", "扫描达到 20,000 项安全上限。"),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (current.largestFiles.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(Utils.t("Largest files", "最大文件"), fontWeight = FontWeight.SemiBold)
                            current.largestFiles.take(15).forEach { file ->
                                Text(
                                    "${formatBytes(file.bytes)} · ${file.relativePath}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (current.sentryEvents.isNotEmpty() || current.sentryDirectoryFound) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                Utils.t("Zeekr SentryMode events", "极氪原厂哨兵事件"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${current.sentryEventCount} " + Utils.t("events", "个事件") + " · " +
                                    formatBytes(current.sentryBytes) + " · " +
                                    "${current.sentryCompleteEventCount} " + Utils.t("complete", "个完整"),
                            )
                            Text(
                                Utils.t(
                                    "Exact timestamp folders are grouped as MP4 + JPG + info_inner.txt. Location values stay redacted.",
                                    "按时间文件夹精确组合 MP4、JPG 与 info_inner.txt；位置坐标保持隐藏。",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            current.sentryScanError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(current.sentryEvents, key = { it.stableKey }) { event ->
                    SentryEventRow(event, showTechnicalMetadata = developerModeEnabled)
                }
            }
            val candidates = current.videos.filterNot {
                it.openAvm || it.relativePath.startsWith("SentryMode/", ignoreCase = true)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            Utils.t("USB video candidates", "USB 视频候选"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            Utils.t(
                                "{0} other non-OpenAVM videos. MediaStore items can be selected for confirmed deletion; raw-only items stay read-only.", "{0} 个其他非 OpenAVM 视频。MediaStore 项可选择后确认删除；仅原始扫描可见的文件保持只读。", candidates.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (selectedKeys.isNotEmpty()) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    confirmDelete = candidates.filter { it.stableKey in selectedKeys }
                                },
                            ) {
                                Text(
                                    Utils.t(
                                        "Delete selected ({0})", "删除所选（{0}）", selectedKeys.size),
                                )
                            }
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                item {
                    Text(
                        Utils.t(
                            "No non-OpenAVM videos are visible through the current access mode.",
                            "当前访问方式没有发现非 OpenAVM 视频。",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(candidates, key = { it.stableKey }) { video ->
                VideoCandidateRow(
                    video = video,
                    selected = video.stableKey in selectedKeys,
                    onSelected = { checked ->
                        selectedKeys = if (checked) selectedKeys + video.stableKey
                        else selectedKeys - video.stableKey
                    },
                )
            }
            current.latestDeletion?.let { deletion ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(Utils.t("Latest deletion", "最近删除结果"), fontWeight = FontWeight.SemiBold)
                            Text(
                                Utils.t("Requested", "请求") + " ${deletion.requestedCount} · " +
                                    Utils.t("removed", "已移除") + " ${deletion.removedCount} · " +
                                    formatBytes(deletion.requestedBytes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${formatBytes(deletion.freeBytesBefore)} → ${formatBytes(deletion.freeBytesAfter)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            deletion.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    confirmDelete?.let { requested ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(Utils.t("Delete selected other USB videos?", "删除所选其他 U 盘视频？")) },
            text = {
                Text(
                    Utils.t(
                        "{0} videos · {1}. This is permanent and may change the native Zeekr list.", "{0} 个视频 · {1}。删除不可恢复，并可能影响 Zeekr 原生列表。", requested.size, formatBytes(requested.sumOf { it.sizeBytes })),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    val before = report ?: return@TextButton
                    val launchFailure = runCatching {
                        val request = UsbStorageInventory.createDeleteRequest(context, requested)
                        pendingDelete = requested
                        pendingBefore = before
                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }.exceptionOrNull()
                    if (launchFailure != null) {
                        pendingDelete = emptyList()
                        pendingBefore = null
                        busy = true
                        scope.launch {
                            report = UsbStorageInventory.recordDeletionResult(
                                context = context,
                                before = before,
                                requested = requested,
                                userApproved = false,
                                launchError = "${launchFailure.javaClass.simpleName}: ${launchFailure.message.orEmpty()}",
                            )
                            status = Utils.t(
                                "Delete request could not be opened.",
                                "无法打开系统删除确认。",
                            )
                            busy = false
                        }
                    }
                }) {
                    Text(Utils.t("Continue to Android confirmation", "继续到 Android 确认"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }
}

@Composable
private fun CapacityCard(report: UsbStorageInventoryReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                report.targetDescription ?: Utils.t("No removable USB", "未检测到可移动 USB"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Utils.t("Total", "总容量") + " ${formatBytes(report.totalBytes)} · " +
                    Utils.t("Used", "已用") + " ${formatBytes(report.usedBytes)} · " +
                    Utils.t("Free", "剩余") + " ${formatBytes(report.freeBytes)}",
            )
            Text(
                "OpenAVM " + Utils.t("quota usage", "配额占用") + " " +
                    "${formatBytes(report.openAvmNamespaceBytes)} / " +
                    formatBytes(report.openAvmQuotaBytes),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Utils.t("Trusted", "已验证归属") + " ${formatBytes(report.openAvmOwnedBytes)} · " +
                    Utils.t("deletion eligible", "可安全清理") + " " +
                    formatBytes(report.openAvmDeletionEligibleBytes),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "MediaStore " + Utils.t("videos", "视频") + ": " +
                    "${report.mediaStoreVideoCount} · ${formatBytes(report.mediaStoreVideoBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (report.rawScanAttempted) {
                Text(
                    Utils.t("Raw files scanned", "原始文件扫描") + ": " +
                        "${report.rawScannedEntries} · ${formatBytes(report.rawScannedFileBytes)} · " +
                        Utils.t("unaccounted", "未解释") + " ${formatBytes(report.unaccountedBytesEstimate)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SentryEventRow(event: UsbSentryEvent, showTechnicalMetadata: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.eventId, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatBytes(event.totalBytes)} · " +
                    if (event.complete) Utils.t("complete event", "完整事件")
                    else Utils.t("incomplete event", "不完整事件"),
                color = if (event.complete) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (showTechnicalMetadata) event.info?.let { info ->
                Text(
                    listOfNotNull(
                        info.rawR?.let { "r=$it" },
                        info.rawD?.let { "d=${it.joinToString(prefix = "[", postfix = "]")}" },
                        info.rawAz?.let { "az=$it" },
                        if (info.locationPresent) Utils.t("location present (redacted)", "包含位置（已隐藏）")
                        else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.missingFiles.isNotEmpty()) {
                Text(
                    Utils.t("Missing", "缺少") + ": " + event.missingFiles.joinToString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                Utils.t("Raw SentryMode event · read-only", "原厂 SentryMode 事件 · 当前只读"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoCandidateRow(
    video: UsbVideoInventoryItem,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
) {
    val deletable = video.source == UsbInventorySource.MEDIASTORE && video.itemUri != null
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = selected,
                enabled = deletable,
                onCheckedChange = onSelected,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(video.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        formatBytes(video.sizeBytes),
                        video.durationMs?.takeIf { it > 0L }?.let(Utils::formatDuration),
                        video.modifiedEpochMs.takeIf { it > 0L }?.let(::formatDate),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    video.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (deletable) {
                        Utils.t("MediaStore · deletion requires system confirmation", "MediaStore · 删除需要系统确认")
                    } else {
                        Utils.t("Raw-only · read-only", "仅原始扫描可见 · 只读")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deletable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"

private fun formatBytes(value: Long?): String {
    if (value == null) return "?"
    return when {
        value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GiB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "$value B"
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, com.dante.zeekrcapabilitylab.product.AppLanguage.locale).format(Date(epochMs))
