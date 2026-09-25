package com.dante.zeekrcapabilitylab.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.diagnostic.AppSpecificStorageObservation
import com.dante.zeekrcapabilitylab.diagnostic.MediaStoreVolumeObservation
import com.dante.zeekrcapabilitylab.diagnostic.MediaStorePublicationProbeResult
import com.dante.zeekrcapabilitylab.diagnostic.SafTreeObservation
import com.dante.zeekrcapabilitylab.diagnostic.UsbProbeCapabilitySummarizer
import com.dante.zeekrcapabilitylab.diagnostic.UsbProbeReport
import com.dante.zeekrcapabilitylab.diagnostic.UsbStorageProbe
import com.dante.zeekrcapabilitylab.diagnostic.UsbWriteProbeResult
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.util.Utils
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun UsbProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val developerModeEnabled = remember { SettingsStore.get(context).developerModeEnabled }
    if (!developerModeEnabled || !com.dante.zeekrcapabilitylab.BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(UsbStorageProbe.loadLatest(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun collect(runWriteProbe: Boolean) {
        if (busy) return
        busy = true
        status = if (runWriteProbe) {
            Utils.t(
                "Running the Gate A.2 removable MediaStore publication probe…",
                "正在运行 Gate A.2 可移动 MediaStore 发布探针…",
            )
        } else {
            Utils.t("Refreshing read-only USB evidence…", "正在刷新只读 USB 证据…")
        }
        scope.launch {
            runCatching { UsbStorageProbe.collect(context, runWriteProbe) }
                .onSuccess {
                    report = it
                    status = if (runWriteProbe) {
                        Utils.t("Probe finished and the report was saved internally.", "探针已完成，报告已保存到应用内部。")
                    } else {
                        Utils.t("Read-only report refreshed.", "只读报告已刷新。")
                    }
                }
                .onFailure {
                    status = Utils.t(
                        "USB probe failed: {0}", "USB 探针失败：{0}", it.message ?: it.javaClass.simpleName)
                }
            busy = false
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        report = UsbStorageProbe.recordSafPickerResult(context, uri != null) ?: report
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            UsbStorageProbe.rememberTreeUri(context, uri)
            collect(runWriteProbe = false)
        } else {
            status = Utils.t(
                "The SAF picker launched and returned without a directory.",
                "SAF 选择器已成功启动，但未返回目录。",
            )
        }
    }

    LaunchedEffect(Unit) {
        if (report == null) collect(runWriteProbe = false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(Utils.t("Back", "返回")) }
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    Utils.t("USB storage probe", "USB 存储探针"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    Utils.t("Gate A.2 publication diagnostic only", "仅 Gate A.2 发布诊断"),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    Utils.t("Safety boundary", "安全边界"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t(
                        "This page never opens a camera, records video, exports media, writes raw mount paths, formats storage, or grants all-files access.",
                        "本页面不会打开摄像头、录像、导出媒体、写入原始挂载路径、格式化存储或授予全盘访问权限。",
                    ),
                )
                Text(
                    Utils.t(
                        "The explicit MediaStore probe publishes two tiny same-name generic files in Download/OpenAVM on removable storage, records the provider's naming behavior, then deletes those exact URIs.",
                        "显式 MediaStore 探针会在可移动存储的 Download/OpenAVM 中发布两个同名的微型通用文件，记录系统的重名策略，然后精确删除这两个 URI。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Utils.t("Controls", "操作"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { collect(false) }) {
                        Text(Utils.t("Refresh read-only", "刷新只读报告"))
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        report = UsbStorageProbe.recordSafPickerLaunchAttempt(context) ?: report
                        runCatching { treePicker.launch(UsbStorageProbe.savedTreeUri(context)) }
                            .onSuccess {
                                report = UsbStorageProbe.recordSafPickerLaunchDispatched(context) ?: report
                                status = Utils.t(
                                    "SAF launch dispatched; waiting for the picker result.",
                                    "SAF 启动请求已发出，正在等待选择器结果。",
                                )
                            }
                            .onFailure { failure ->
                                report = UsbStorageProbe.recordSafPickerLaunchFailure(context, failure) ?: report
                                status = Utils.t(
                                    "Could not open the SAF picker: {0}", "无法打开 SAF 选择器：{0}", failure.message ?: failure.javaClass.simpleName)
                            }
                    }) {
                        Text(Utils.t("Select SAF tree", "选择 SAF 目录"))
                    }
                }
                Button(
                    enabled = !busy,
                    onClick = { collect(true) },
                ) {
                    Text(Utils.t("Run Gate A.2 probe", "运行 Gate A.2 探针"))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        report?.let { current ->
            ProbeSummaryCard(current)
            EvidenceCard(current)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Utils.t("Saved report", "已保存报告"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        UsbStorageProbe.latestReportFile(context).absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("OpenAVM USB probe", UsbStorageProbe.reportJson(current)),
                        )
                        status = Utils.t("Report copied.", "报告已复制。")
                    }) {
                        Text(Utils.t("Copy JSON report", "复制 JSON 报告"))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProbeSummaryCard(report: UsbProbeReport) {
    val summaries = remember(report) { UsbProbeCapabilitySummarizer.summarize(report) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Utils.t("Backend summary", "后端摘要"), style = MaterialTheme.typography.titleMedium)
            summaries.forEach { summary ->
                Text(summary.backend, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text(
                    Utils.t("Visible", "可见") + ": ${yesNo(summary.visible)} · " +
                        Utils.t("Export candidate", "导出候选") + ": ${yesNo(summary.exportCandidate)} · " +
                        "FD: ${yesNo(summary.fileDescriptorCandidate)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(summary.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                Utils.t("All-files access granted", "全盘访问已授予") + ": ${yesNo(report.allFilesAccessGranted)}",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EvidenceCard(report: UsbProbeReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Utils.t("Observed evidence", "观测证据"), style = MaterialTheme.typography.titleMedium)
            Text(
                Utils.t("Generated", "生成时间") + ": ${Utils.formatEpoch(report.generatedAtEpochMs)} · " +
                    "${report.buildVersion} · ${report.buildGitSha}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Android SDK ${report.androidSdkInt}", style = MaterialTheme.typography.bodySmall)
            Text(Utils.t("App-specific directories", "应用专属目录"), fontWeight = FontWeight.SemiBold)
            if (report.appSpecific.isEmpty()) Text("—")
            report.appSpecific.forEach { AppSpecificRow(it) }
            Text(Utils.t("StorageManager volumes", "StorageManager 卷"), fontWeight = FontWeight.SemiBold)
            if (report.storageVolumes.isEmpty()) Text("—")
            report.storageVolumes.forEach { volume ->
                Text(
                    "${volume.description} · state=${volume.state} · removable=${volume.removable} · " +
                        "primary=${volume.primary} · uuid=${volume.uuid ?: "-"} · ${volume.directory ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("MediaStore", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            if (report.mediaStoreVolumes.isEmpty()) Text("—")
            report.mediaStoreVolumes.forEach { MediaStoreVolumeRow(it) }
            Text("/proc/mounts", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            if (report.mounts.isEmpty()) Text("—")
            report.mounts.forEach { mount ->
                Text(
                    "${mount.mountPoint} · ${mount.fileSystem} · ${mount.options.joinToString(",")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(Utils.t("SAF picker", "SAF 选择器"), fontWeight = FontWeight.SemiBold)
            report.safPicker?.let { picker ->
                Text(
                    "preflightHandler=${picker.handlerAvailable} · attempted=${picker.launchAttempted} · " +
                        "dispatched=${picker.launchDispatchSucceeded ?: "?"} · returned=${picker.resultReturned ?: "?"} · " +
                        "selected=${picker.treeSelected ?: "?"}\n" +
                        "${picker.resolvedPackage ?: "-"}/${picker.resolvedActivity ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                picker.launchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } ?: Text("—")
            Text("SAF", fontWeight = FontWeight.SemiBold)
            SafRow(report.safTree)
        }
    }
}

@Composable
private fun MediaStoreVolumeRow(item: MediaStoreVolumeObservation) {
    Text(
        "${item.volumeName} · query=${item.querySucceeded} · mounted=${item.mounted ?: "?"} · " +
            "removable=${item.removable ?: "?"} · primary=${item.primary ?: "?"}\n${item.collectionUri}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    item.queryError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    item.writeProbe?.let { Text(probeResultText(it), style = MaterialTheme.typography.bodySmall) }
    item.publicationProbe?.let { PublicationProbeRows(it) }
}

@Composable
private fun PublicationProbeRows(result: MediaStorePublicationProbeResult) {
    val first = result.firstItem
    Text(
        "A2 pass=${yesNo(result.exportCandidate)} · create=${first.createSucceeded} · " +
            "write=${first.writeSucceeded} · sync=${first.syncSucceeded} · seek=${first.seekSucceeded} · " +
            "read=${first.readBackSucceeded}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "publishCount=${first.publishUpdateCount ?: "?"} · uriQuery=${first.uriQuerySucceeded} · " +
            "collectionQuery=${first.collectionQuerySucceeded} · metadata=${first.metadataMatched} · " +
            "deleteCount=${first.deleteCount ?: "?"} · deleted=${first.deletionConfirmed}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "name=${first.observedDisplayName ?: "-"} · path=${first.observedRelativePath ?: "-"} · " +
            "size=${first.observedSizeBytes ?: -1} · mime=${first.observedMimeType ?: "-"} · " +
            "pending=${first.observedPending ?: "?"} · volume=${first.observedVolumeName ?: "-"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "duplicate=${result.duplicate.behavior} · first=${result.duplicate.firstObservedDisplayName ?: "-"} · " +
            "second=${result.duplicate.secondObservedDisplayName ?: "-"} · " +
            "secondDeleted=${result.duplicate.secondItemDeleted ?: "?"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    first.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    result.duplicate.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AppSpecificRow(item: AppSpecificStorageObservation) {
    Text(
        "#${item.index} · removable=${item.removable ?: "?"} · read=${item.readable} · write=${item.writable} · " +
            "free=${formatBytes(item.usableBytes)}\n${item.path ?: "null"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    item.writeProbe?.let { Text(probeResultText(it), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SafRow(item: SafTreeObservation?) {
    if (item == null) {
        Text(Utils.t("No SAF tree selected", "尚未选择 SAF 目录"), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "name=${item.displayName ?: "-"} · read=${item.persistedRead} · write=${item.persistedWrite} · " +
            "create=${item.supportsCreate ?: "?"} · rename=${item.supportsRename ?: "?"}\n${item.uri}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    item.writeProbe?.let { Text(probeResultText(it), style = MaterialTheme.typography.bodySmall) }
}

private fun probeResultText(result: UsbWriteProbeResult): String =
    "probe create=${result.createSucceeded} write=${result.writeSucceeded} sync=${result.syncSucceeded} " +
        "seek=${result.seekSucceeded} read=${result.readBackSucceeded} rename=${result.renameSucceeded} " +
        "cleanup=${result.cleanupSucceeded}${result.error?.let { " · $it" } ?: ""}"

private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"

private fun formatBytes(value: Long?): String {
    if (value == null) return "?"
    val gib = value / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f GiB", gib)
}
