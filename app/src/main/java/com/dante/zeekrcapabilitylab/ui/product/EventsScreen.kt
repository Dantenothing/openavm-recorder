package com.dante.zeekrcapabilitylab.ui.product

import com.dante.zeekrcapabilitylab.sharing.ShareSelection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcapabilitylab.player.RecordingThumbnailCache
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardEvent
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardEventStore
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardVideoPresentation
import com.dante.zeekrcapabilitylab.player.RecordingPresentationPolicy
import com.dante.zeekrcapabilitylab.player.FourLaneThumbs
import com.dante.zeekrcapabilitylab.product.EventGroups
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.RecordingDateFilter
import com.dante.zeekrcapabilitylab.product.MediaAvailability
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.VehicleMediaCategory
import com.dante.zeekrcapabilitylab.product.VehicleSentryRecording
import com.dante.zeekrcapabilitylab.product.VehicleUsbMediaLibrary
import com.dante.zeekrcapabilitylab.product.VehicleUsbMediaSnapshot
import com.dante.zeekrcapabilitylab.product.VehicleUsbRecording
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.OpenAvmUsbDeleteRequest
import com.dante.zeekrcapabilitylab.usbexport.OpenAvmUsbDeletionManager
import com.dante.zeekrcapabilitylab.usbexport.UsbExportRepository
import com.dante.zeekrcapabilitylab.usbexport.UsbExportState
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTask
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class UsbExportSelection(
    val logicalId: String,
    val recordingMode: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val files: List<File>,
)

private data class OpenAvmSentryRow(val event: GuardEvent, val files: List<File>)

private sealed interface VehicleTimelineItem {
    val key: String
    val startedAtEpochMs: Long

    data class Incident(val group: EventGroups.EventGroup) : VehicleTimelineItem {
        override val key: String = "incident:${EventGroups.stableIncidentKey(group)}"
        override val startedAtEpochMs: Long = group.startedAtEpochMs
    }

    data class InternalRecording(val recording: RecorderLibrary.Recording) : VehicleTimelineItem {
        override val key: String = "recording:${recording.id}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class UsbRecording(val recording: VehicleUsbRecording) : VehicleTimelineItem {
        override val key: String = "usb:${recording.stableKey}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class FactorySentry(val recording: VehicleSentryRecording) : VehicleTimelineItem {
        override val key: String = "factory:${recording.stableKey}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class OpenAvmSentry(val row: OpenAvmSentryRow) : VehicleTimelineItem {
        override val key: String = "openavm-sentry:${row.event.id}"
        override val startedAtEpochMs: Long = row.event.createdAtEpochMs
    }
}

/** Playable rows only; incident cards resolve to their owning internal recording. */
private sealed interface VehiclePlaybackItem {
    val key: String
    val startedAtEpochMs: Long

    data class Internal(val recording: RecorderLibrary.Recording) : VehiclePlaybackItem {
        override val key: String = "internal:${recording.id}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class Usb(val recording: VehicleUsbRecording) : VehiclePlaybackItem {
        override val key: String = "usb:${recording.stableKey}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class Sentry(val recording: VehicleSentryRecording) : VehiclePlaybackItem {
        override val key: String = "sentry:${recording.stableKey}"
        override val startedAtEpochMs: Long = recording.startedAtEpochMs
    }

    data class OpenAvmSentry(val row: OpenAvmSentryRow) : VehiclePlaybackItem {
        override val key: String = "openavm-sentry:${row.event.id}"
        override val startedAtEpochMs: Long = row.event.createdAtEpochMs
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val developerModeEnabled = remember { SettingsStore.get(context).developerModeEnabled }
    val segmentsDir = remember { File(context.filesDir, "recordings/segments").apply { mkdirs() } }
    val thumbnailCache = remember {
        RecordingThumbnailCache(File(context.cacheDir, "recording-covers"))
    }
    val languageMode by AppLanguage.mode.collectAsState()
    val recorderState by CameraRecordingService.state.collectAsState()
    val nativePublicationRevision by com.dante.zeekrcapabilitylab.service.recorder.NativeUsbPublication.revision.collectAsState()
    val guardStore = remember { GuardEventStore(context) }
    val phoneConnection by TransferRepository.connection.collectAsState()
    val usbExportTasks by UsbExportRepository.tasks.collectAsState()
    val pendingCameraCleanup by com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.pendingOwners.collectAsState()

    var segments by remember { mutableStateOf<List<EventGroups.Segment>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<RecorderLibrary.Recording>>(emptyList()) }
    var covers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var usbCovers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var usbMedia by remember { mutableStateOf(VehicleUsbMediaSnapshot()) }
    var guardRows by remember { mutableStateOf<List<OpenAvmSentryRow>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(VehicleMediaCategory.ALL) }
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val dateZone = ZoneId.systemDefault()
    val libraryGrid = rememberLazyGridState()
    LaunchedEffect(selectedEpochDay, selectedCategory) { libraryGrid.scrollToItem(0) }
    var statusText by remember { mutableStateOf("") }
    var playRecording by remember { mutableStateOf<RecorderLibrary.Recording?>(null) }
    var playUsbRecording by remember { mutableStateOf<VehicleUsbRecording?>(null) }
    var playSentryRecording by remember { mutableStateOf<VehicleSentryRecording?>(null) }
    var playGuardRow by remember { mutableStateOf<OpenAvmSentryRow?>(null) }
    var pendingDeleteRecording by remember { mutableStateOf<RecorderLibrary.Recording?>(null) }
    var pendingDeleteUsbRecording by remember { mutableStateOf<VehicleUsbRecording?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<EventGroups.EventGroup?>(null) }
    var confirmDeleteUnprotected by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedDeleteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var deletingSelection by remember { mutableStateOf(false) }
    var pendingUsbExport by remember { mutableStateOf<UsbExportSelection?>(null) }
    var availableUsbTargets by remember { mutableStateOf<List<UsbExportTarget>>(emptyList()) }
    var selectedUsbTarget by remember { mutableStateOf<UsbExportTarget?>(null) }
    var findingUsb by remember { mutableStateOf(false) }
    var browserShare by remember { mutableStateOf<ShareSelection?>(null) }

    fun shareInBrowser(selection: ShareSelection) {
        if (CameraRecordingService.isRunning() || pendingCameraCleanup > 0) {
            statusText = Utils.t("Stop recording and wait for the camera to finish closing first.", "请先停止录像，等待相机关闭完成。")
        } else browserShare = selection
    }

    fun refresh(scanUsb: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val items = RecorderLibrary.listFinalized(segmentsDir).mapNotNull { file ->
                SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.let {
                    EventGroups.Segment(file, it)
                }
            }
            val recordingItems = RecorderLibrary.listRecordings(segmentsDir)
            val sentryItems = guardStore?.let { store -> store.list().map { OpenAvmSentryRow(it, store.files(it)) } }.orEmpty()
            val removable = if (scanUsb) {
                VehicleUsbMediaLibrary.refresh(context)
            } else {
                VehicleUsbMediaLibrary.loadCached(context)
            }
            withContext(Dispatchers.Main) {
                segments = items
                recordings = recordingItems
                guardRows = sentryItems
                usbMedia = removable
                val names = items.mapTo(mutableSetOf()) { it.file.name }
                covers = covers.filterKeys { it in names }
                if (removable.scanErrors.isNotEmpty()) {
                    statusText = Utils.t(
                        "Some USB media could not be refreshed.",
                        "部分 USB 媒体未能刷新。",
                    )
                }
            }
        }
    }

    fun loadCover(segment: EventGroups.Segment) {
        val file = segment.file
        if (file.name in covers) return
        val presentation = RecordingPresentationPolicy.resolve(segment.sidecar)
        scope.launch(Dispatchers.IO) {
            val cover = thumbnailCache.loadOrCreate(file, presentation.layoutKind)
            if (cover != null) {
                withContext(Dispatchers.Main) {
                    covers = covers + (file.name to cover)
                }
            }
        }
    }

    fun loadUsbCover(recording: VehicleUsbRecording) {
        if (recording.stableKey in usbCovers || recording.availability != MediaAvailability.ONLINE) return
        val file = recording.files.firstOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val cover = thumbnailCache.loadOrCreate(file, recording.layoutKind)
            if (cover != null) withContext(Dispatchers.Main) {
                usbCovers = usbCovers + (recording.stableKey to cover)
            }
        }
    }

    fun loadSentryCover(recording: VehicleSentryRecording) {
        if (recording.stableKey in usbCovers || recording.availability != MediaAvailability.ONLINE) return
        scope.launch(Dispatchers.IO) {
            val cover = recording.thumbnailFile?.let(::decodeSampledCover)
                ?: recording.videoFile?.let { FourLaneThumbs.extractCover(it, 180) }
            if (cover != null) withContext(Dispatchers.Main) {
                usbCovers = usbCovers + (recording.stableKey to cover)
            }
        }
    }

    fun loadGuardCover(row: OpenAvmSentryRow) {
        val key = "openavm-sentry:${row.event.id}"
        if (key in usbCovers || row.event.state == "WRITING") return
        val file = row.files.firstOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val cover = thumbnailCache.loadOrCreate(file, row.event.source.layoutKind)
            if (cover != null) withContext(Dispatchers.Main) { usbCovers = usbCovers + (key to cover) }
        }
    }

    fun toggleFileProtection(segment: EventGroups.Segment) {
        scope.launch(Dispatchers.IO) {
            val changed = if (segment.sidecar.protected) {
                RecorderLibrary.unbookmark(segment.file)
            } else {
                RecorderLibrary.bookmark(segment.file)
            }
            withContext(Dispatchers.Main) {
                statusText = if (!changed) {
                    Utils.t("Unable to change protection", "保护状态修改失败")
                } else if (segment.sidecar.protected) {
                    Utils.t("Protection removed", "已取消保护")
                } else {
                    Utils.t("Recording protected", "录像已保护")
                }
            }
            refresh()
        }
    }

    fun toggleGroupProtection(group: EventGroups.EventGroup) {
        scope.launch(Dispatchers.IO) {
            val protect = group.protectedCount == 0
            val changed = group.segments.count { segment ->
                if (protect) RecorderLibrary.bookmark(segment.file)
                else RecorderLibrary.unbookmark(segment.file)
            }
            withContext(Dispatchers.Main) {
                statusText = if (protect) Utils.t("Protected {0} segments", "已保护 {0} 个分段", changed) else Utils.t("Removed protection from {0} segments", "已取消保护 {0} 个分段", changed)
            }
            refresh()
        }
    }

    fun toggleRecordingProtection(recording: RecorderLibrary.Recording) {
        scope.launch(Dispatchers.IO) {
            val protect = recording.protectedCount < recording.files.size
            val changed = RecorderLibrary.setRecordingProtected(recording, protect)
            withContext(Dispatchers.Main) {
                statusText = if (protect) {
                    Utils.t("Protected {0} files", "已保护 {0} 个文件", changed)
                } else {
                    Utils.t("Removed protection from {0} files", "已取消保护 {0} 个文件", changed)
                }
            }
            refresh()
        }
    }

    fun deleteRecording(recording: RecorderLibrary.Recording) {
        scope.launch(Dispatchers.IO) {
            recording.files.forEach(thumbnailCache::remove)
            val result = RecorderLibrary.deleteRecordingByUser(segmentsDir, recording)
            withContext(Dispatchers.Main) {
                if (playRecording?.id == recording.id) playRecording = null
                statusText = Utils.t("Deleted {0} files", "已删除 {0} 个文件", result.deleted) +
                    if (result.blocked > 0) Utils.t("; kept {0} locked files", "；保留 {0} 个锁定中的文件", result.blocked) else ""
            }
            refresh()
        }
    }

    fun deleteGroup(group: EventGroups.EventGroup) {
        scope.launch(Dispatchers.IO) {
            val results = group.segments.map { segment ->
                thumbnailCache.remove(segment.file)
                RecorderLibrary.deleteManaged(segmentsDir, segment.file)
            }
            val deleted = results.count { it.deleted }
            val blocked = results.size - deleted
            withContext(Dispatchers.Main) {
                statusText = Utils.t("Deleted {0} segments", "已删除 {0} 个分段", deleted) +
                    if (blocked > 0) Utils.t("; kept {0} protected or locked segments", "；受保护或锁定中的 {0} 个已保留", blocked) else ""
            }
            refresh()
        }
    }

    fun deleteAllRecordings() {
        val visibleFiles = segments.map { it.file }
        scope.launch(Dispatchers.IO) {
            visibleFiles.forEach(thumbnailCache::remove)
            val result = RecorderLibrary.deleteAllManagedByUser(segmentsDir)
            withContext(Dispatchers.Main) {
                playRecording = null
                covers = emptyMap()
                statusText = Utils.t("Deleted {0} recordings", "已删除 {0} 段录像", result.deleted) +
                    if (result.blocked > 0) Utils.t("; kept {0} active or transfer-locked recordings", "；{0} 段正在播放或传输锁定的录像已保留", result.blocked) else ""
            }
            refresh()
        }
    }

    fun deleteUnprotectedRecordings() {
        val unprotectedFiles = segments.filterNot { it.sidecar.protected }.map { it.file }
        scope.launch(Dispatchers.IO) {
            val result = RecorderLibrary.deleteAllUnprotected(segmentsDir)
            val deletedFiles = unprotectedFiles.filterNot { it.exists() }
            deletedFiles.forEach(thumbnailCache::remove)
            withContext(Dispatchers.Main) {
                if (playRecording?.files?.any(deletedFiles::contains) == true) playRecording = null
                covers = covers - deletedFiles.mapTo(mutableSetOf()) { it.name }
                statusText = Utils.t("Deleted {0} unprotected recordings", "已删除 {0} 段未保护录像", result.deleted) +
                    if (result.blocked > 0) Utils.t("; kept {0} protected or locked recordings", "；{0} 段受保护或锁定中的录像已保留", result.blocked) else ""
            }
            refresh()
        }
    }

    fun queueForPhone(files: List<File>): Boolean {
        val uniqueFiles = files.distinctBy { it.absolutePath }
        val sessionIds = uniqueFiles.mapNotNull { file ->
            SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.recordingSessionId
        }.distinct()
        val sessionId = sessionIds.singleOrNull()
        val result = TransferRepository.enqueueSelection(sessionId, uniqueFiles)
        statusText = result.fold(
            onSuccess = { queued ->
                if (phoneConnection.connected) {
                    Utils.t(
                        "Queued {0} selected segment{1} ({2})", "已加入 {0} 个选中分段（{2}）", queued.fileCount, if (queued.fileCount == 1) "" else "s", formatBytes(queued.totalBytes))
                } else {
                    Utils.t(
                        "Queued {0} selected segment{1}; waiting for the phone connection", "已加入 {0} 个选中分段；正在等待手机连接", queued.fileCount, if (queued.fileCount == 1) "" else "s")
                }
            },
            onFailure = { it.message ?: Utils.t("Unable to queue transfer", "无法加入传输队列") },
        )
        return result.isSuccess
    }

    fun queueSentryForPhone(recording: VehicleSentryRecording): Boolean {
        val video = recording.videoFile
        if (recording.availability != MediaAvailability.ONLINE || video == null) {
            statusText = Utils.t(
                "Reconnect the same USB before sending this Sentry event.",
                "请先重新插入同一 U 盘，再发送该哨兵事件。",
            )
            return false
        }
        val result = TransferRepository.enqueueFactorySentry(
            storageUuid = recording.storageUuid,
            eventId = recording.eventId,
            startedAtEpochMs = recording.startedAtEpochMs,
            videoFile = video,
        )
        statusText = result.fold(
            onSuccess = { queued ->
                if (phoneConnection.connected) {
                    Utils.t(
                        "Sentry event queued ({0})", "哨兵事件已加入发送队列（{0}）", formatBytes(queued.totalBytes))
                } else {
                    Utils.t(
                        "Sentry event queued; waiting for the phone connection",
                        "哨兵事件已加入队列，正在等待手机连接",
                    )
                }
            },
            onFailure = { it.message ?: Utils.t("Unable to queue Sentry transfer", "无法加入哨兵传输队列") },
        )
        return result.isSuccess
    }

    fun queueUsbForPhone(recording: VehicleUsbRecording, files: List<File>): Boolean {
        if (recording.availability != MediaAvailability.ONLINE) {
            statusText = Utils.t(
                "Reconnect the same USB before sending these segments.",
                "请先重新插入同一 U 盘，再发送这些分段。",
            )
            return false
        }
        val result = TransferRepository.enqueueOpenAvmUsb(
            storageUuid = recording.storageUuid,
            recordingSessionId = recording.logicalId.removePrefix("session:"),
            files = files,
        )
        statusText = result.fold(
            onSuccess = { queued ->
                if (phoneConnection.connected) {
                    Utils.t(
                        "Queued {0} USB segment{1} ({2})", "已加入 {0} 个 USB 分段（{2}）", queued.fileCount, if (queued.fileCount == 1) "" else "s", formatBytes(queued.totalBytes))
                } else {
                    Utils.t("USB segments queued; waiting for the phone connection", "USB 分段已加入队列，正在等待手机连接")
                }
            },
            onFailure = { it.message ?: Utils.t("Unable to queue USB transfer", "无法加入 USB 传输队列") },
        )
        return result.isSuccess
    }

    fun requestUsbExport(selection: UsbExportSelection) {
        if (CameraRecordingService.isRunning() || com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.pendingOwners.value > 0) {
            statusText = Utils.t(
                "Stop recording before exporting to USB.",
                "请先停止录像，再导出到 USB。",
            )
            return
        }
        findingUsb = true
        statusText = Utils.t("Waiting for a mounted USB…", "正在等待 USB 挂载…")
        scope.launch {
            val targets = withContext(Dispatchers.IO) {
                UsbExportVolumeResolver.awaitMountedTargets(context)
            }
            findingUsb = false
            if (targets.isEmpty()) {
                statusText = Utils.t(
                    "No writable removable MediaStore volume is ready.",
                    "尚未检测到可用的可移动 MediaStore USB。",
                )
            } else {
                pendingUsbExport = selection
                availableUsbTargets = targets
                selectedUsbTarget = targets.singleOrNull()
                statusText = ""
            }
        }
    }

    fun enqueueUsbExport() {
        val selection = pendingUsbExport ?: return
        val target = selectedUsbTarget ?: return
        scope.launch(Dispatchers.IO) {
            val result = UsbExportRepository.enqueue(
                logicalId = selection.logicalId,
                recordingMode = selection.recordingMode,
                startedAtEpochMs = selection.startedAtEpochMs,
                stoppedAtEpochMs = selection.stoppedAtEpochMs,
                files = selection.files,
                target = target,
            )
            withContext(Dispatchers.Main) {
                statusText = result.fold(
                    onSuccess = {
                        Utils.t(
                            "USB export queued. Internal recordings will be kept.",
                            "USB 导出已加入队列，内部录像将保留。",
                        )
                    },
                    onFailure = {
                        Utils.t(
                            "Unable to queue USB export: {0}", "无法加入 USB 导出：{0}", it.message ?: it.javaClass.simpleName)
                    },
                )
                pendingUsbExport = null
                selectedUsbTarget = null
                availableUsbTargets = emptyList()
            }
        }
    }

    fun copyUsbReport(task: UsbExportTask) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("OpenAVM USB export report", UsbExportRepository.reportJson(task)),
        )
        statusText = Utils.t("USB export report copied.", "USB 导出报告已复制。")
    }

    LaunchedEffect(Unit) { refresh(scanUsb = true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(recorderState.libraryRevision) {
        if (recorderState.libraryRevision > 0L) refresh(scanUsb = false)
    }

    LaunchedEffect(nativePublicationRevision) {
        if (nativePublicationRevision > 0L) refresh(scanUsb = true)
    }

    LaunchedEffect(guardRows, playGuardRow?.event?.id) {
        val selectedId = playGuardRow?.event?.id ?: return@LaunchedEffect
        playGuardRow = guardRows.firstOrNull { it.event.id == selectedId }
    }

    LaunchedEffect(recordings, playRecording?.id) {
        val selectedId = playRecording?.id ?: return@LaunchedEffect
        playRecording = recordings.firstOrNull { it.id == selectedId }
    }

    LaunchedEffect(usbMedia, playUsbRecording?.stableKey, playSentryRecording?.stableKey) {
        playUsbRecording = playUsbRecording?.let { selected ->
            usbMedia.openAvmRecordings.firstOrNull { it.stableKey == selected.stableKey }
        }
        playSentryRecording = playSentryRecording?.let { selected ->
            usbMedia.sentryRecordings.firstOrNull { it.stableKey == selected.stableKey }
        }
    }

    val incidents = remember(segments, languageMode) {
        EventGroups.groupIncidents(
            segments.filter { it.sidecar.recordingMode == RecordingMode.NORMAL },
        )
    }
    val visibleIncidents = remember(incidents, selectedCategory, selectedEpochDay, dateZone) {
        if (selectedCategory in setOf(VehicleMediaCategory.ALL, VehicleMediaCategory.EVENTS)) {
            incidents.filter { RecordingDateFilter.matches(it.startedAtEpochMs, selectedEpochDay, dateZone) }
        } else {
            emptyList()
        }
    }
    val visibleRecordings = remember(recordings, selectedCategory, selectedEpochDay, dateZone) {
        when (selectedCategory) {
            VehicleMediaCategory.ALL -> recordings
            VehicleMediaCategory.NORMAL -> recordings.filterNot { it.isTimeLapse }
            VehicleMediaCategory.TIME_LAPSE -> recordings.filter { it.isTimeLapse }
            VehicleMediaCategory.EVENTS, VehicleMediaCategory.OPENAVM_SENTRY, VehicleMediaCategory.SENTRY -> emptyList()
        }.filter { RecordingDateFilter.matches(it.startedAtEpochMs, selectedEpochDay, dateZone) }
    }
    val internalIds = remember(recordings) { recordings.mapTo(mutableSetOf()) { it.id } }
    val visibleUsbRecordings = remember(usbMedia, selectedCategory, internalIds, selectedEpochDay, dateZone) {
        usbMedia.openAvmRecordings.filter { recording ->
            RecordingDateFilter.matches(recording.startedAtEpochMs, selectedEpochDay, dateZone) &&
                (recording.logicalId !in internalIds || recording.containsDirectRecording) &&
                (selectedCategory == VehicleMediaCategory.ALL || recording.category == selectedCategory ||
                    (selectedCategory == VehicleMediaCategory.EVENTS && recording.eventTimes.isNotEmpty()))
        }
    }
    val visibleSentryRecordings = remember(usbMedia, selectedCategory, selectedEpochDay, dateZone) {
        if (selectedCategory in setOf(VehicleMediaCategory.ALL, VehicleMediaCategory.SENTRY)) {
            usbMedia.sentryRecordings.filter { RecordingDateFilter.matches(it.startedAtEpochMs, selectedEpochDay, dateZone) }
        } else {
            emptyList()
        }
    }
    val visibleGuardRows = remember(guardRows, selectedCategory, selectedEpochDay, dateZone) {
        if (selectedCategory in setOf(VehicleMediaCategory.ALL, VehicleMediaCategory.OPENAVM_SENTRY))
            guardRows.filter { RecordingDateFilter.matches(it.event.createdAtEpochMs, selectedEpochDay, dateZone) } else emptyList()
    }
    val timelineItems = remember(
        visibleIncidents,
        visibleRecordings,
        visibleUsbRecordings,
        visibleSentryRecordings,
        visibleGuardRows,
    ) {
        buildList<VehicleTimelineItem> {
            visibleIncidents.forEach { add(VehicleTimelineItem.Incident(it)) }
            visibleRecordings.forEach { add(VehicleTimelineItem.InternalRecording(it)) }
            visibleUsbRecordings.forEach { add(VehicleTimelineItem.UsbRecording(it)) }
            visibleSentryRecordings.forEach { add(VehicleTimelineItem.FactorySentry(it)) }
            visibleGuardRows.forEach { add(VehicleTimelineItem.OpenAvmSentry(it)) }
        }.sortedByDescending { it.startedAtEpochMs }
    }
    val visibleCount = timelineItems.size
    val timelineByDate = remember(timelineItems) {
        timelineItems.groupBy { recordingDate(it.startedAtEpochMs) }
            .toList()
            .sortedByDescending { it.first }
    }
    val playbackTimeline = remember(
        visibleRecordings,
        visibleUsbRecordings,
        visibleSentryRecordings,
        visibleGuardRows,
        visibleIncidents,
        selectedCategory,
        recordings,
    ) {
        buildList<VehiclePlaybackItem> {
            visibleRecordings.forEach { add(VehiclePlaybackItem.Internal(it)) }
            visibleUsbRecordings.forEach { add(VehiclePlaybackItem.Usb(it)) }
            visibleSentryRecordings.forEach { add(VehiclePlaybackItem.Sentry(it)) }
            visibleGuardRows.filter { it.event.state != "WRITING" && it.files.isNotEmpty() }
                .forEach { add(VehiclePlaybackItem.OpenAvmSentry(it)) }
            if (selectedCategory == VehicleMediaCategory.EVENTS) {
                visibleIncidents.forEach { incident ->
                    val firstFile = incident.segments.firstOrNull()?.file ?: return@forEach
                    recordings.firstOrNull { firstFile in it.files }?.let {
                        add(VehiclePlaybackItem.Internal(it))
                    }
                }
            }
        }.distinctBy { it.key }
            .sortedByDescending { it.startedAtEpochMs }
    }
    val usbDeleteAllowed = !CameraRecordingService.isRunning() && pendingCameraCleanup == 0
    val selectableKeys = remember(timelineItems, usbDeleteAllowed) {
        timelineItems.mapNotNullTo(mutableSetOf()) { item ->
            when (item) {
                is VehicleTimelineItem.Incident,
                is VehicleTimelineItem.InternalRecording -> item.key
                is VehicleTimelineItem.UsbRecording -> item.key.takeIf {
                    usbDeleteAllowed && item.recording.availability == MediaAvailability.ONLINE &&
                        item.recording.ownedUnits.isNotEmpty()
                }
                is VehicleTimelineItem.FactorySentry, is VehicleTimelineItem.OpenAvmSentry -> null
            }
        }
    }
    val selectedTimelineItems = timelineItems.filter { it.key in selectedDeleteKeys && it.key in selectableKeys }
    val selectedInternalFiles = selectedTimelineItems.flatMap { item ->
        when (item) {
            is VehicleTimelineItem.Incident -> item.group.segments.map { it.file }
            is VehicleTimelineItem.InternalRecording -> item.recording.files
            else -> emptyList()
        }
    }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    val selectedUsbRecordings = selectedTimelineItems.mapNotNull {
        (it as? VehicleTimelineItem.UsbRecording)?.recording
    }.distinctBy { it.stableKey }
    val selectedProtectedFiles = selectedInternalFiles.count { file ->
        SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.protected == true
    }
    val selectedDeleteBytes = selectedInternalFiles.sumOf(File::length) +
        selectedUsbRecordings.sumOf { it.totalBytes }

    fun openPlayback(item: VehiclePlaybackItem) {
        playRecording = null
        playUsbRecording = null
        playSentryRecording = null
        playGuardRow = null
        when (item) {
            is VehiclePlaybackItem.Internal -> playRecording = item.recording
            is VehiclePlaybackItem.Usb -> playUsbRecording = item.recording
            is VehiclePlaybackItem.Sentry -> playSentryRecording = item.recording
            is VehiclePlaybackItem.OpenAvmSentry -> playGuardRow = item.row
        }
    }

    fun playbackNeighbor(key: String, offset: Int): VehiclePlaybackItem? {
        val index = playbackTimeline.indexOfFirst { it.key == key }
        if (index < 0) return null
        return playbackTimeline.getOrNull(index + offset)
    }
    val visibleUsbTasks = usbExportTasks.filter { it.statusCardVisible() }
    val latestUsbTask = visibleUsbTasks
        .filter {
            it.state !in setOf(
                UsbExportState.COMPLETED,
                UsbExportState.ALREADY_EXPORTED,
            )
        }

        .maxByOrNull { it.updatedAtEpochMs }
        ?: visibleUsbTasks.maxByOrNull { it.updatedAtEpochMs }


    fun toggleSelection(key: String) {
        if (key !in selectableKeys) return
        selectedDeleteKeys = if (key in selectedDeleteKeys) selectedDeleteKeys - key else selectedDeleteKeys + key
    }

    fun leaveSelectionMode() {
        selectedDeleteKeys = emptySet()
        selectionMode = false
        confirmDeleteSelected = false
    }

    fun deleteUsbRecording(recording: VehicleUsbRecording) {
        if (deletingSelection) return
        deletingSelection = true
        scope.launch(Dispatchers.IO) {
            val result = OpenAvmUsbDeletionManager(context).delete(
                listOf(OpenAvmUsbDeleteRequest(recording.stableKey, recording.storageUuid, recording.ownedUnits, includeProtected = true)),
            )
            val details = result.errors.map(::usbDeleteMessage).distinct().joinToString(" | ").take(360)
            withContext(Dispatchers.Main) {
                deletingSelection = false
                statusText = if (result.deletedRecordings == 1) {
                    Utils.t(
                        "USB recording deleted ({0} owned units, {1}).", "U 盘录像已删除（{0} 个归属单元，{1}）。", result.deletedUnits, formatBytes(result.deletedBytes))
                } else {
                    Utils.t(
                        "USB recording was not deleted: {0}", "U 盘录像未删除：{0}", details)
                }
                playUsbRecording = null
            }
            refresh(scanUsb = true)
        }
    }

    fun deleteSelected() {
        if (deletingSelection || selectedTimelineItems.isEmpty()) return
        val internalFiles = selectedInternalFiles
        val usbRequests = selectedUsbRecordings.map { recording ->
            OpenAvmUsbDeleteRequest(recording.stableKey, recording.storageUuid, recording.ownedUnits, includeProtected = true)
        }
        confirmDeleteSelected = false
        deletingSelection = true
        scope.launch(Dispatchers.IO) {
            val internalResults = internalFiles.map { RecorderLibrary.deleteManagedByUser(segmentsDir, it) }
            val usbResult = OpenAvmUsbDeletionManager(context).delete(usbRequests)
            val deletedInternal = internalResults.count { it.deleted }
            val blockedInternal = internalResults.size - deletedInternal
            internalFiles.filterNot(File::exists).forEach(thumbnailCache::remove)
            withContext(Dispatchers.Main) {
                deletingSelection = false
                leaveSelectionMode()
                val usbErrors = usbResult.errors.map(::usbDeleteMessage).distinct().joinToString(" | ").take(360)
                statusText = Utils.t("Deleted {0} local files and {1} USB recordings", "已删除 {0} 个本地文件和 {1} 条 U 盘录像", deletedInternal, usbResult.deletedRecordings) +
                    if (blockedInternal + usbResult.blockedRecordings > 0) {
                        Utils.t("; {0} blocked", "；{0} 项被阻止", blockedInternal + usbResult.blockedRecordings) +
                            if (usbErrors.isNotBlank()) ": $usbErrors" else ""
                    } else ""
                playRecording = null
                playUsbRecording = null
            }
            refresh(scanUsb = true)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnCount = when {
            // Twice the previous column count: each square card is roughly
            // half as wide/high, or one quarter of its previous area.
            maxWidth >= 1_200.dp -> 8
            maxWidth >= 760.dp -> 6
            else -> 4
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = libraryGrid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecordingLibraryHeader(
                        subtitle = if (selectionMode) Utils.t(
                            "{0} selected · {1}", "已选 {0} 项 · {1}", selectedTimelineItems.size, formatBytes(selectedDeleteBytes))
                        else Utils.t("{0} items", "{0} 项内容", visibleCount),
                    ) {
                            if (selectionMode) {
                                OutlinedButton(onClick = { selectedDeleteKeys = selectableKeys }, enabled = selectableKeys.isNotEmpty() && !deletingSelection) {
                                    Text(Utils.t("Select visible", "全选当前"))
                                }
                                OutlinedButton(onClick = { selectedDeleteKeys = emptySet() }, enabled = selectedDeleteKeys.isNotEmpty() && !deletingSelection) {
                                    Text(Utils.t("Clear", "清除选择"))
                                }
                                OutlinedButton(onClick = { confirmDeleteSelected = true }, enabled = selectedTimelineItems.isNotEmpty() && !deletingSelection) {
                                    Text(Utils.t("Delete selected", "删除所选"), color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedButton(onClick = { leaveSelectionMode() }, enabled = !deletingSelection) {
                                    Text(Utils.t("Cancel", "取消"))
                                }
                            } else {
                                OutlinedButton(onClick = { selectionMode = true }) { Text(Utils.t("Select", "多选")) }
                                if (selectedEpochDay == null) OutlinedButton(onClick = { confirmDeleteUnprotected = true }, enabled = segments.any { !it.sidecar.protected }) {
                                    Text(Utils.t("Delete unprotected", "删除未保护"))
                                }
                                if (selectedEpochDay == null) OutlinedButton(onClick = { confirmDeleteAll = true }, enabled = segments.isNotEmpty()) {
                                    Text(
                                        Utils.t("Clear recordings", "清空录像"),
                                        color = if (segments.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Unspecified,
                                    )
                                }
                                OutlinedButton(onClick = { refresh(scanUsb = true) }) { Text(Utils.t("Refresh", "刷新")) }
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VehicleMediaCategory.entries.filter { category ->
                            category != VehicleMediaCategory.OPENAVM_SENTRY || guardRows.isNotEmpty() ||
                                usbMedia.openAvmRecordings.any { it.category == VehicleMediaCategory.OPENAVM_SENTRY }
                        }.forEach { category ->
                            OutlinedButton(enabled = !deletingSelection, onClick = {
                                selectedCategory = category
                                if (selectionMode) selectedDeleteKeys = emptySet()
                            }) {
                                Text(
                                    categoryLabel(category) +
                                        if (selectedCategory == category) " ✓" else "",
                                )
                            }
                        }
                    }
                    RecordingDateControls(selectedEpochDay, enabled = !deletingSelection) { date ->
                        selectedDeleteKeys = emptySet()
                        confirmDeleteSelected = false
                        selectedEpochDay = date
                    }
                    if (statusText.isNotBlank()) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (latestUsbTask != null) {
                item(key = "usb-export-status", span = { GridItemSpan(maxLineSpan) }) {
                    UsbExportTaskCard(
                        task = latestUsbTask,
                        onCancel = { UsbExportRepository.requestCancel(latestUsbTask.id) },
                        onRetry = { UsbExportRepository.retry(latestUsbTask.id) },
                        onDismiss = { UsbExportRepository.dismissStatusCard(latestUsbTask.id) },
                        onCopyReport = { copyUsbReport(latestUsbTask) },
                        showDiagnostics = developerModeEnabled,
                    )
                }
            }

            if (visibleCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (selectedEpochDay == null) Utils.t("No recordings", "暂无录像") else
                            Utils.t("No recordings for this date and category", "这个日期和分类下暂无录像"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            timelineByDate.forEach { (date, group) ->
                item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(formatDateHeader(date), Utils.t("{0}", "{0} 条", group.size))
                }
                items(
                    items = group.sortedByDescending { it.startedAtEpochMs },
                    key = { it.key },
                ) { entry ->
                    when (entry) {
                        is VehicleTimelineItem.OpenAvmSentry -> {
                            val row = entry.row
                            LaunchedEffect(row.event.id, row.event.state) { loadGuardCover(row) }
                            OpenAvmSentryMediaCard(
                                row = row,
                                cover = usbCovers[entry.key],
                                selectionMode = selectionMode,
                                onPlay = { openPlayback(VehiclePlaybackItem.OpenAvmSentry(row)) },
                                onExport = { requestUsbExport(UsbExportSelection(
                                    logicalId = row.event.id,
                                    recordingMode = "SENTRY",
                                    startedAtEpochMs = row.event.createdAtEpochMs,
                                    stoppedAtEpochMs = row.event.createdAtEpochMs + row.files.sumOf { file ->
                                        SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.realDurationMs ?: 0L
                                    },
                                    files = row.files,
                                )) },
                                exportEnabled = !CameraRecordingService.isRunning() && !findingUsb,
                            )
                        }
                        is VehicleTimelineItem.Incident -> {
                            val event = entry.group
                            val first = event.segments.first()
                            LaunchedEffect(first.file.name) { loadCover(first) }
                            SavedEventCard(
                                group = event,
                                cover = covers[first.file.name],
                                onPlay = { playRecording = recordings.firstOrNull { first.file in it.files } },
                                onToggleProtect = { toggleGroupProtection(event) },
                                onSendToPhone = { queueForPhone(event.segments.map { it.file }) },
                                onExportToUsb = {
                                    requestUsbExport(
                                        UsbExportSelection(
                                            logicalId = EventGroups.stableIncidentKey(event),
                                            recordingMode = first.sidecar.recordingMode.name,
                                            startedAtEpochMs = event.startedAtEpochMs,
                                            stoppedAtEpochMs = event.stoppedAtEpochMs,
                                            files = event.segments.map { it.file },
                                        ),
                                    )
                                },
                                usbExportEnabled = !CameraRecordingService.isRunning() && !findingUsb,
                                onDelete = { pendingDeleteGroup = event },
                                selectionMode = selectionMode,
                                selected = entry.key in selectedDeleteKeys,
                                onToggleSelection = { toggleSelection(entry.key) },
                            )
                        }
                        is VehicleTimelineItem.InternalRecording -> {
                            val recording = entry.recording
                            val segment = EventGroups.Segment(recording.firstFile, recording.sidecar)
                            LaunchedEffect(segment.file.name) { loadCover(segment) }
                            RecordingCard(
                                segment = segment,
                                recording = recording,
                                cover = covers[segment.file.name],
                                onPlay = { playRecording = recording },
                                onToggleProtect = { toggleRecordingProtection(recording) },
                                onSendToPhone = { queueForPhone(recording.files) },
                                onBrowserShare = { shareInBrowser(ShareSelection.Internal(recording.files)) },
                                onExportToUsb = {
                                    requestUsbExport(
                                        UsbExportSelection(
                                            logicalId = recording.id,
                                            recordingMode = recording.sidecar.recordingMode.name,
                                            startedAtEpochMs = recording.startedAtEpochMs,
                                            stoppedAtEpochMs = recording.stoppedAtEpochMs,
                                            files = recording.files,
                                        ),
                                    )
                                },
                                usbExportEnabled = !CameraRecordingService.isRunning() && !findingUsb,
                                onDelete = { pendingDeleteRecording = recording },
                                selectionMode = selectionMode,
                                selected = entry.key in selectedDeleteKeys,
                                onToggleSelection = { toggleSelection(entry.key) },
                            )
                        }
                        is VehicleTimelineItem.UsbRecording -> {
                            val recording = entry.recording
                            LaunchedEffect(recording.stableKey, recording.availability) { loadUsbCover(recording) }
                            UsbOpenAvmMediaCard(
                                recording = recording,
                                cover = usbCovers[recording.stableKey],
                                onPlay = {
                                    if (recording.availability == MediaAvailability.ONLINE) {
                                        playUsbRecording = recording
                                    }
                                },
                                onSendToPhone = { queueUsbForPhone(recording, recording.files) },
                                onBrowserShare = { shareInBrowser(ShareSelection.Usb(recording)) },
                                onDelete = { pendingDeleteUsbRecording = recording },
                                deleteEnabled = entry.key in selectableKeys && !deletingSelection,
                                selectionMode = selectionMode,
                                selected = entry.key in selectedDeleteKeys,
                                selectionEnabled = entry.key in selectableKeys,
                                onToggleSelection = { toggleSelection(entry.key) },
                            )
                        }
                        is VehicleTimelineItem.FactorySentry -> {
                            val sentry = entry.recording
                            LaunchedEffect(sentry.stableKey, sentry.availability) { loadSentryCover(sentry) }
                            UsbSentryMediaCard(
                                recording = sentry,
                                cover = usbCovers[sentry.stableKey],
                                onPlay = {
                                    if (sentry.availability == MediaAvailability.ONLINE) {
                                        playSentryRecording = sentry
                                    }
                                },
                                onSendToPhone = { queueSentryForPhone(sentry) },
                                onBrowserShare = { shareInBrowser(ShareSelection.Factory(sentry)) },
                                selectionMode = selectionMode,
                            )
                        }
                    }
                }
            }
        }
    }

    if (BuildConfig.BROWSER_DOWNLOAD_ENABLED) {
        browserShare?.let { selection -> BrowserShareDialog(selection) { browserShare = null } }
    }

    playGuardRow?.let { row ->
        val event = row.event
        if (row.files.isNotEmpty()) {
            val playbackKey = "openavm-sentry:${event.id}"
            val previousItem = playbackNeighbor(playbackKey, -1)
            val nextItem = playbackNeighbor(playbackKey, 1)
            val markers = remember(event, row.files) {
                row.files.associate { file -> file.absolutePath to event.assets.firstOrNull { it.name == file.name }
                    ?.let { GuardVideoPresentation.markers(event, it) }.orEmpty() }
            }
            FourLanePlayerDialog(
                file = row.files.first(), files = row.files,
                layoutKind = event.source.layoutKind, sourceRole = event.source.sourceRole,
                compositeWidth = event.source.profile.size.width, compositeHeight = event.source.profile.size.height,
                displayTitle = Utils.t("OpenAVM Sentry video", "OpenAVM 哨兵视频"),
                triggerMarkersByFile = markers,
                onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
                onNext = nextItem?.let { next -> { openPlayback(next) } },
                onSendToPhone = { queueForPhone(it) },
                onDismiss = { playGuardRow = null },
            )
        }
    }

    playRecording?.let { recording ->
        val file = recording.firstFile
        val presentation = RecordingPresentationPolicy.resolve(recording.sidecar)
        val playbackKey = "internal:${recording.id}"
        val previousItem = playbackNeighbor(playbackKey, -1)
        val nextItem = playbackNeighbor(playbackKey, 1)
        FourLanePlayerDialog(
            file = file,
            files = recording.files,
            layoutKind = presentation?.layoutKind,
            sourceRole = presentation?.sourceRole,
            recordingMode = recording.sidecar.recordingMode,
            timeLapseMultiplier = recording.sidecar.timeLapseMultiplier,
            realDurationMs = recording.realDurationMs.takeIf { recording.isTimeLapse },
            onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
            onNext = nextItem?.let { next -> { openPlayback(next) } },
            onSendToPhone = {
                queueForPhone(it)
            },
            onDelete = {
                playRecording = null
                pendingDeleteRecording = recording
            },
            onDismiss = { playRecording = null },
        )
    }

    playUsbRecording?.let { recording ->
        val playbackKey = "usb:${recording.stableKey}"
        val previousItem = playbackNeighbor(playbackKey, -1)
        val nextItem = playbackNeighbor(playbackKey, 1)
        if (recording.availability == MediaAvailability.ONLINE && recording.files.isNotEmpty()) {
            FourLanePlayerDialog(
                file = recording.files.first(),
                files = recording.files,
                segmentDurationHintsMs = recording.segmentDurationsMs,
                manualEventEpochs = recording.eventTimes,
                layoutKind = recording.layoutKind,
                sourceRole = recording.sourceRole,
                recordingMode = if (recording.category == VehicleMediaCategory.TIME_LAPSE) {
                    RecordingMode.TIME_LAPSE
                } else {
                    RecordingMode.NORMAL
                },
                timeLapseMultiplier = recording.timeLapseMultiplier,
                realDurationMs = (recording.stoppedAtEpochMs - recording.startedAtEpochMs)
                    .takeIf { recording.category == VehicleMediaCategory.TIME_LAPSE && it > 0L },
                recordedAtEpochMs = recording.startedAtEpochMs,
                displayTitle = if (recording.category == VehicleMediaCategory.OPENAVM_SENTRY) {
                    Utils.t("OpenAVM Sentry video", "OpenAVM 哨兵视频")
                } else Utils.t("USB recording playback", "U 盘录像回放"),
                onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
                onNext = nextItem?.let { next -> { openPlayback(next) } },
                onSendToPhone = { selected -> queueUsbForPhone(recording, selected) },
                onDelete = if (recording.ownedUnits.isNotEmpty() && !CameraRecordingService.isRunning() && pendingCameraCleanup == 0) {
                    {
                        playUsbRecording = null
                        pendingDeleteUsbRecording = recording
                    }
                } else {
                    null
                },
                onDismiss = { playUsbRecording = null },
            )
        } else {
            OfflinePlaybackDialog(
                title = Utils.t("USB recording offline", "U 盘录像离线"),
                recordedAtEpochMs = recording.startedAtEpochMs,
                storageDescription = recording.storageDescription,
                onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
                onNext = nextItem?.let { next -> { openPlayback(next) } },
                onDismiss = { playUsbRecording = null },
            )
        }
    }

    playSentryRecording?.let { sentry ->
        val playbackKey = "sentry:${sentry.stableKey}"
        val previousItem = playbackNeighbor(playbackKey, -1)
        val nextItem = playbackNeighbor(playbackKey, 1)
        if (sentry.availability == MediaAvailability.ONLINE && sentry.videoFile != null) {
            val video = sentry.videoFile ?: return@let
            FourLanePlayerDialog(
                file = video,
                files = listOf(video),
                layoutKind = RecordingLayoutKind.FOUR_LANE_V1,
                sourceRole = RecordingSourceRole.SURROUND,
                compositeWidth = 2560,
                compositeHeight = 2560,
                laneLabels = listOf(
                    Utils.t("Top left", "左上"),
                    Utils.t("Top right", "右上"),
                    Utils.t("Bottom left", "左下"),
                    Utils.t("Bottom right", "右下"),
                ),
                recordedAtEpochMs = sentry.startedAtEpochMs,
                displayTitle = Utils.t("Factory Sentry video", "原车哨兵视频"),
                onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
                onNext = nextItem?.let { next -> { openPlayback(next) } },
                onSendToPhone = { queueSentryForPhone(sentry) },
                onDismiss = { playSentryRecording = null },
            )
        } else {
            OfflinePlaybackDialog(
                title = Utils.t("Factory Sentry offline", "原车哨兵离线"),
                recordedAtEpochMs = sentry.startedAtEpochMs,
                storageDescription = sentry.storageDescription,
                onPrevious = previousItem?.let { previous -> { openPlayback(previous) } },
                onNext = nextItem?.let { next -> { openPlayback(next) } },
                onDismiss = { playSentryRecording = null },
            )
        }
    }

    if (pendingUsbExport != null && selectedUsbTarget == null && availableUsbTargets.size > 1) {
        AlertDialog(
            onDismissRequest = {
                pendingUsbExport = null
                availableUsbTargets = emptyList()
            },
            title = { Text(Utils.t("Choose USB target", "选择 USB 目标")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Utils.t(
                            "For safety, this export will not switch to another drive automatically.",
                            "为确保安全，本次导出不会自动切换到其他 U 盘。",
                        ),
                    )
                    availableUsbTargets.forEach { target ->
                        OutlinedButton(
                            onClick = { selectedUsbTarget = target },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${target.description} · " +
                                    Utils.t("free", "剩余") + " ${target.freeBytes?.let(::formatBytes) ?: "?"}",
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    pendingUsbExport = null
                    availableUsbTargets = emptyList()
                }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }

    val exportSelection = pendingUsbExport
    val exportTarget = selectedUsbTarget
    if (exportSelection != null && exportTarget != null) {
        AlertDialog(
            onDismissRequest = {
                pendingUsbExport = null
                selectedUsbTarget = null
                availableUsbTargets = emptyList()
            },
            title = { Text(Utils.t("Export to USB (experimental)?", "导出到 USB（实验性）？")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${exportTarget.description} · ${exportTarget.storageUuid}")
                    Text(
                        Utils.t("USB free", "USB 剩余") + " " +
                            (exportTarget.freeBytes?.let(::formatBytes) ?: "?") + " / " +
                            Utils.t("total", "总容量") + " " +
                            (exportTarget.totalBytes?.let(::formatBytes) ?: "?"),
                    )
                    Text(
                        Utils.t(
                            "{0} safety files · {1}", "{0} 个安全分段 · {1}", exportSelection.files.size, formatBytes(exportSelection.files.sumOf(File::length))),
                    )
                    Text(UsbExportPolicy.RELATIVE_PATH)
                    Text(
                        Utils.t(
                            "OpenAVM USB quota: {0} · transaction reserve: 10 GiB. Oldest verified OpenAVM segments may be rotated; SentryMode is never quota-cleaned.", "OpenAVM USB 配额：{0} · 空间预留：10 GiB。最旧且已验证的 OpenAVM 分段可能轮转；配额绝不会清理 SentryMode。", formatBytes(SettingsStore.get(context).usbQuotaBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        Utils.t(
                            "Files are copied, reread and verified before publication. Internal recordings are never deleted.",
                            "文件会在发布前复制、回读并校验；内部录像绝不会因导出而删除。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::enqueueUsbExport) {
                    Text(Utils.t("Start export", "开始导出"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingUsbExport = null
                    selectedUsbTarget = null
                    availableUsbTargets = emptyList()
                }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(Utils.t("Delete selected recordings?", "删除所选录像？")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Utils.t(
                        "{0} library items · {1} unique internal files · {2} complete OpenAVM USB recordings · {3}", "{0} 项媒体 · {1} 个去重后的内部文件 · {2} 条完整 OpenAVM U 盘录像 · {3}", selectedTimelineItems.size, selectedInternalFiles.size, selectedUsbRecordings.size, formatBytes(selectedDeleteBytes)))
                    if (selectedProtectedFiles > 0) Text(
                        Utils.t(
                            "{0} protected internal files are included by this explicit action.", "本次明确操作包含 {0} 个受保护内部文件。", selectedProtectedFiles),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (selectedUsbRecordings.any { it.protectedSegments > 0 }) Text(Utils.t("Protected USB clips are included in this permanent deletion.", "本次永久删除包含已保护的 USB 片段。"), color = MaterialTheme.colorScheme.error)
                    Text(Utils.t(
                        "Playback, transfer and active recording locks still win. Factory /SentryMode/ is read-only and excluded.",
                        "播放、传输和正在录像的锁仍然优先。原厂 /SentryMode/ 只读且已排除。",
                    ))
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteSelected() }) {
                    Text(Utils.t("Delete permanently", "永久删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }

    pendingDeleteUsbRecording?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDeleteUsbRecording = null },
            title = { Text(Utils.t("Delete this USB recording?", "删除这段 U 盘录像？")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (recording.protectedSegments > 0) Text(Utils.t("Protected USB clips are included in this permanent deletion.", "本次永久删除包含已保护的 USB 片段。"), color = MaterialTheme.colorScheme.error)
                    Text(
                        Utils.t(
                            "This permanently deletes all {0} manifest-proven OpenAVM units in this recording ({1}).", "这会永久删除本次录像中全部 {0} 个经 manifest 验证的 OpenAVM 单元（{1}）。", recording.ownedUnits.size, formatBytes(recording.totalBytes)),
                    )
                    Text(
                        Utils.t(
                            "Factory /SentryMode/ content is never included.",
                            "原厂 /SentryMode/ 内容绝不会包含在内。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteUsbRecording = null
                    deleteUsbRecording(recording)
                }) {
                    Text(Utils.t("Delete permanently", "永久删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteUsbRecording = null }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }

    pendingDeleteRecording?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecording = null },
            title = { Text(Utils.t("Delete this recording?", "删除这段录像？")) },
            text = {
                Text(
                    if (recording.files.size > 1) {
                        Utils.t(
                            "All {0} safety files in this recording will be permanently deleted.", "这会永久删除本次录像的全部 {0} 个安全分段。", recording.files.size)
                    } else {
                        Utils.t("This will permanently delete the recording, including a protected recording.", "这会永久删除该录像，包括已保护录像。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteRecording = null
                    deleteRecording(recording)
                }) { Text(Utils.t("Delete", "删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecording = null }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(Utils.t("Delete {0} segments?", "删除 {0} 个分段？", group.segments.size)) },
            text = { Text(Utils.t("Protected events will be kept.", "已保护事件会被保留。")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteGroup = null
                    deleteGroup(group)
                }) { Text(Utils.t("Delete", "删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGroup = null }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }

    if (confirmDeleteUnprotected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteUnprotected = false },
            title = { Text(Utils.t("Delete unprotected recordings?", "删除未保护录像？")) },
            text = {
                Text(
                    Utils.t(
                        "This permanently deletes all unprotected recordings stored on the vehicle. Protected recordings and recordings being played or transferred are kept.",
                        "这会永久删除保存在车机上的全部未保护录像。已保护、正在播放或正在传输的录像会被保留。",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteUnprotected = false
                        deleteUnprotectedRecordings()
                    },
                ) {
                    Text(Utils.t("Delete unprotected", "删除未保护"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteUnprotected = false }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(Utils.t("Delete all recordings?", "删除全部录像？")) },
            text = {
                Text(
                    Utils.t(
                        "This permanently deletes all recordings stored on the vehicle, including protected recordings. Recordings being played or transferred are kept.",
                        "这会永久删除保存在车机上的全部录像，包括已保护录像。正在播放或传输中的录像会被保留。",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        deleteAllRecordings()
                    },
                ) {
                    Text(Utils.t("Delete all", "全部删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(Utils.t("Cancel", "取消"))
                }
            },
        )
    }
}

/** Keep library actions at the top right; only a narrow window puts them on a second row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecordingLibraryHeader(subtitle: String, actions: @Composable () -> Unit) {
    val title: @Composable () -> Unit = {
        Column {
            Text(Utils.t("Recordings", "录像记录"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        androidx.compose.foundation.layout.FlowRow(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { actions() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 800.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f).padding(end = 16.dp)) { title() }
                controls(Modifier.weight(2f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title()
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordingCard(
    segment: EventGroups.Segment,
    recording: RecorderLibrary.Recording,
    cover: Bitmap?,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onSendToPhone: () -> Unit,
    onBrowserShare: () -> Unit,
    onExportToUsb: () -> Unit,
    usbExportEnabled: Boolean,
    onDelete: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    var menuOpen by remember(recording.id, selectionMode) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { if (selectionMode) onToggleSelection() else onPlay() },
    ) {
        Column {
            Box {
                RecordingCover(
                    cover = cover,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 9.dp, top = 9.dp, end = 62.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (recording.protectedCount > 0) {
                        StatusBadge(Utils.t("Protected", "已保护"), Color(0xFFFFB74D))
                    }
                    StatusBadge("${recording.speedMultiplier}×", Color(0xFF64B5F6))
                }
                StatusBadge(
                    text = recordingSourceLabel(segment.sidecar),
                    color = Color(0xFF81C784),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(9.dp),
                )
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    )
                } else Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                ) {
                    RecordingMenuButton(onClick = { menuOpen = true })
                    RecordingActionsMenu(
                        expanded = menuOpen,
                        protected = recording.protected,
                        onDismiss = { menuOpen = false },
                        onToggleProtect = onToggleProtect,
                        onSendToPhone = onSendToPhone,
                        onBrowserShare = onBrowserShare,
                        onExportToUsb = onExportToUsb,
                        usbExportEnabled = usbExportEnabled,
                        onDelete = onDelete,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    formatRecordingTimeRange(recording),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (recording.isTimeLapse) {
                        Utils.t(
                            "{0} captured → {1} video · {2} safety files · {3}", "实拍 {0} → 成片 {1} · {2} 个安全分段 · {3}", Utils.formatDuration(recording.realDurationMs), Utils.formatDuration(recording.encodedDurationMs), recording.files.size, formatBytes(recording.totalBytes))
                    } else {
                        Utils.t(
                            "{0} recorded · {1} safety files · {2}", "实录 {0} · {1} 个安全分段 · {2}", Utils.formatDuration(recording.realDurationMs), recording.files.size, formatBytes(recording.totalBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflinePlaybackDialog(
    title: String,
    recordedAtEpochMs: Long,
    storageDescription: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Utils.formatEpoch(recordedAtEpochMs))
                Text(
                    Utils.t(
                        "Reconnect {0} and refresh the recordings page to play this item.", "请重新连接 {0}，并刷新录像记录页面后播放。", storageDescription),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = onPrevious != null,
                        onClick = { onPrevious?.invoke() },
                    ) { Text(Utils.t("Previous", "上一个")) }
                    OutlinedButton(
                        enabled = onNext != null,
                        onClick = { onNext?.invoke() },
                    ) { Text(Utils.t("Next", "下一个")) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Utils.t("Close", "关闭")) }
        },
    )
}

@Composable
private fun OpenAvmSentryMediaCard(
    row: OpenAvmSentryRow, cover: Bitmap?, selectionMode: Boolean,
    onPlay: () -> Unit, onExport: () -> Unit, exportEnabled: Boolean,
) {
    val event = row.event
    val playable = !selectionMode && row.files.isNotEmpty() && event.state != "WRITING"
    Card(Modifier.fillMaxWidth().clickable(enabled = playable, onClick = onPlay)) {
        Column {
            Box {
                RecordingCover(cover = cover, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                StatusBadge(Utils.t("OpenAVM Sentry", "OpenAVM 哨兵"), Color(0xFFFFB74D),
                    modifier = Modifier.align(Alignment.TopStart).padding(9.dp))
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Utils.t("OpenAVM Sentry video", "OpenAVM 哨兵视频"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatRecordingTime(event.createdAtEpochMs))
                Text(when (event.state) {
                    "COMPLETE" -> Utils.t("Saved", "已完整保存")
                    "PARTIAL" -> Utils.t("Partially saved", "已保存部分录像")
                    "WRITING" -> Utils.t("Saving / awaiting recovery", "保存中 / 等待恢复")
                    else -> Utils.t("Save failed", "保存失败")
                } + Utils.t(" · {0} triggers · {1} segments", " · {0} 个触发点 · {1} 个分段", event.triggers.size, row.files.size), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPlay, enabled = playable) { Text(Utils.t("Play", "回看")) }
                    TextButton(onClick = onExport, enabled = playable && exportEnabled) { Text(Utils.t("Export to USB", "导出到 USB")) }
                }
            }
        }
    }
}

@Composable
private fun UsbOpenAvmMediaCard(
    recording: VehicleUsbRecording,
    cover: Bitmap?,
    onPlay: () -> Unit,
    onSendToPhone: () -> Unit,
    onBrowserShare: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggleSelection: () -> Unit,
) {
    val online = recording.availability == MediaAvailability.ONLINE
    var menuOpen by remember(recording.stableKey, selectionMode, online) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = if (selectionMode) selectionEnabled else online) {
                if (selectionMode) onToggleSelection() else onPlay()
            },
    ) {
        Column {
            Box {
                RecordingCover(
                    cover = cover,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Column(
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 9.dp, top = 9.dp, end = 62.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusBadge("USB", Color(0xFF64B5F6))
                    if (recording.protectedSegments > 0) StatusBadge(Utils.t("Protected", "已保护"), Color(0xFFFFB74D))
                    StatusBadge(categoryLabel(recording.category), Color(0xFF81C784))
                }
                if (!online) {
                    StatusBadge(
                        text = Utils.t("USB offline", "U 盘离线"),
                        color = Color(0xFFFFB74D),
                        modifier = Modifier.align(Alignment.BottomStart).padding(9.dp),
                    )
                }
                if (selectionMode && selectionEnabled) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    )
                } else if (!selectionMode) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                        RecordingMenuButton(onClick = { menuOpen = true })
                        UsbRecordingActionsMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            mediaAvailable = online && recording.files.isNotEmpty(),
                            onPlay = onPlay,
                            onSendToPhone = onSendToPhone,
                            onBrowserShare = onBrowserShare,
                            onDelete = onDelete,
                            deleteEnabled = deleteEnabled,
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    formatRecordingTime(recording.startedAtEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t(
                        "{0} segments · {1} · {2}", "{0} 个分段 · {1} · {2}", recording.files.size, formatBytes(recording.totalBytes), recording.storageDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsbSentryMediaCard(
    recording: VehicleSentryRecording,
    cover: Bitmap?,
    onPlay: () -> Unit,
    onSendToPhone: () -> Unit,
    onBrowserShare: () -> Unit,
    selectionMode: Boolean,
) {
    val online = recording.availability == MediaAvailability.ONLINE
    var menuOpen by remember(recording.stableKey, selectionMode, online) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = online && !selectionMode, onClick = onPlay),
    ) {
        Column {
            Box {
                RecordingCover(
                    cover = cover,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                StatusBadge(
                    text = Utils.t("Factory Sentry", "原车哨兵"),
                    color = Color(0xFFFFB74D),
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 9.dp, top = 9.dp, end = 62.dp),
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusBadge("2560×2560", Color(0xFF64B5F6))
                    StatusBadge(
                        text = if (online) recording.storageDescription else Utils.t("USB offline", "U 盘离线"),
                        color = if (online) Color(0xFF81C784) else Color(0xFFFFB74D),
                    )
                }
                if (selectionMode) {
                    StatusBadge(
                        text = Utils.t("Read-only", "只读"),
                        color = Color(0xFFFFB74D),
                        modifier = Modifier.align(Alignment.TopEnd).padding(9.dp),
                    )
                } else {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                        RecordingMenuButton(onClick = { menuOpen = true })
                        UsbRecordingActionsMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            mediaAvailable = online && recording.videoFile != null,
                            onPlay = onPlay,
                            onSendToPhone = onSendToPhone,
                            onBrowserShare = onBrowserShare,
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    Utils.t("Factory Sentry video · ", "原车哨兵视频 · ") + formatSentryTime(recording.startedAtEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t(
                        "{0} · Factory SentryMode · {1}", "{0} · 原车哨兵事件 · {1}", recording.eventId, formatBytes(recording.totalBytes)) + if (!recording.complete) Utils.t(" · incomplete metadata", " · 元数据不完整") else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SavedEventCard(
    group: EventGroups.EventGroup,
    cover: Bitmap?,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onSendToPhone: () -> Unit,
    onExportToUsb: () -> Unit,
    usbExportEnabled: Boolean,
    onDelete: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    var menuOpen by remember(group.segments.first().file.name, selectionMode) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { if (selectionMode) onToggleSelection() else onPlay() },
    ) {
        Column {
            Box {
                RecordingCover(
                    cover = cover,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 9.dp, top = 9.dp, end = 62.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusBadge(Utils.t("Saved", "已保存"), Color(0xFFFFB74D))
                }
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    )
                } else Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                ) {
                    RecordingMenuButton(onClick = { menuOpen = true })
                    RecordingActionsMenu(
                        expanded = menuOpen,
                        protected = group.protectedCount > 0,
                        onDismiss = { menuOpen = false },
                        onToggleProtect = onToggleProtect,
                        onSendToPhone = onSendToPhone,
                        onExportToUsb = onExportToUsb,
                        usbExportEnabled = usbExportEnabled,
                        onDelete = onDelete,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    Utils.t("Event {0}", "事件 {0}", formatRecordingTime(group.startedAtEpochMs)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t(
                        "{0}  ·  {1} segments  ·  {2}", "{0}  ·  {1} 段  ·  {2}", Utils.formatDuration(group.durationMs), group.segments.size, recordingSourceLabel(group.segments.first().sidecar)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordingMenuButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
            .background(Color(0xFF171D25), RoundedCornerShape(999.dp)),
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = Utils.t("More actions", "更多操作"),
            tint = Color.White,
        )
    }
}

@Composable
private fun UsbRecordingActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    mediaAvailable: Boolean,
    onPlay: () -> Unit,
    onSendToPhone: () -> Unit,
    onBrowserShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = false,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(Utils.t("Play", "回看")) },
            onClick = { onDismiss(); onPlay() },
            enabled = mediaAvailable,
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Send to phone", "发送到手机")) },
            onClick = { onDismiss(); onSendToPhone() },
            enabled = mediaAvailable && GalleryTransferActionPolicy.menuEnabled,
        )
        if (BuildConfig.BROWSER_DOWNLOAD_ENABLED && onBrowserShare != null) DropdownMenuItem(
            text = { Text(Utils.t("Browser download", "浏览器下载")) },
            onClick = { onDismiss(); onBrowserShare() },
            enabled = mediaAvailable,
        )
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text(Utils.t("Delete", "删除")) },
                onClick = { onDismiss(); onDelete() },
                enabled = deleteEnabled,
            )
        }
    }
}

@Composable
private fun RecordingActionsMenu(
    expanded: Boolean,
    protected: Boolean,
    onDismiss: () -> Unit,
    onToggleProtect: () -> Unit,
    onSendToPhone: () -> Unit,
    onBrowserShare: (() -> Unit)? = null,
    onExportToUsb: () -> Unit,
    usbExportEnabled: Boolean,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (protected) Utils.t("Remove protection", "取消保护") else Utils.t("Protect recording", "保护录像")) },
            onClick = { onDismiss(); onToggleProtect() },
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Send to phone", "发送到手机")) },
            onClick = { onDismiss(); onSendToPhone() },
            enabled = GalleryTransferActionPolicy.menuEnabled,
        )
        if (BuildConfig.BROWSER_DOWNLOAD_ENABLED && onBrowserShare != null) DropdownMenuItem(
            text = { Text(Utils.t("Browser download", "浏览器下载")) },
            onClick = { onDismiss(); onBrowserShare() },
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Export to USB (experimental)", "导出到 USB（实验性）")) },
            onClick = { onDismiss(); onExportToUsb() },
            enabled = usbExportEnabled,
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Delete", "删除")) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun UsbExportTaskCard(
    task: UsbExportTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCopyReport: () -> Unit,
    showDiagnostics: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(Utils.t("USB export · {0}", "USB 导出 · {0}", usbStateLabel(task.state)), fontWeight = FontWeight.SemiBold)
            Text(
                "${task.target.description} · ${task.target.storageUuid} · ${task.relativePath}",
                style = MaterialTheme.typography.bodySmall,
            )
            val fraction = if (task.totalBytes > 0L) {
                (task.progressBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${formatBytes(task.progressBytes)} / ${formatBytes(task.totalBytes)}" +
                    (task.message?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.errorCode == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!task.terminal() && task.state !in setOf(UsbExportState.CANCEL_REQUESTED, UsbExportState.CLEANING_UP)) {
                    TextButton(onClick = onCancel) { Text(Utils.t("Cancel", "取消")) }
                }
                if (task.state in setOf(UsbExportState.FAILED, UsbExportState.FAILED_RECOVERABLE, UsbExportState.WAITING_FOR_TARGET)) {
                    TextButton(onClick = onRetry) { Text(Utils.t("Retry", "重试")) }
                }
                if (task.statusCardDismissible()) {
                    TextButton(onClick = onDismiss) { Text(Utils.t("Dismiss", "关闭")) }
                }
                if (showDiagnostics) {
                    TextButton(onClick = onCopyReport) {
                        Text(Utils.t("Copy diagnostic report", "复制诊断报告"))
                    }
                }
            }
        }
    }
}

private fun usbStateLabel(state: UsbExportState): String = when (state) {
    UsbExportState.QUEUED -> Utils.t("Queued", "等待中")
    UsbExportState.WAITING_FOR_TARGET -> Utils.t("Waiting for the same USB", "等待同一 U 盘")
    UsbExportState.PREPARING_SOURCE -> Utils.t("Preparing", "准备源文件")
    UsbExportState.COPYING -> Utils.t("Copying", "复制中")
    UsbExportState.VERIFYING -> Utils.t("Verifying", "校验中")
    UsbExportState.PUBLISHING -> Utils.t("Publishing", "发布中")
    UsbExportState.CLEANING_UP -> Utils.t("Cleaning", "清理中")
    UsbExportState.CANCEL_REQUESTED -> Utils.t("Cancelling", "正在取消")
    UsbExportState.COMPLETED -> Utils.t("Completed", "已完成")
    UsbExportState.ALREADY_EXPORTED -> Utils.t("Already exported", "已经导出")
    UsbExportState.FAILED_RECOVERABLE -> Utils.t("Needs recovery", "可恢复失败")
    UsbExportState.FAILED -> Utils.t("Failed", "失败")
    UsbExportState.CANCELLED -> Utils.t("Cancelled", "已取消")
}

@Composable
private fun RecordingCover(cover: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151515)),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null && !cover.isRecycled) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = Utils.t("Recording thumbnail", "录像封面"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(Utils.t("Generating thumbnail", "生成封面中"), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(
                color.copy(alpha = 0.18f).compositeOver(Color(0xFF121820)),
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun recordingEpoch(recording: RecorderLibrary.Recording): Long =
    recording.startedAtEpochMs

private fun recordingDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))

private fun recordingSourceLabel(sidecar: com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar): String =
    when (RecordingPresentationPolicy.resolve(sidecar).sourceRole) {
        com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole.SURROUND -> "360°"
        com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole.CABIN -> Utils.t("Cabin", "车内")
        com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole.IR -> Utils.t("Infrared", "红外")
    }

private fun formatRecordingTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", AppLanguage.locale).format(Date(epochMs))

private fun formatSentryTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", AppLanguage.locale).format(Date(epochMs))

private fun formatRecordingTimeRange(recording: RecorderLibrary.Recording): String =
    "${formatRecordingTime(recording.startedAtEpochMs)}–${formatRecordingTime(recording.stoppedAtEpochMs)}"

private fun formatDateHeader(value: String): String = runCatching {
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) ?: return value
    SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(AppLanguage.locale, "MMMEd"),
        AppLanguage.locale,
    ).format(source)
}.getOrDefault(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} KB"
}

private fun categoryLabel(category: VehicleMediaCategory): String = when (category) {
    VehicleMediaCategory.ALL -> Utils.t("All", "全部")
    VehicleMediaCategory.NORMAL -> Utils.t("Normal", "普通视频")
    VehicleMediaCategory.TIME_LAPSE -> Utils.t("Time-lapse", "延时视频")
    VehicleMediaCategory.EVENTS -> Utils.t("Incidents", "紧急事件")
    VehicleMediaCategory.OPENAVM_SENTRY -> Utils.t("OpenAVM Sentry", "OpenAVM 哨兵")
    VehicleMediaCategory.SENTRY -> Utils.t("Factory Sentry", "原车哨兵")
}

private fun decodeSampledCover(file: File): Bitmap? {
    if (!file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}

private fun usbDeleteMessage(error: String): String = when (error.substringAfterLast(':')) {
    "RECORDING_ACTIVE" -> Utils.t("Recording or saving is in progress. Stop recording and wait for saving to finish before deleting.", "正在录像或保存文件，请停止录像并等待保存完成后再删除。")
    "CAMERA_WORK_BUSY" -> Utils.t("Camera work is still finishing. Wait for it to finish before deleting.", "相机任务尚未结束，请等待收尾完成后再删除。")
    "USB_SEGMENT_PLAYING", "USB_EXPORT_PLAYING" -> Utils.t("Close playback before deleting this recording.", "请先关闭这段录像的播放，再删除。")
    "USB_SEGMENT_TRANSFER_ACTIVE" -> Utils.t("This recording is being transferred. Wait for the transfer to finish.", "这段录像正在传输，请等待传输完成。")
    "TARGET_NOT_MOUNTED" -> Utils.t("The selected USB is no longer connected.", "所选 U 盘已断开。")
    else -> error
}

private fun deleteFailureText(reason: String?): String = when (reason) {
    RecorderLibrary.DELETE_BOOKMARKED -> Utils.t("Recording is protected and was not deleted", "录像已保护，未删除")
    RecorderLibrary.DELETE_UPLOAD_PINNED -> Utils.t("Recording is temporarily locked and was not deleted", "录像暂时锁定，未删除")
    else -> Utils.t("Unable to delete recording", "录像删除失败")
}
