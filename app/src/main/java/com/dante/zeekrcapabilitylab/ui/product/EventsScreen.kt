package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.player.RecordingThumbnailCache
import com.dante.zeekrcapabilitylab.product.EventGroups
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.util.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EventsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val segmentsDir = remember { File(context.filesDir, "recordings/segments").apply { mkdirs() } }
    val thumbnailCache = remember {
        RecordingThumbnailCache(File(context.cacheDir, "recording-covers"))
    }
    val languageMode by AppLanguage.mode.collectAsState()
    val recorderState by CameraRecordingService.state.collectAsState()

    var segments by remember { mutableStateOf<List<EventGroups.Segment>>(emptyList()) }
    var covers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var statusText by remember { mutableStateOf("") }
    var playFile by remember { mutableStateOf<File?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<File?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<EventGroups.EventGroup?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val items = RecorderLibrary.listFinalized(segmentsDir).mapNotNull { file ->
                SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.let {
                    EventGroups.Segment(file, it)
                }
            }
            withContext(Dispatchers.Main) {
                segments = items
                val names = items.mapTo(mutableSetOf()) { it.file.name }
                covers = covers.filterKeys { it in names }
            }
        }
    }

    fun loadCover(file: File) {
        if (file.name in covers) return
        scope.launch(Dispatchers.IO) {
            val cover = thumbnailCache.loadOrCreate(file)
            if (cover != null) {
                withContext(Dispatchers.Main) {
                    covers = covers + (file.name to cover)
                }
            }
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
                statusText = if (protect) Utils.t("Protected $changed segments", "已保护 $changed 个分段") else Utils.t("Removed protection from $changed segments", "已取消保护 $changed 个分段")
            }
            refresh()
        }
    }

    fun deleteFile(file: File) {
        scope.launch(Dispatchers.IO) {
            thumbnailCache.remove(file)
            val result = RecorderLibrary.deleteManagedByUser(segmentsDir, file)
            withContext(Dispatchers.Main) {
                if (result.deleted) {
                    if (playFile == file) playFile = null
                    statusText = Utils.t("Recording deleted", "录像已删除")
                } else {
                    statusText = deleteFailureText(result.reason)
                }
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
                statusText = Utils.t("Deleted $deleted segments", "已删除 $deleted 个分段") +
                    if (blocked > 0) Utils.t("; kept $blocked protected or locked segments", "；受保护或锁定中的 $blocked 个已保留") else ""
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
                playFile = null
                covers = emptyMap()
                statusText = Utils.t(
                    "Deleted ${result.deleted} recordings" +
                        if (result.blocked > 0) "; kept ${result.blocked} active or transfer-locked recordings" else "",
                    "已删除 ${result.deleted} 段录像" +
                        if (result.blocked > 0) "；${result.blocked} 段正在播放或传输锁定的录像已保留" else "",
                )
            }
            refresh()
        }
    }

    LaunchedEffect(recorderState.libraryRevision) { refresh() }

    val incidents = remember(segments, languageMode) { EventGroups.groupIncidents(segments) }
    val dateGroups = remember(segments, languageMode) { EventGroups.groupByDate(segments) }
    val playbackSegments = remember(segments) {
        segments.sortedByDescending(::recordingEpoch)
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Utils.t("Recordings", "录像记录"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            Utils.t("${segments.size} recordings", "${segments.size} 段录像"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { confirmDeleteAll = true },
                            enabled = segments.isNotEmpty(),
                        ) {
                            Text(
                                Utils.t("Clear all", "清空全部"),
                                color = if (segments.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        }
                        OutlinedButton(onClick = { refresh() }) { Text(Utils.t("Refresh", "刷新")) }
                    }
                }
                if (statusText.isNotBlank()) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (incidents.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(Utils.t("Saved events", "已保存事件"), Utils.t("${incidents.size}", "${incidents.size} 个"))
                }
                items(incidents, key = { "event:${it.segments.first().file.name}" }) { group ->
                    val first = group.segments.first()
                    LaunchedEffect(first.file.name) { loadCover(first.file) }
                    SavedEventCard(
                        group = group,
                        cover = covers[first.file.name],
                        onPlay = { playFile = first.file },
                        onToggleProtect = { toggleGroupProtection(group) },
                        onDelete = { pendingDeleteGroup = group },
                    )
                }
            }

            if (segments.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Utils.t("No recordings", "暂无录像"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            dateGroups.forEach { (date, group) ->
                item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(formatDateHeader(date), Utils.t("${group.segments.size}", "${group.segments.size} 段"))
                }
                items(
                    items = group.segments.sortedByDescending { recordingEpoch(it) },
                    key = { "recording:${it.file.name}" },
                ) { segment ->
                    LaunchedEffect(segment.file.name) { loadCover(segment.file) }
                    RecordingCard(
                        segment = segment,
                        cover = covers[segment.file.name],
                        onPlay = { playFile = segment.file },
                        onToggleProtect = { toggleFileProtection(segment) },
                        onDelete = { pendingDeleteFile = segment.file },
                    )
                }
            }
        }
    }

    playFile?.let { file ->
        val playbackIndex = playbackSegments.indexOfFirst { it.file == file }
        val previousFile = playbackSegments.getOrNull(playbackIndex - 1)?.file
        val nextFile = playbackSegments.getOrNull(playbackIndex + 1)?.file
        FourLanePlayerDialog(
            file = file,
            onPrevious = previousFile?.let { previous -> { playFile = previous } },
            onNext = nextFile?.let { next -> { playFile = next } },
            onSendToPhone = null,
            onDelete = {
                // Close playback first so its MediaPlayer and playback pin are
                // released before the single confirmation dialog can delete.
                playFile = null
                pendingDeleteFile = file
            },
            onDismiss = { playFile = null },
        )
    }

    pendingDeleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text(Utils.t("Delete this recording?", "删除这段录像？")) },
            text = { Text(Utils.t("This will permanently delete the recording, including a protected recording.", "这会永久删除该录像，包括已保护录像。")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteFile = null
                    deleteFile(file)
                }) { Text(Utils.t("Delete", "删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(Utils.t("Delete ${group.segments.size} segments?", "删除 ${group.segments.size} 个分段？")) },
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

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(Utils.t("Delete all recordings?", "删除全部录像？")) },
            text = {
                Text(
                    Utils.t(
                        "This permanently deletes all recordings in the library, including protected recordings. Recordings being played or transferred are kept.",
                        "这会永久删除记录库中的全部录像，包括已保护录像。正在播放或传输中的录像会被保留。",
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
    cover: Bitmap?,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(segment.file.name) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
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
                        .padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (segment.sidecar.protected) StatusBadge(Utils.t("Protected", "已保护"), Color(0xFFFFB74D))
                }
                StatusBadge(
                    text = Utils.t("4 views", "四路"),
                    color = Color(0xFF81C784),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(9.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                ) {
                    TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(999.dp)),
                    ) { Text("•••", color = Color.White) }
                    RecordingActionsMenu(
                        expanded = menuOpen,
                        protected = segment.sidecar.protected,
                        onDismiss = { menuOpen = false },
                        onToggleProtect = onToggleProtect,
                        onDelete = onDelete,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    formatRecordingTime(recordingEpoch(segment)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatDuration(segment)}  ·  ${formatBytes(segment.file.length())}",
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
    onDelete: () -> Unit,
) {
    var menuOpen by remember(group.segments.first().file.name) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
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
                        .padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusBadge(Utils.t("Saved", "已保存"), Color(0xFFFFB74D))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                ) {
                    TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(999.dp)),
                    ) { Text("•••", color = Color.White) }
                    RecordingActionsMenu(
                        expanded = menuOpen,
                        protected = group.protectedCount > 0,
                        onDismiss = { menuOpen = false },
                        onToggleProtect = onToggleProtect,
                        onDelete = onDelete,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    Utils.t("Event ${formatRecordingTime(group.startedAtEpochMs)}", "事件 ${formatRecordingTime(group.startedAtEpochMs)}"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t("${Utils.formatDuration(group.durationMs)}  ·  ${group.segments.size} segments  ·  ${group.laneCount} views", "${Utils.formatDuration(group.durationMs)}  ·  ${group.segments.size} 段  ·  ${group.laneCount} 路"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordingActionsMenu(
    expanded: Boolean,
    protected: Boolean,
    onDismiss: () -> Unit,
    onToggleProtect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (protected) Utils.t("Remove protection", "取消保护") else Utils.t("Protect recording", "保护录像")) },
            onClick = { onDismiss(); onToggleProtect() },
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Send to phone · In development", "发送到手机 · 开发中")) },
            onClick = {},
            enabled = false,
        )
        DropdownMenuItem(
            text = { Text(Utils.t("Delete", "删除")) },
            onClick = { onDismiss(); onDelete() },
        )
    }
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
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun recordingEpoch(segment: EventGroups.Segment): Long =
    segment.sidecar.startedAtEpochMs ?: segment.file.lastModified()

private fun formatRecordingTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatDateHeader(value: String): String = runCatching {
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) ?: return value
    SimpleDateFormat(
        if (AppLanguage.usesChinese()) "M月d日 EEEE" else "EEE, MMM d",
        if (AppLanguage.usesChinese()) Locale.SIMPLIFIED_CHINESE else Locale.US,
    ).format(source)
}.getOrDefault(value)

private fun formatDuration(segment: EventGroups.Segment): String {
    val duration = segment.sidecar.actualTrack?.durationMs
        ?: run {
            val start = segment.sidecar.startedAtEpochMs
            val stop = segment.sidecar.stoppedAtEpochMs
            if (start != null && stop != null) stop - start else segment.sidecar.segmentSeconds * 1000L
        }
    return Utils.formatDuration(duration)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} KB"
}

private fun deleteFailureText(reason: String?): String = when (reason) {
    RecorderLibrary.DELETE_BOOKMARKED -> Utils.t("Recording is protected and was not deleted", "录像已保护，未删除")
    RecorderLibrary.DELETE_UPLOAD_PINNED -> Utils.t("Recording is temporarily locked and was not deleted", "录像暂时锁定，未删除")
    else -> Utils.t("Unable to delete recording", "录像删除失败")
}
