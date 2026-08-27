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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import com.dante.zeekrbridge.core.IndexedRecordingSession
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.core.MediaIndexStore
import com.dante.zeekrbridge.core.MediaThumbnailCache
import com.dante.zeekrbridge.core.ReceivedStore
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
    val segments: List<IndexedMediaSegment>,
) {
    val cover: IndexedMediaSegment get() = segments.first()
}

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

    var section by remember { mutableStateOf(LibrarySection.ALL) }
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var playSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var deleteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var noteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var noteText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }

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

    detail?.let { selected ->
        MediaDetailScreen(
            detail = selected,
            onBack = { detail = null },
            onPlay = { playSegment = it },
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibrarySection.entries.forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { section = item },
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
                    emptyText = t("No OpenAVM recordings on this phone", "手机中还没有 OpenAVM 录像"),
                    onOpen = { detail = it.toDetail() },
                )
                LibrarySection.EVENTS -> EventGrid(
                    events = index.events,
                    emptyText = t("No saved incidents", "暂无保存的事件录像"),
                    onOpen = { detail = it.toDetail() },
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

    playSegment?.let { segment ->
        MediaPlaybackDialog(
            file = segment.file,
            laneLabels = playbackLabels(segment),
            onDismiss = { playSegment = null },
        )
    }
    deleteSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { deleteSegment = null },
            title = { Text(t("Delete this segment?", "删除这个片段？")) },
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
    noteSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { noteSegment = null },
            title = { Text(t("Segment note", "片段备注")) },
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
    emptyText: String,
    onOpen: (IndexedRecordingSession) -> Unit,
) {
    if (sessions.isEmpty()) return EmptyLibrary(emptyText)
    val groups = sessions.groupBy { dayLabel(it.startedAtEpochMs) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
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
                    title = if (session.hasIncident) t("Recording · incident saved", "普通录像 · 已保存事件") else t("Recording", "普通录像"),
                    startedAt = session.startedAtEpochMs,
                    durationMs = session.durationMs,
                    sizeBytes = session.sizeBytes,
                    segmentCount = session.segments.size,
                    source = session.sourceRole,
                    onClick = { onOpen(session) },
                )
            }
        }
    }
}

@Composable
private fun EventGrid(
    events: List<IndexedRecordingEvent>,
    emptyText: String,
    onOpen: (IndexedRecordingEvent) -> Unit,
) {
    if (events.isEmpty()) return EmptyLibrary(emptyText)
    val groups = events.groupBy { dayLabel(it.startedAtEpochMs) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
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
                    onClick = { onOpen(event) },
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
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            MediaCover(cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatClock(startedAt), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${sourceLabel(source)} · ${formatDuration(durationMs)} · ${t("$segmentCount segments", "$segmentCount 个片段")}",
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
    }
}

@Composable
private fun MediaDetailScreen(
    detail: MediaDetail,
    onBack: () -> Unit,
    onPlay: (IndexedMediaSegment) -> Unit,
    onShare: (IndexedMediaSegment) -> Unit,
    onSave: (IndexedMediaSegment) -> Unit,
    onNote: (IndexedMediaSegment) -> Unit,
    onDelete: (IndexedMediaSegment) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
            Text(
                if (detail.isEvent) t("Incident", "事件录像") else t("Recording session", "录像 Session"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        MediaCover(detail.cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(formatDateTime(detail.startedAtEpochMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${sourceLabel(detail.sourceRole)} · ${formatDuration(detail.durationMs)}")
                Text(
                    t(
                        "${detail.segments.size} physical segments · ${formatMediaBytes(detail.sizeBytes)}",
                        "${detail.segments.size} 个物理片段 · ${formatMediaBytes(detail.sizeBytes)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    t("Continuous session playback arrives in v2.2.2. You can play each segment below now.", "跨片段连续播放将在 v2.2.2 完成；现在可以播放下面的每个片段。"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(t("Included segments", "包含的片段"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail.segments.forEachIndexed { index, segment ->
            SegmentCard(
                index = index,
                segment = segment,
                onPlay = { onPlay(segment) },
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
            Text(t("Segment ${index + 1}", "片段 ${index + 1}"), fontWeight = FontWeight.SemiBold)
            Text(
                "${formatClock(segment.startedAtEpochMs)} · ${formatDuration(segment.durationMs)} · ${formatMediaBytes(segment.sizeBytes)}",
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
                TextButton(onClick = onDelete) { Text(t("Delete segment", "删除片段")) }
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
    segments = segments,
)

private fun IndexedRecordingEvent.toDetail() = MediaDetail(
    id = id,
    isEvent = true,
    startedAtEpochMs = startedAtEpochMs,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    sourceRole = sourceRole,
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
