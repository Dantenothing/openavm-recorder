package com.dante.zeekrbridge.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.core.CarRecording
import com.dante.zeekrbridge.core.IndexedLayoutKind
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedRecordingEvent
import com.dante.zeekrbridge.core.IndexedRecordingMode
import com.dante.zeekrbridge.core.IndexedRecordingSession
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.core.MediaIndexStore
import com.dante.zeekrbridge.core.MediaThumbnailCache
import com.dante.zeekrbridge.core.LibraryBackAction
import com.dante.zeekrbridge.core.MediaDeletePlan
import com.dante.zeekrbridge.core.MediaDeletePlanner
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.resolveLibraryBackAction
import com.dante.zeekrbridge.core.ServerLog
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibrarySection(val en: String, val zh: String) {
    ALL("All", "全部"),
    EVENTS("Events", "事件"),
    ON_VEHICLE("On vehicle", "车机录像"),
    PHONE("Phone files", "手机文件"),
}

private data class MediaDetail(
    val id: String,
    val isEvent: Boolean,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val sourceRole: IndexedSourceRole,
    val recordingMode: IndexedRecordingMode,
    val timeLapseMultiplier: Int,
    val realDurationMs: Long,
    val segments: List<IndexedMediaSegment>,
) {
    val cover: IndexedMediaSegment get() = segments.first()
}

private data class SessionPlaybackRequest(
    val detail: MediaDetail,
    val initialIndex: Int,
)

private data class BatchDeleteRequest(
    val logicalCount: Int,
    val isEvent: Boolean,
    val plan: MediaDeletePlan,
)

@Composable
fun SessionMediaLibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val received by ReceivedStore.files.collectAsState()
    val index by MediaIndexStore.snapshot.collectAsState()
    val scanning by MediaIndexStore.scanning.collectAsState()
    val catalogOnline by CarCatalogStore.online.collectAsState()
    val carItems by CarCatalogStore.items.collectAsState()
    val uploads by CarCatalogStore.uploads.collectAsState()
    val lastMessage by CarCatalogStore.lastMessage.collectAsState()

    var sectionName by rememberSaveable { mutableStateOf(LibrarySection.ALL.name) }
    val requestedSection = runCatching { LibrarySection.valueOf(sectionName) }.getOrDefault(LibrarySection.ALL)
    val section = if (requestedSection == LibrarySection.ON_VEHICLE) LibrarySection.ALL else requestedSection
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var exportDetail by remember { mutableStateOf<MediaDetail?>(null) }
    var playSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var sessionPlayback by remember { mutableStateOf<SessionPlaybackRequest?>(null) }
    var deleteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var batchDelete by remember { mutableStateOf<BatchDeleteRequest?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var noteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var noteText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    val sessionGridState = rememberLazyGridState()
    val eventGridState = rememberLazyGridState()

    var mediaPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var phoneVideos by remember { mutableStateOf<List<File>>(emptyList()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        mediaPermissionGranted = granted
        if (granted) scope.launch { phoneVideos = queryPhoneVideos(context) }
    }

    LaunchedEffect(received) { MediaIndexStore.refresh(received) }
    LaunchedEffect(catalogOnline) {
        if (catalogOnline && carItems.isEmpty()) BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
    }
    LaunchedEffect(section, mediaPermissionGranted) {
        if (section == LibrarySection.PHONE && mediaPermissionGranted) phoneVideos = queryPhoneVideos(context)
    }
    LaunchedEffect(section) { selectedIds = emptySet() }
    LaunchedEffect(index, section) {
        val validIds = when (section) {
            LibrarySection.ALL -> index.sessions.mapTo(hashSetOf()) { it.id }
            LibrarySection.EVENTS -> index.events.mapTo(hashSetOf()) { it.id }
            else -> emptySet()
        }
        selectedIds = selectedIds.intersect(validIds)
    }

    val playerOpen = sessionPlayback != null || playSegment != null
    BackHandler(enabled = playerOpen || detail != null || selectedIds.isNotEmpty()) {
        when (resolveLibraryBackAction(playerOpen, detail != null, selectedIds.size)) {
            LibraryBackAction.CLOSE_PLAYER -> {
                sessionPlayback = null
                playSegment = null
            }
            LibraryBackAction.CLOSE_DETAIL -> detail = null
            LibraryBackAction.CLEAR_SELECTION -> selectedIds = emptySet()
            LibraryBackAction.EXIT_LIBRARY -> Unit
        }
    }

    detail?.let { selected ->
        MediaDetailScreen(
            detail = selected,
            onBack = { detail = null },
            onPlay = { index -> sessionPlayback = SessionPlaybackRequest(selected, index) },
            onExport = { exportDetail = selected },
            onShare = { shareMediaFile(context, it.file) },
            onSave = { segment ->
                scope.launch {
                    val ok = saveMediaToMovies(context, segment.file)
                    statusText = if (ok) t("Saved to Movies", "已保存到系统相册") else t("Save failed", "保存失败")
                }
            },
            onNote = {
                noteSegment = it
                noteText = loadMediaNote(context, it.fileName)
            },
            onDelete = { deleteSegment = it },
        )
    } ?: Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (selectedIds.isNotEmpty()) {
            val selectableIds = when (section) {
                LibrarySection.ALL -> index.sessions.map { it.id }
                LibrarySection.EVENTS -> index.events.map { it.id }
                else -> emptyList()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("${selectedIds.size} selected", "已选择 ${selectedIds.size} 项"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedIds = selectableIds.toSet() }) { Text(t("Select all", "全选")) }
                TextButton(onClick = {
                    val selectedGroups = when (section) {
                        LibrarySection.ALL -> index.sessions.filter { it.id in selectedIds }.map { it.segments }
                        LibrarySection.EVENTS -> index.events.filter { it.id in selectedIds }.map { it.segments }
                        else -> emptyList()
                    }
                    batchDelete = BatchDeleteRequest(
                        logicalCount = selectedGroups.size,
                        isEvent = section == LibrarySection.EVENTS,
                        plan = MediaDeletePlanner.plan(selectedGroups),
                    )
                }) { Text(t("Trash", "移入回收站")) }
                TextButton(onClick = { selectedIds = emptySet() }) { Text(t("Cancel", "取消")) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Library", "媒体库"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        t(
                            "${index.sessions.size} sessions · ${index.events.size} events",
                            "${index.sessions.size} 次录像 · ${index.events.size} 个事件",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        MediaExportQueueCard()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VISIBLE_LIBRARY_SECTIONS.forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { sectionName = item.name },
                    label = { Text(t(item.en, item.zh)) },
                )
            }
        }
        if (lastMessage.isNotBlank() && section == LibrarySection.ON_VEHICLE) {
            Text(lastMessage, style = MaterialTheme.typography.bodySmall)
        }
        if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.bodySmall)

        Box(Modifier.weight(1f)) {
            when (section) {
                LibrarySection.ALL -> SessionGrid(
                    sessions = index.sessions,
                    state = sessionGridState,
                    emptyText = t("No OpenAVM recordings on this phone", "手机中还没有 OpenAVM 录像"),
                    selectedIds = selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onOpen = { detail = it.toDetail() },
                    onToggle = { id ->
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        }
                    },
                    onLongPress = { selectedIds = selectedIds + it },
                )
                LibrarySection.EVENTS -> EventGrid(
                    events = index.events,
                    state = eventGridState,
                    emptyText = t("No saved incidents", "暂无保存的事件录像"),
                    selectedIds = selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onOpen = { detail = it.toDetail() },
                    onToggle = { id ->
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        }
                    },
                    onLongPress = { selectedIds = selectedIds + it },
                )
                LibrarySection.ON_VEHICLE -> OnVehicleList(
                    online = catalogOnline,
                    items = carItems,
                    receivedNames = index.segments.mapTo(hashSetOf()) { it.fileName },
                    uploads = uploads,
                    onRefresh = { BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap()) },
                    onDownload = { item ->
                        BridgeServer.sendToCars(WsType.REQUEST_UPLOAD, mapOf("fileName" to item.fileName))
                        statusText = t("Download requested", "已请求下载")
                    },
                )
                LibrarySection.PHONE -> PhoneFilesList(
                    permissionGranted = mediaPermissionGranted,
                    files = phoneVideos,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                            else Manifest.permission.READ_EXTERNAL_STORAGE,
                        )
                    },
                    onPlay = { file ->
                        playSegment = externalSegment(file)
                    },
                )
            }
        }
    }

    exportDetail?.let { selected ->
        MediaExportDialog(segments = selected.segments, onDismiss = { exportDetail = null })
    }

    playSegment?.let { segment ->
        MediaPlaybackDialog(
            file = segment.file,
            laneLabels = playbackLabels(segment),
            laneOrder = segment.playbackLaneOrder,
            onDismiss = { playSegment = null },
        )
    }
    sessionPlayback?.let { request ->
        MediaSessionPlaybackDialog(
            segments = request.detail.segments,
            initialIndex = request.initialIndex,
            isEvent = request.detail.isEvent,
            recordingMode = request.detail.recordingMode,
            timeLapseMultiplier = request.detail.timeLapseMultiplier,
            realDurationMs = request.detail.realDurationMs,
            onDismiss = { sessionPlayback = null },
        )
    }
    deleteSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { deleteSegment = null },
            title = { Text(t("Delete this segment?", "删除这个分段？")) },
            text = { Text(t("The MP4 and its metadata will move to OpenAVM trash.", "MP4 和对应元数据将移入 OpenAVM 回收站。")) },
            confirmButton = {
                TextButton(onClick = {
                    ReceivedStore.moveToTrash(segment.file)
                    MediaIndexStore.invalidate()
                    deleteSegment = null
                    detail = null
                }) { Text(t("Delete", "删除")) }
            },
            dismissButton = { TextButton(onClick = { deleteSegment = null }) { Text(t("Cancel", "取消")) } },
        )
    }
    batchDelete?.let { request ->
        AlertDialog(
            onDismissRequest = { batchDelete = null },
            title = { Text(t("Move selected recordings to trash?", "将所选录像移入回收站？")) },
            text = {
                Text(
                    if (request.isEvent) {
                        t(
                            "${request.logicalCount} events reference ${request.plan.physicalVideoCount} physical videos. Moving them to trash can also remove those segments from their Sessions.",
                            "${request.logicalCount} 个事件引用 ${request.plan.physicalVideoCount} 个分段文件。移入回收站后，这些分段也会从对应录像片段中消失。",
                        )
                    } else {
                        t(
                            "${request.logicalCount} Sessions contain ${request.plan.physicalVideoCount} unique physical videos. MP4 files and metadata will move to OpenAVM trash.",
                            "${request.logicalCount} 个录像片段共包含 ${request.plan.physicalVideoCount} 个分段文件。MP4 和元数据将移入 OpenAVM 回收站。",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val files = request.plan.segments.map { it.file }
                    scope.launch {
                        val moved = withContext(Dispatchers.IO) { ReceivedStore.moveToTrash(files) }
                        MediaIndexStore.invalidate()
                        selectedIds = emptySet()
                        batchDelete = null
                        statusText = t(
                            "Moved $moved videos to OpenAVM trash",
                            "已将 $moved 个视频移入 OpenAVM 回收站",
                        )
                    }
                }) { Text(t("Move to trash", "移入回收站")) }
            },
            dismissButton = {
                TextButton(onClick = { batchDelete = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }
    noteSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { noteSegment = null },
            title = { Text(t("Segment note", "分段备注")) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(t("Note", "备注")) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveMediaNote(context, segment.fileName, noteText)
                    noteSegment = null
                }) { Text(t("Save", "保存")) }
            },
            dismissButton = { TextButton(onClick = { noteSegment = null }) { Text(t("Cancel", "取消")) } },
        )
    }
}

@Composable
private fun SessionGrid(
    sessions: List<IndexedRecordingSession>,
    state: LazyGridState,
    emptyText: String,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    onOpen: (IndexedRecordingSession) -> Unit,
    onToggle: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (sessions.isEmpty()) return EmptyLibrary(emptyText)
    val groups = sessions.groupBy { dayLabel(it.startedAtEpochMs) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (day, values) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(day, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            gridItems(values, key = { it.id }) { session ->
                LibraryCard(
                    cover = session.cover,
                    title = when {
                        session.recordingMode == IndexedRecordingMode.TIME_LAPSE ->
                            t("Time-lapse · ${session.timeLapseMultiplier}×", "延时摄影 · ${session.timeLapseMultiplier}×")
                        session.hasIncident -> t("Recording · incident saved", "普通录像 · 已保存事件")
                        else -> t("Recording", "普通录像")
                    },
                    startedAt = session.startedAtEpochMs,
                    durationMs = session.durationMs,
                    sizeBytes = session.sizeBytes,
                    segmentCount = session.segments.size,
                    source = session.sourceRole,
                    recordingMode = session.recordingMode,
                    realDurationMs = session.realDurationMs,
                    selected = session.id in selectedIds,
                    onClick = {
                        if (selectionMode) onToggle(session.id) else onOpen(session)
                    },
                    onLongClick = { onLongPress(session.id) },
                )
            }
        }
    }
}

@Composable
private fun EventGrid(
    events: List<IndexedRecordingEvent>,
    state: LazyGridState,
    emptyText: String,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    onOpen: (IndexedRecordingEvent) -> Unit,
    onToggle: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (events.isEmpty()) return EmptyLibrary(emptyText)
    val groups = events.groupBy { dayLabel(it.startedAtEpochMs) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (day, values) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(day, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            gridItems(values, key = { it.id }) { event ->
                LibraryCard(
                    cover = event.cover,
                    title = t("★ Incident", "★ 事件录像"),
                    startedAt = event.startedAtEpochMs,
                    durationMs = event.durationMs,
                    sizeBytes = event.sizeBytes,
                    segmentCount = event.segments.size,
                    source = event.sourceRole,
                    recordingMode = IndexedRecordingMode.NORMAL,
                    realDurationMs = event.durationMs,
                    selected = event.id in selectedIds,
                    onClick = {
                        if (selectionMode) onToggle(event.id) else onOpen(event)
                    },
                    onLongClick = { onLongPress(event.id) },
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(
    cover: IndexedMediaSegment,
    title: String,
    startedAt: Long,
    durationMs: Long,
    sizeBytes: Long,
    segmentCount: Int,
    source: IndexedSourceRole,
    recordingMode: IndexedRecordingMode,
    realDurationMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
    ) {
        Column {
            Box {
                MediaCover(cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (selected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatClock(startedAt), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                        t(
                            "${sourceLabel(source)} · captured ${formatDuration(realDurationMs)} → video ${formatDuration(durationMs)} · $segmentCount safety files",
                            "${sourceLabel(source)} · 拍摄 ${formatDuration(realDurationMs)} → 成片 ${formatDuration(durationMs)} · $segmentCount 个安全文件",
                        )
                    } else {
                        "${sourceLabel(source)} · ${formatDuration(durationMs)} · ${t("$segmentCount segments", "$segmentCount 个分段")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(formatMediaBytes(sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MediaCover(segment: IndexedMediaSegment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(segment.id, segment.sizeBytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(segment.id, segment.sizeBytes) {
        bitmap = MediaThumbnailCache.loadOrCreate(context, segment)
    }
    Box(
        modifier.clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.68f),
        ) {
            Text(sourceLabel(segment.sourceRole), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
        if (segment.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
            ) {
                Text(
                    "${segment.timeLapseMultiplier}×",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaDetailScreen(
    detail: MediaDetail,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onExport: () -> Unit,
    onShare: (IndexedMediaSegment) -> Unit,
    onSave: (IndexedMediaSegment) -> Unit,
    onNote: (IndexedMediaSegment) -> Unit,
    onDelete: (IndexedMediaSegment) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
            Text(
                if (detail.isEvent) t("Incident", "事件录像") else t("Recording clip", "录像片段"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        MediaCover(detail.cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(formatDateTime(detail.startedAtEpochMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (detail.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                        t(
                            "${sourceLabel(detail.sourceRole)} · Time-lapse ${detail.timeLapseMultiplier}×",
                            "${sourceLabel(detail.sourceRole)} · 延时摄影 ${detail.timeLapseMultiplier}×",
                        )
                    } else {
                        "${sourceLabel(detail.sourceRole)} · ${formatDuration(detail.durationMs)}"
                    },
                )
                if (detail.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                    Text(
                        t(
                            "Captured ${formatDuration(detail.realDurationMs)} → video ${formatDuration(detail.durationMs)}",
                            "现实拍摄 ${formatDuration(detail.realDurationMs)} → 成片 ${formatDuration(detail.durationMs)}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    t(
                        "${detail.segments.size} physical segments · ${formatMediaBytes(detail.sizeBytes)}",
                        "${detail.segments.size} 个分段文件 · ${formatMediaBytes(detail.sizeBytes)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = { onPlay(0) }, modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        if (detail.isEvent) t("Play incident continuously", "连续播放事件")
                        else t(
                            "Play ${formatPlaybackRange(detail.segments)}",
                            "播放 ${formatPlaybackRange(detail.segments)}",
                        ),
                    )
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.padding(top = 8.dp)) {
                    Text(t("Export recording clip", "导出录像片段"))
                }
            }
        }
        MediaExportQueueCard(Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(16.dp))
        Text(t("Segment files", "分段文件"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail.segments.forEachIndexed { index, segment ->
            SegmentCard(
                index = index,
                segment = segment,
                onPlay = { onPlay(index) },
                onShare = { onShare(segment) },
                onSave = { onSave(segment) },
                onNote = { onNote(segment) },
                onDelete = { onDelete(segment) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: IndexedMediaSegment,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(t("Segment ${index + 1}", "分段 ${index + 1}"), fontWeight = FontWeight.SemiBold)
            Text(
                if (segment.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                    t(
                        "${formatClock(segment.startedAtEpochMs)} · captured ${formatDuration(segment.realDurationMs ?: 0L)} → video ${formatDuration(segment.durationMs)} · ${formatMediaBytes(segment.sizeBytes)}",
                        "${formatClock(segment.startedAtEpochMs)} · 拍摄 ${formatDuration(segment.realDurationMs ?: 0L)} → 成片 ${formatDuration(segment.durationMs)} · ${formatMediaBytes(segment.sizeBytes)}",
                    )
                } else {
                    "${formatClock(segment.startedAtEpochMs)} · ${formatDuration(segment.durationMs)} · ${formatMediaBytes(segment.sizeBytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(segment.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onPlay) { Text(t("Play", "播放")) }
                OutlinedButton(onClick = onSave) { Text(t("Save", "保存")) }
                OutlinedButton(onClick = onShare) { Text(t("Share", "分享")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onNote) { Text(t("Note", "备注")) }
                TextButton(onClick = onDelete) { Text(t("Delete segment", "删除分段")) }
            }
        }
    }
}

@Composable
private fun OnVehicleList(
    online: Boolean,
    items: List<CarRecording>,
    receivedNames: Set<String>,
    uploads: Map<String, String>,
    onRefresh: () -> Unit,
    onDownload: (CarRecording) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (online) t("Vehicle connected", "车机已连接") else t("Vehicle offline", "车机离线"),
                modifier = Modifier.weight(1f),
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRefresh, enabled = online) { Text(t("Refresh", "刷新")) }
        }
        if (items.isEmpty()) return EmptyLibrary(t("No vehicle catalog available", "暂无车机录像目录"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listItems(items, key = { it.fileName }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDateTime(item.startedAtEpochMs ?: 0L), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${remoteSourceLabel(item)} · ${formatDuration(item.durationMs ?: 0L)} · ${formatMediaBytes(item.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(item.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            item.fileName in receivedNames -> Text(t("Saved", "已保存"), color = MaterialTheme.colorScheme.primary)
                            uploads[item.fileName] == "QUEUED" -> Text(t("Queued", "等待下载"))
                            else -> Button(onClick = { onDownload(item) }, enabled = online) { Text(t("Download", "下载")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneFilesList(
    permissionGranted: Boolean,
    files: List<File>,
    onRequestPermission: () -> Unit,
    onPlay: (File) -> Unit,
) {
    if (!permissionGranted) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("Allow video access to view other phone files.", "允许视频访问后可查看手机中的其他视频。"))
            Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 10.dp)) { Text(t("Allow access", "授予权限")) }
        }
        return
    }
    if (files.isEmpty()) return EmptyLibrary(t("No phone videos found", "没有找到手机视频"))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listItems(files, key = { it.absolutePath }) { file ->
            Card(onClick = { onPlay(file) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatMediaBytes(file.length()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun IndexedRecordingSession.toDetail() = MediaDetail(
    id = id,
    isEvent = false,
    startedAtEpochMs = startedAtEpochMs,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    sourceRole = sourceRole,
    recordingMode = recordingMode,
    timeLapseMultiplier = timeLapseMultiplier,
    realDurationMs = realDurationMs,
    segments = segments,
)

private fun IndexedRecordingEvent.toDetail() = MediaDetail(
    id = id,
    isEvent = true,
    startedAtEpochMs = startedAtEpochMs,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    sourceRole = sourceRole,
    recordingMode = IndexedRecordingMode.NORMAL,
    timeLapseMultiplier = 1,
    realDurationMs = durationMs,
    segments = segments,
)

private fun externalSegment(file: File) = IndexedMediaSegment(
    id = file.absolutePath,
    filePath = file.absolutePath,
    fileName = file.name,
    sidecarPath = null,
    sizeBytes = file.length(),
    startedAtEpochMs = file.lastModified(),
    stoppedAtEpochMs = null,
    durationMs = 0,
    segmentNumber = null,
    recordingSessionId = null,
    eventId = null,
    eventRole = null,
    protected = false,
    sourceRole = IndexedSourceRole.UNKNOWN,
    layoutKind = IndexedLayoutKind.UNKNOWN,
    cameraId = null,
    lanes = emptyList(),
    originalWidth = null,
    originalHeight = null,
)

private fun sourceLabel(role: IndexedSourceRole): String = when (role) {
    IndexedSourceRole.SURROUND -> "360°"
    IndexedSourceRole.CABIN -> t("Cabin", "后座")
    IndexedSourceRole.IR -> t("IR", "红外")
    IndexedSourceRole.UNKNOWN -> t("Unknown source", "未知来源")
}

private fun remoteSourceLabel(item: CarRecording): String = when {
    item.cameraId == "2" || item.layoutType.contains("4", ignoreCase = true) -> "360°"
    item.cameraId == "1" -> t("Cabin", "后座")
    item.cameraId == "0" -> t("IR", "红外")
    else -> t("Recording", "录像")
}

private fun playbackLabels(segment: IndexedMediaSegment): List<String> {
    val frozen = segment.lanes.sortedBy { it.displayOrder }.map { it.label }
    if (frozen.size == 4) return frozen
    return if (segment.layoutKind == IndexedLayoutKind.FOUR_LANE_V1) {
        listOf(t("Front", "前"), t("Rear", "后"), t("Left", "左"), t("Right", "右"))
    } else {
        emptyList()
    }
}

private fun dayLabel(epochMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(epochMs))
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86_400_000L))
    return when (day) {
        today -> t("Today", "今天")
        yesterday -> t("Yesterday", "昨天")
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private fun formatClock(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

private fun formatDateTime(epochMs: Long): String = if (epochMs <= 0L) "—" else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun formatMediaBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private val VISIBLE_LIBRARY_SECTIONS = listOf(
    LibrarySection.ALL,
    LibrarySection.EVENTS,
    LibrarySection.PHONE,
)

private suspend fun queryPhoneVideos(context: Context): List<File> = withContext(Dispatchers.IO) {
    val results = mutableListOf<File>()
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media.DATA),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            while (cursor.moveToNext()) {
                cursor.getString(dataIndex)?.let(::File)?.takeIf(File::isFile)?.let(results::add)
            }
        }
    }.onFailure { ServerLog.log("MEDIA_QUERY_FAILED ${it.message}") }
    results.distinctBy { it.absolutePath }.take(200)
}

private fun shareMediaFile(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        t("Share recording", "分享录像"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(chooser)
    true
}.getOrElse {
    ServerLog.log("SHARE_FAILED ${it.message}")
    false
}

private suspend fun saveMediaToMovies(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext shareMediaFile(context, file)
    var uri: Uri? = null
    try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OpenAVM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: return@withContext false
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        true
    } catch (_: Throwable) {
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        false
    }
}

private fun loadMediaNote(context: Context, name: String): String =
    context.getSharedPreferences("media_notes", Context.MODE_PRIVATE).getString(name, "") ?: ""

private fun saveMediaNote(context: Context, name: String, note: String) {
    context.getSharedPreferences("media_notes", Context.MODE_PRIVATE).edit().putString(name, note).apply()
}
