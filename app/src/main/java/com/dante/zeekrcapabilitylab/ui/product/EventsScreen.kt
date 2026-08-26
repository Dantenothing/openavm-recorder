package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Checkbox
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
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
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
    val libraryRevision by RecorderLibrary.revision.collectAsState()

    var segments by remember { mutableStateOf<List<EventGroups.Segment>>(emptyList()) }
    var covers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var statusText by remember { mutableStateOf("") }
    var playGroup by remember { mutableStateOf<EventGroups.EventGroup?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<EventGroups.EventGroup?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteSelection by remember { mutableStateOf<Set<String>?>(null) }

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
                selectedNames = selectedNames.intersect(names)
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

    fun toggleGroupProtection(group: EventGroups.EventGroup) {
        scope.launch(Dispatchers.IO) {
            val protect = group.protectedCount == 0
            val changed = group.segments.count { segment ->
                if (protect) RecorderLibrary.bookmark(segment.file)
                else RecorderLibrary.unbookmark(segment.file)
            }
            withContext(Dispatchers.Main) {
                statusText = if (changed == group.segments.size) {
                    if (protect) Utils.t("Recording protected", "录像已保护")
                    else Utils.t("Protection removed", "已取消保护")
                } else {
                    Utils.t("Unable to change protection", "保护状态修改失败")
                }
            }
            refresh()
        }
    }

    fun deleteGroup(group: EventGroups.EventGroup) {
        scope.launch(Dispatchers.IO) {
            val attempts = RecorderLibrary.deleteManagedByUser(
                segmentsDir,
                group.segments.map { it.file },
            )
            attempts.filter { it.result.deleted }.forEach { thumbnailCache.remove(it.file) }
            val blocked = attempts.count { !it.result.deleted }
            withContext(Dispatchers.Main) {
                statusText = if (blocked == 0) {
                    Utils.t("Video deleted", "视频已删除")
                } else {
                    Utils.t("Video could not be fully deleted because part is locked", "视频未能完整删除，部分内容正在使用中")
                }
            }
            refresh()
        }
    }

    fun deleteSelection(names: Set<String>) {
        scope.launch(Dispatchers.IO) {
            val selectedFiles = RecorderLibrary.selectManaged(
                RecorderLibrary.listFinalized(segmentsDir),
                names,
            )
            val attempts = RecorderLibrary.deleteManagedByUser(segmentsDir, selectedFiles)
            val deletedFiles = attempts.filter { it.result.deleted }.map { it.file }
            deletedFiles.forEach(thumbnailCache::remove)
            val deletedNames = deletedFiles.mapTo(mutableSetOf()) { it.name }
            val blocked = names.size - deletedNames.size
            withContext(Dispatchers.Main) {
                statusText = if (blocked > 0) {
                    Utils.t(
                        "Some selected recordings could not be fully deleted because content is locked",
                        "部分所选录像未能完整删除，部分内容正在使用中",
                    )
                } else {
                    Utils.t("Selected recordings deleted", "已删除所选录像")
                }
            }
            refresh()
        }
    }

    LaunchedEffect(libraryRevision) { refresh() }

    val incidents = remember(segments, languageMode) { EventGroups.groupIncidents(segments) }
    val recordingGroups = remember(segments) { EventGroups.groupRecordings(segments) }
    val dateGroups = remember(segments, languageMode) { EventGroups.groupRecordingsByDate(segments) }
    val selectedRecordingCount = recordingGroups.count { group ->
        group.segments.all { it.file.name in selectedNames }
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Utils.t("Recordings", "录像记录"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (selectionMode) {
                                Utils.t("$selectedRecordingCount selected", "已选择 $selectedRecordingCount 项")
                            } else {
                                Utils.t("${recordingGroups.size} recordings", "${recordingGroups.size} 个录像")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectionMode) {
                        OutlinedButton(
                            onClick = {
                                selectedNames = if (selectedRecordingCount == recordingGroups.size) {
                                    emptySet()
                                } else {
                                    segments.mapTo(mutableSetOf()) { it.file.name }
                                }
                            },
                        ) {
                            Text(
                                if (selectedRecordingCount == recordingGroups.size) {
                                    Utils.t("Clear all", "全部取消")
                                } else {
                                    Utils.t("Select all", "全选")
                                },
                            )
                        }
                        TextButton(
                            onClick = { pendingDeleteSelection = selectedNames },
                            enabled = selectedNames.isNotEmpty(),
                        ) {
                            Text(
                                Utils.t("Delete ($selectedRecordingCount)", "删除 ($selectedRecordingCount)"),
                                color = if (selectedNames.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                selectionMode = false
                                selectedNames = emptySet()
                            },
                        ) { Text(Utils.t("Cancel", "取消")) }
                    } else {
                        OutlinedButton(
                            onClick = { selectionMode = true },
                            enabled = recordingGroups.isNotEmpty(),
                        ) { Text(Utils.t("Select", "选择")) }
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
                    val eventNames = group.segments.mapTo(mutableSetOf()) { it.file.name }
                    val groupNames = recordingGroups
                        .filter { recording -> recording.segments.any { it.file.name in eventNames } }
                        .flatMapTo(mutableSetOf()) { recording -> recording.segments.map { it.file.name } }
                    val selected = groupNames.isNotEmpty() && groupNames.all { it in selectedNames }
                    LaunchedEffect(first.file.name) { loadCover(first.file) }
                    SavedEventCard(
                        group = group,
                        cover = covers[first.file.name],
                        onPlay = { playGroup = group },
                        onToggleProtect = { toggleGroupProtection(group) },
                        onDelete = { pendingDeleteGroup = group },
                        selectionMode = selectionMode,
                        selected = selected,
                        onToggleSelection = {
                            selectedNames = if (selected) {
                                selectedNames - groupNames
                            } else {
                                selectedNames + groupNames
                            }
                        },
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

            dateGroups.forEach { (date, recordings) ->
                item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(formatDateHeader(date), Utils.t("${recordings.size}", "${recordings.size} 个"))
                }
                items(
                    items = recordings,
                    key = { "recording:${it.segments.first().file.name}" },
                ) { group ->
                    val first = group.segments.first()
                    val groupNames = group.segments.mapTo(mutableSetOf()) { it.file.name }
                    val selected = groupNames.all { it in selectedNames }
                    LaunchedEffect(first.file.name) { loadCover(first.file) }
                    RecordingCard(
                        group = group,
                        cover = covers[first.file.name],
                        onPlay = { playGroup = group },
                        onToggleProtect = { toggleGroupProtection(group) },
                        onDelete = { pendingDeleteGroup = group },
                        selectionMode = selectionMode,
                        selected = selected,
                        onToggleSelection = {
                            selectedNames = if (selected) {
                                selectedNames - groupNames
                            } else {
                                selectedNames + groupNames
                            }
                        },
                    )
                }
            }
        }
    }

    playGroup?.let { group ->
        val playbackIndex = recordingGroups.indexOfFirst { candidate ->
            candidate.segments.map { it.file } == group.segments.map { it.file }
        }
        val previousGroup = playbackIndex.takeIf { it >= 0 }?.let { recordingGroups.getOrNull(it - 1) }
        val nextGroup = playbackIndex.takeIf { it >= 0 }?.let { recordingGroups.getOrNull(it + 1) }
        FourLanePlayerDialog(
            files = group.segments.map { it.file },
            onPrevious = previousGroup?.let { previous -> { playGroup = previous } },
            onNext = nextGroup?.let { next -> { playGroup = next } },
            onSendToPhone = null,
            onDelete = {
                // Close playback first so every internal file pin is released
                // before the single confirmation dialog can delete the recording.
                playGroup = null
                pendingDeleteGroup = group
            },
            onDismiss = { playGroup = null },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(Utils.t("Delete this video?", "删除这个视频？")) },
            text = { Text(Utils.t("This will permanently delete all included video, including protected content.", "这会永久删除其中包含的全部视频，包括已保护内容。")) },
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

    pendingDeleteSelection?.let { names ->
        val recordingCount = recordingGroups.count { group ->
            group.segments.all { it.file.name in names }
        }
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = null },
            title = {
                Text(
                    Utils.t(
                        "Delete $recordingCount selected recordings?",
                        "删除已选择的 $recordingCount 个录像？",
                    ),
                )
            },
            text = {
                Text(
                    Utils.t(
                        "This permanently deletes the selected recordings, including protected recordings. Temporarily locked recordings will be kept.",
                        "这会永久删除所选录像，包括已保护录像。暂时锁定中的录像会被保留。",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSelection = null
                        selectionMode = false
                        selectedNames = emptySet()
                        deleteSelection(names)
                    },
                ) {
                    Text(Utils.t("Delete", "删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelection = null }) {
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
    group: EventGroups.EventGroup,
    cover: Bitmap?,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val first = group.segments.first()
    var menuOpen by remember(first.file.name) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onToggleSelection else onPlay),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
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
                    if (group.protectedCount > 0) StatusBadge(Utils.t("Protected", "已保护"), Color(0xFFFFB74D))
                }
                StatusBadge(
                    text = if (first.sidecar.recordingMode == RecordingMode.FRONT_ONLY) {
                        Utils.t("Front", "前方")
                    } else {
                        Utils.t("4 views", "四路")
                    },
                    color = Color(0xFF81C784),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(9.dp),
                )
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(999.dp)),
                    )
                } else {
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
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    formatRecordingTime(group.startedAtEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${Utils.formatDuration(group.durationMs)}  ·  ${formatBytes(group.totalBytes)}",
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
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    var menuOpen by remember(group.segments.first().file.name) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onToggleSelection else onPlay),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
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
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(999.dp)),
                    )
                } else {
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
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    Utils.t("Event ${formatRecordingTime(group.startedAtEpochMs)}", "事件 ${formatRecordingTime(group.startedAtEpochMs)}"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Utils.t("${Utils.formatDuration(group.durationMs)}  ·  ${group.laneCount} views", "${Utils.formatDuration(group.durationMs)}  ·  ${group.laneCount} 路"),
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

private fun formatRecordingTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatDateHeader(value: String): String = runCatching {
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) ?: return value
    SimpleDateFormat(
        if (AppLanguage.usesChinese()) "M月d日 EEEE" else "EEE, MMM d",
        if (AppLanguage.usesChinese()) Locale.SIMPLIFIED_CHINESE else Locale.US,
    ).format(source)
}.getOrDefault(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} KB"
}
