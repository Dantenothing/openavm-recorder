package com.dante.zeekrbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.dante.zeekrbridge.player.PlaybackSeekRequest

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.dante.zeekrbridge.core.ReceivedSentryRegistrar
import com.dante.zeekrbridge.core.SavedMediaOrigin
import com.dante.zeekrbridge.core.SavedMediaRecord
import com.dante.zeekrbridge.core.SavedMediaStore
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
    NORMAL("Recordings", "普通视频"),
    TIME_LAPSE("Time-lapse", "延时视频"),
    EVENTS("Incidents", "紧急事件"),
    SENTRY("Sentry", "哨兵模式"),
}

private data class UriPlaybackRequest(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sourceRole: IndexedSourceRole = IndexedSourceRole.UNKNOWN,
    val layoutKind: IndexedLayoutKind = IndexedLayoutKind.UNKNOWN,
    val laneLabels: List<String> = emptyList(),
    val laneOrder: List<Int> = emptyList(),
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
)

private sealed interface LibraryFeedItem {
    val key: String
    val timestamp: Long

    data class Session(val value: IndexedRecordingSession) : LibraryFeedItem {
        override val key: String = "session:${value.id}"
        override val timestamp: Long = value.startedAtEpochMs
    }

    data class Event(val value: IndexedRecordingEvent) : LibraryFeedItem {
        override val key: String = "event:${value.id}"
        override val timestamp: Long = value.startedAtEpochMs
    }

    data class Saved(val value: SavedMediaRecord) : LibraryFeedItem {
        override val key: String = "saved:${value.id}"
        override val timestamp: Long = value.createdAtEpochMs
    }

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

private data class BatchDeleteRequest(
    val logicalCount: Int,
    val isEvent: Boolean,
    val plan: MediaDeletePlan,
)

// Save identifiers rather than entire metadata graphs when the Activity rotates.
private val MediaDetailStateSaver = listSaver<MediaDetail?, Any>(
    save = { value -> value?.let { listOf(it.id, it.isEvent) }.orEmpty() },
    restore = { values ->
        val id = values.getOrNull(0) as? String
        if (values.getOrNull(1) == true) MediaIndexStore.snapshot.value.events.firstOrNull { it.id == id }?.toDetail()
        else MediaIndexStore.snapshot.value.sessions.firstOrNull { it.id == id }?.toDetail()
    },
)

private val UriPlaybackStateSaver = listSaver<UriPlaybackRequest?, Any>(
    save = { value -> value?.let {
        listOf(it.uri.toString(), it.displayName, it.durationMs, it.sourceRole.name, it.layoutKind.name,
            ArrayList(it.laneLabels), ArrayList(it.laneOrder), it.originalWidth ?: 0, it.originalHeight ?: 0)
    }.orEmpty() },
    restore = { values -> runCatching {
        UriPlaybackRequest(Uri.parse(values[0] as String), values[1] as String, values[2] as Long,
            IndexedSourceRole.valueOf(values[3] as String), IndexedLayoutKind.valueOf(values[4] as String),
            (values[5] as List<*>).filterIsInstance<String>(), (values[6] as List<*>).filterIsInstance<Int>(),
            (values[7] as Int).takeIf { it > 0 }, (values[8] as Int).takeIf { it > 0 })
    }.getOrNull() },
)

@Composable
fun SessionMediaLibraryScreen(onDetailVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val received by ReceivedStore.files.collectAsState()
    val index by MediaIndexStore.snapshot.collectAsState()
    val scanning by MediaIndexStore.scanning.collectAsState()
    val savedMedia by SavedMediaStore.records.collectAsState()
    val sentryMedia = savedMedia.filter { it.origin == SavedMediaOrigin.SENTRY }
    val normalSessions = index.sessions.filter { it.recordingMode == IndexedRecordingMode.NORMAL }
    val timeLapseSessions = index.sessions.filter { it.recordingMode == IndexedRecordingMode.TIME_LAPSE }

    var sectionName by rememberSaveable { mutableStateOf(LibrarySection.ALL.name) }
    val section = runCatching { LibrarySection.valueOf(sectionName) }.getOrDefault(LibrarySection.ALL)
    var detail by rememberSaveable(stateSaver = MediaDetailStateSaver) { mutableStateOf<MediaDetail?>(null) }
    var exportDetail by remember { mutableStateOf<MediaDetail?>(null) }
    var uriPlayback by rememberSaveable(stateSaver = UriPlaybackStateSaver) { mutableStateOf<UriPlaybackRequest?>(null) }
    var deleteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var batchDelete by remember { mutableStateOf<BatchDeleteRequest?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var noteSegment by remember { mutableStateOf<IndexedMediaSegment?>(null) }
    var noteText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    val allGridState = rememberLazyGridState()
    val sessionGridState = rememberLazyGridState()
    val timeLapseGridState = rememberLazyGridState()
    val eventGridState = rememberLazyGridState()
    val sentryGridState = rememberLazyGridState()

    LaunchedEffect(received) {
        withContext(Dispatchers.IO) { ReceivedSentryRegistrar.reconcile(received) }
        MediaIndexStore.refresh(received)
    }
    LaunchedEffect(Unit) { SavedMediaStore.reconcileMissing() }
    LaunchedEffect(section) { selectedIds = emptySet() }
    LaunchedEffect(index, section) {
        val validIds = when (section) {
            LibrarySection.NORMAL -> normalSessions.mapTo(hashSetOf()) { it.id }
            LibrarySection.TIME_LAPSE -> timeLapseSessions.mapTo(hashSetOf()) { it.id }
            LibrarySection.EVENTS -> index.events.mapTo(hashSetOf()) { it.id }
            else -> emptySet()
        }
        selectedIds = selectedIds.intersect(validIds)
    }

    fun openDetail(value: MediaDetail) {
        statusText = ""
        detail = value
    }
    val currentVisibilityCallback by rememberUpdatedState(onDetailVisibilityChanged)
    val detailOpen = detail != null || uriPlayback != null
    DisposableEffect(detailOpen) {
        currentVisibilityCallback(detailOpen)
        onDispose { currentVisibilityCallback(false) }
    }
    val playerOpen = uriPlayback != null
    BackHandler(enabled = playerOpen || detail != null || selectedIds.isNotEmpty()) {
        when (resolveLibraryBackAction(playerOpen, detail != null, selectedIds.size)) {
            LibraryBackAction.CLOSE_PLAYER -> {
                uriPlayback = null
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
            playbackObstructed = exportDetail != null || deleteSegment != null || noteSegment != null,
            statusText = statusText,
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
                LibrarySection.NORMAL -> normalSessions.map { it.id }
                LibrarySection.TIME_LAPSE -> timeLapseSessions.map { it.id }
                LibrarySection.EVENTS -> index.events.map { it.id }
                else -> emptyList()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("{0} selected", "已选择 {0} 项", selectedIds.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedIds = selectableIds.toSet() }) { Text(t("Select all", "全选")) }
                TextButton(onClick = {
                    val selectedGroups = when (section) {
                        LibrarySection.NORMAL -> normalSessions.filter { it.id in selectedIds }.map { it.segments }
                        LibrarySection.TIME_LAPSE -> timeLapseSessions.filter { it.id in selectedIds }.map { it.segments }
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
                            "{0} recordings · {1} time-lapse · {2} incidents · {3} Sentry exports", "{0} 个普通视频 · {1} 个延时视频 · {2} 个紧急事件 · {3} 个哨兵成品", normalSessions.size, timeLapseSessions.size, index.events.size, sentryMedia.size),
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
        if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.bodySmall)

        Box(Modifier.weight(1f)) {
            when (section) {
                LibrarySection.ALL -> CombinedMediaGrid(
                    sessions = index.sessions,
                    events = index.events,
                    sentry = sentryMedia,
                    state = allGridState,
                    onOpenSession = { openDetail(it.toDetail()) },
                    onOpenEvent = { openDetail(it.toDetail()) },
                    onOpenSaved = { record ->
                        record.uri?.let { uri ->
                            uriPlayback = record.toPlaybackRequest(uri)
                        }
                    },
                )
                LibrarySection.NORMAL -> SessionGrid(
                    sessions = normalSessions,
                    state = sessionGridState,
                    emptyText = t("No OpenAVM recordings on this phone", "手机中还没有 OpenAVM 录像"),
                    selectedIds = selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onOpen = { openDetail(it.toDetail()) },
                    onToggle = { id ->
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        }
                    },
                    onLongPress = { selectedIds = selectedIds + it },
                )
                LibrarySection.TIME_LAPSE -> SessionGrid(
                    sessions = timeLapseSessions,
                    state = timeLapseGridState,
                    emptyText = t("No time-lapse recordings", "暂无延时视频"),
                    selectedIds = selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onOpen = { openDetail(it.toDetail()) },
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
                    onOpen = { openDetail(it.toDetail()) },
                    onToggle = { id ->
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        }
                    },
                    onLongPress = { selectedIds = selectedIds + it },
                )
                LibrarySection.SENTRY -> SavedMediaGrid(
                    records = sentryMedia,
                    state = sentryGridState,
                    onPlay = { record ->
                        record.uri?.let { uri -> uriPlayback = record.toPlaybackRequest(uri) }
                    },
                )
            }
        }
    }

    exportDetail?.let { selected ->
        MediaExportDialog(segments = selected.segments, onDismiss = { exportDetail = null })
    }

    uriPlayback?.let { request ->
        UriMediaPlaybackDialog(
            uri = request.uri,
            displayName = request.displayName,
            durationMs = request.durationMs,
            sourceRole = request.sourceRole,
            layoutKind = request.layoutKind,
            laneLabels = request.laneLabels,
            laneOrder = request.laneOrder,
            originalWidth = request.originalWidth,
            originalHeight = request.originalHeight,
            onDismiss = { uriPlayback = null },
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
                            "{0} events reference {1} physical videos. Moving them to trash can also remove those segments from their Sessions.", "{0} 个事件引用 {1} 个分段文件。移入回收站后，这些分段也会从对应录像片段中消失。", request.logicalCount, request.plan.physicalVideoCount)
                    } else {
                        t(
                            "{0} Sessions contain {1} unique physical videos. MP4 files and metadata will move to OpenAVM trash.", "{0} 个录像片段共包含 {1} 个分段文件。MP4 和元数据将移入 OpenAVM 回收站。", request.logicalCount, request.plan.physicalVideoCount)
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
                            "Moved {0} videos to OpenAVM trash", "已将 {0} 个视频移入 OpenAVM 回收站", moved)
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
                            t("Time-lapse · {0}×", "延时摄影 · {0}×", session.timeLapseMultiplier)
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
private fun CombinedMediaGrid(
    sessions: List<IndexedRecordingSession>,
    events: List<IndexedRecordingEvent>,
    sentry: List<SavedMediaRecord>,
    state: LazyGridState,
    onOpenSession: (IndexedRecordingSession) -> Unit,
    onOpenEvent: (IndexedRecordingEvent) -> Unit,
    onOpenSaved: (SavedMediaRecord) -> Unit,
) {
    val feed = remember(sessions, events, sentry) {
        buildList<LibraryFeedItem> {
            sessions.forEach { add(LibraryFeedItem.Session(it)) }
            events.forEach { add(LibraryFeedItem.Event(it)) }
            sentry.forEach { add(LibraryFeedItem.Saved(it)) }
        }.sortedByDescending { it.timestamp }
    }
    if (feed.isEmpty()) return EmptyLibrary(t("No videos found", "暂无视频"))
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        gridItems(feed, key = { it.key }) { item ->
            when (item) {
                is LibraryFeedItem.Session -> LibraryCard(
                    cover = item.value.cover,
                    title = if (item.value.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                        t("Time-lapse · {0}×", "延时摄影 · {0}×", item.value.timeLapseMultiplier)
                    } else {
                        t("Recording", "普通视频")
                    },
                    startedAt = item.value.startedAtEpochMs,
                    durationMs = item.value.durationMs,
                    sizeBytes = item.value.sizeBytes,
                    segmentCount = item.value.segments.size,
                    source = item.value.sourceRole,
                    recordingMode = item.value.recordingMode,
                    realDurationMs = item.value.realDurationMs,
                    selected = false,
                    onClick = { onOpenSession(item.value) },
                    onLongClick = {},
                )
                is LibraryFeedItem.Event -> LibraryCard(
                    cover = item.value.cover,
                    title = t("★ Incident", "★ 紧急事件"),
                    startedAt = item.value.startedAtEpochMs,
                    durationMs = item.value.durationMs,
                    sizeBytes = item.value.sizeBytes,
                    segmentCount = item.value.segments.size,
                    source = item.value.sourceRole,
                    recordingMode = IndexedRecordingMode.NORMAL,
                    realDurationMs = item.value.durationMs,
                    selected = false,
                    onClick = { onOpenEvent(item.value) },
                    onLongClick = {},
                )
                is LibraryFeedItem.Saved -> SavedMediaCard(item.value) { onOpenSaved(item.value) }
            }
        }
    }
}

@Composable
private fun SavedMediaGrid(
    records: List<SavedMediaRecord>,
    state: LazyGridState,
    onPlay: (SavedMediaRecord) -> Unit,
) {
    if (records.isEmpty()) return EmptyLibrary(t("No saved Sentry videos", "暂无已保存的哨兵视频"))
    val groups = records.sortedByDescending { it.createdAtEpochMs }.groupBy { dayLabel(it.createdAtEpochMs) }
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
            gridItems(values, key = { it.id }) { record ->
                SavedMediaCard(record) { onPlay(record) }
            }
        }
    }
}

@Composable
private fun SavedMediaCard(record: SavedMediaRecord, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(record.id, record.sizeBytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, record.sizeBytes) {
        bitmap = MediaThumbnailCache.loadOrCreate(context, record)
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
            Column(Modifier.padding(10.dp)) {
                Text(t("Sentry video", "哨兵视频"), fontWeight = FontWeight.SemiBold)
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatDuration(record.durationMs)} · ${formatMediaBytes(record.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box {
                MediaCover(cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f), durationMs = durationMs)
                if (selected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatClock(startedAt), style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${t("{0} segments", "{0} 个分段", segmentCount)} · ${formatMediaBytes(sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                    Text(t("Captured {0} → video {1}", "现实拍摄 {0} → 成片 {1}", formatDuration(realDurationMs), formatDuration(durationMs)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun MediaCover(segment: IndexedMediaSegment, modifier: Modifier = Modifier, showBadges: Boolean = true, durationMs: Long = segment.durationMs) {
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
        if (showBadges) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)))))
            Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp), color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp)) {
                Text(formatDuration(durationMs), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showBadges) Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.68f),
        ) {
            Text(sourceLabel(segment.sourceRole), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
        if (showBadges && segment.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaDetailScreen(
    detail: MediaDetail,
    onBack: () -> Unit,
    playbackObstructed: Boolean,
    statusText: String,
    onExport: () -> Unit,
    onShare: (IndexedMediaSegment) -> Unit,
    onSave: (IndexedMediaSegment) -> Unit,
    onNote: (IndexedMediaSegment) -> Unit,
    onDelete: (IndexedMediaSegment) -> Unit,
) {
    var activeId by rememberSaveable(detail.id) { mutableStateOf(detail.cover.id) }
    var seekRequest by remember(detail.id) { mutableStateOf<PlaybackSeekRequest?>(null) }
    var visibleSegments by rememberSaveable(detail.id) { mutableIntStateOf(3) }
    val active = detail.segments.firstOrNull { it.id == activeId } ?: detail.cover
    val activeIndex = detail.segments.indexOf(active)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    when {
                        detail.isEvent -> t("Incident", "事件录像")
                        detail.recordingMode == IndexedRecordingMode.TIME_LAPSE -> t("Time-lapse", "延时视频")
                        else -> t("Recording clip", "录像片段")
                    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(formatDateTime(detail.startedAtEpochMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SegmentActionsMenu(enabled = active.file.isFile,
                onSave = { onSave(active) }, onShare = { onShare(active) },
                onNote = { onNote(active) }, onDelete = { onDelete(active) })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaSessionPlayer(detail.segments, seekRequest, playbackObstructed, onActiveMediaChanged = { activeId = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailBadge(sourceLabel(detail.sourceRole))
                DetailBadge(formatDuration(detail.durationMs))
                DetailBadge(formatMediaBytes(detail.sizeBytes))
                if (detail.recordingMode == IndexedRecordingMode.TIME_LAPSE) DetailBadge("${detail.timeLapseMultiplier}×")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport, enabled = detail.segments.any { it.file.isFile },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.small) {
                    Icon(MediaIcons.Export, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("Export clip", "导出片段"), maxLines = 2)
                }
                OutlinedButton(onClick = { onShare(active) }, enabled = active.file.isFile,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.Share, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (detail.segments.size > 1) t("Share segment", "分享当前分段") else t("Share", "分享"), maxLines = 2)
                }
            }
            if (statusText.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(statusText, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (detail.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
                Text(t("Captured {0} → video {1}", "现实拍摄 {0} → 成片 {1}", formatDuration(detail.realDurationMs), formatDuration(detail.durationMs)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MediaExportQueueCard()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Segments", "分段"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${activeIndex + 1} / ${detail.segments.size}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                detail.segments.take(visibleSegments).forEachIndexed { index, segment ->
                    key(segment.id) {
                        SegmentCard(index, segment, active = segment.id == activeId,
                            onSelect = { seekRequest = PlaybackSeekRequest(segment.id, (seekRequest?.sequence ?: 0) + 1) },
                            onShare = { onShare(segment) }, onSave = { onSave(segment) },
                            onNote = { onNote(segment) }, onDelete = { onDelete(segment) })
                    }
                }
                if (detail.segments.size > visibleSegments) {
                    TextButton(onClick = { visibleSegments += 20 }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Show more segments", "显示更多分段"))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBadge(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: IndexedMediaSegment,
    active: Boolean,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(onClick = onSelect, enabled = segment.file.isFile,
        modifier = Modifier.fillMaxWidth().semantics { selected = active },
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(64.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                MediaCover(segment, Modifier.fillMaxSize(), showBadges = false)
                if (active) Surface(color = Color.Black.copy(alpha = 0.70f), shape = RoundedCornerShape(6.dp)) {
                    Icon(MediaIcons.Play, null, Modifier.padding(4.dp).size(16.dp), tint = Color.White)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t("Segment {0}", "分段 {0}", index + 1), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text("${formatClock(segment.startedAtEpochMs)} · ${formatDuration(segment.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(segment.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SegmentActionsMenu(segment.file.isFile, onSave, onShare, onNote, onDelete)
        }
    }
}

@Composable
private fun SegmentActionsMenu(
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, t("More options", "更多选项")) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(t("Save to gallery", "保存到相册")) }, enabled = enabled,
                leadingIcon = { Icon(MediaIcons.Save, null) }, onClick = { expanded = false; onSave() })
            DropdownMenuItem(text = { Text(t("Share", "分享")) }, enabled = enabled,
                leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text(t("Note", "备注")) },
                leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onNote() })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(text = { Text(t("Delete segment", "删除分段"), color = MaterialTheme.colorScheme.error) }, enabled = enabled,
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() })
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

private fun SavedMediaRecord.toPlaybackRequest(uri: Uri) = UriPlaybackRequest(
    uri = uri,
    displayName = displayName,
    durationMs = durationMs,
    sourceRole = IndexedSourceRole.SURROUND,
    layoutKind = indexedLayoutKind,
    laneLabels = laneLabels,
    laneOrder = laneOrder,
    originalWidth = originalWidth,
    originalHeight = originalHeight,
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
    return when (segment.layoutKind) {
        IndexedLayoutKind.FOUR_LANE_V1 ->
            listOf(t("Front", "前"), t("Rear", "后"), t("Left", "左"), t("Right", "右"))
        IndexedLayoutKind.FOUR_LANE_GRID_2X2 ->
            listOf(t("Top left", "左上"), t("Top right", "右上"), t("Bottom left", "左下"), t("Bottom right", "右下"))
        else -> emptyList()
    }
}

private fun dayLabel(epochMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(epochMs))
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86_400_000L))
    return when (day) {
        today -> t("Today", "今天")
        yesterday -> t("Yesterday", "昨天")
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, PhoneLanguage.locale).format(Date(epochMs))
    }
}

private fun formatClock(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, PhoneLanguage.locale).format(Date(epochMs))

private fun formatDateTime(epochMs: Long): String = if (epochMs <= 0L) "—" else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, PhoneLanguage.locale).format(Date(epochMs))

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
    LibrarySection.NORMAL,
    LibrarySection.TIME_LAPSE,
    LibrarySection.EVENTS,
    LibrarySection.SENTRY,
)

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
