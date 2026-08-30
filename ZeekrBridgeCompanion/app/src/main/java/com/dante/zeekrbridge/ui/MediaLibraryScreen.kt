package com.dante.zeekrbridge.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.core.CarRecording
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.ServerLog
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private enum class MediaFilter(val en: String, val zh: String) {
    ALL("All", "全部"),
    INCIDENT("Incidents", "事故事件"),
    CAR("Car recordings", "车机录像"),
    USB("Factory USB", "原厂 USB"),
    LOCAL("Local", "本地"),
}

private data class MediaItem(
    val name: String,
    val source: String,
    val status: String,
    val file: File?,
    val carRecording: CarRecording? = null,
    val sizeBytes: Long,
    val startedAtEpochMs: Long?,
    val laneLabels: List<String>,
)

@Composable
fun MediaLibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalogOnline by CarCatalogStore.online.collectAsState()
    val carItems by CarCatalogStore.items.collectAsState()
    val received by ReceivedStore.files.collectAsState()
    val uploads by CarCatalogStore.uploads.collectAsState()
    val lastMessage by CarCatalogStore.lastMessage.collectAsState()

    var filter by remember { mutableStateOf(MediaFilter.ALL) }
    var mediaPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var localVideos by remember { mutableStateOf<List<File>>(emptyList()) }
    var playFile by remember { mutableStateOf<File?>(null) }
    var playLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var noteTarget by remember { mutableStateOf<String?>(null) }
    var noteText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun loadLocalVideos() {
        scope.launch(Dispatchers.IO) {
            val files = queryLocalVideos(context)
            withContext(Dispatchers.Main) { localVideos = files }
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        mediaPermissionGranted = granted
        if (granted) loadLocalVideos()
    }

    LaunchedEffect(Unit) {
        if (mediaPermissionGranted) loadLocalVideos()
    }
    LaunchedEffect(catalogOnline) {
        if (catalogOnline && carItems.isEmpty()) {
            BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
        }
    }

    val carNames = carItems.map { it.fileName }.toSet()
    val carMedia = carItems.map { item ->
        val local = received.firstOrNull { it.name == item.fileName }
        MediaItem(
            name = item.fileName,
            source = t("Car recording", "车机录像"),
            status = when {
                local != null -> t("Saved", "已保存")
                uploads[item.fileName] == "QUEUED" -> t("Waiting to download", "等待下载")
                else -> t("Not downloaded", "未下载")
            },
            file = local,
            carRecording = item,
            sizeBytes = item.sizeBytes,
            startedAtEpochMs = item.startedAtEpochMs,
            laneLabels = item.laneLabels,
        )
    }

    val localMedia = (received.filterNot { it.name in carNames }.map { file ->
        MediaItem(
            name = file.name,
            source = t("Local", "本地"),
            status = t("Verified", "已校验"),
            file = file,
            sizeBytes = file.length(),
            startedAtEpochMs = file.lastModified(),
            laneLabels = laneLabelsFor(file),
        )
    } + localVideos.map { file ->
        MediaItem(
            name = file.name,
            source = t("Local", "本地"),
            status = t("Verified", "已校验"),
            file = file,
            sizeBytes = file.length(),
            startedAtEpochMs = file.lastModified(),
            laneLabels = emptyList(),
        )
    }).distinctBy { it.name }

    val all = carMedia + localMedia
    val filtered = when (filter) {
        MediaFilter.ALL -> all
        MediaFilter.INCIDENT -> all.filter { it.carRecording?.protected == true }
        MediaFilter.CAR -> all.filter { it.carRecording != null }
        MediaFilter.USB -> emptyList()
        MediaFilter.LOCAL -> all.filter { it.carRecording == null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
    ) {
        Text(t("Library", "媒体库"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MediaFilter.values().forEach { f ->
                OutlinedButton(
                    onClick = { filter = f },
                ) {
                    val label = t(f.en, f.zh)
                    Text(if (f == filter) "$label ✓" else label)
                }
            }
        }
        if (filter == MediaFilter.USB) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(t("Factory USB video", "原厂 USB 视频"), fontWeight = FontWeight.SemiBold)
                    Text(
                        t(
                            "Direct car USB access is not available on phone. Use USB Assistant in the car, or export videos to the phone first.",
                            "手机端直读车机 USB 暂未开放：请先在车机端“USB 助手”中查看/复制，或将原厂录像导出到手机后在此管理。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!mediaPermissionGranted && filter == MediaFilter.LOCAL) {
            Button(onClick = {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                mediaPermissionLauncher.launch(permission)
            }) { Text(t("Allow media access", "授予媒体访问权限")) }
        }
        if (lastMessage.isNotBlank()) {
            Text(lastMessage, style = MaterialTheme.typography.bodySmall)
        }
        if (statusText.isNotBlank()) {
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        when (filter) {
                            MediaFilter.USB -> t("Factory USB is unavailable", "原厂 USB 未开放")
                            MediaFilter.CAR -> t("No car recordings. Refresh from Vehicle when connected.", "暂无车机录像（车机在线时可在“车辆”页刷新）")
                            else -> t("No media files", "暂无媒体文件")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.name }) { item ->
                MediaItemCard(
                    item = item,
                    onDownload = {
                        if (item.carRecording != null) {
                            BridgeServer.sendToCars(
                                WsType.REQUEST_UPLOAD,
                                mapOf("fileName" to item.carRecording.fileName),
                            )
                            statusText = t("Download requested: ", "已请求下载 ") + item.carRecording.fileName
                        }
                    },
                    onPlay = {
                        item.file?.let {
                            playFile = it
                            playLabels = item.laneLabels
                        }
                    },
                    onShare = {
                        item.file?.let { shareFile(context, it) }
                    },
                    onSave = {
                        item.file?.let {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { saveToMovies(context, it) }
                                statusText = if (ok) t("Saved to gallery", "已保存到相册/视频") else t("Save failed", "保存失败")
                            }
                        }
                    },
                    onNote = {
                        item.file?.let {
                            noteTarget = it.name
                            noteText = loadNote(context, it.name)
                        }
                    },
                    onDelete = {
                        item.file?.let { deleteTarget = it }
                    },
                )
            }
        }
    }

    playFile?.let { file ->
        MediaPlaybackDialog(
            file = file,
            laneLabels = playLabels,
            onDismiss = { playFile = null },
        )
    }

    noteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("备注：$target") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveNote(context, target, noteText)
                    noteTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteTarget = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("Delete ", "删除 ") + file.name + "?") },
            text = { Text(t("The file will move to this app's trash.", "文件将移入本应用回收站，可稍后在设置中清理。")) },
            confirmButton = {
                TextButton(onClick = {
                    ReceivedStore.moveToTrash(file)
                    deleteTarget = null
                    ReceivedStore.refresh()
                }) { Text(t("Delete", "删除")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun MediaItemCard(
    item: MediaItem,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Medium)
                    Text(
                        buildString {
                            append(item.source)
                            append(" | ")
                            append(item.status)
                            if (item.carRecording?.protected == true) append(" | 已保护")
                            if (item.carRecording?.uploadPinned == true) append(" | 传输中")
                            append(" | ${formatBytes(item.sizeBytes)}")
                            item.startedAtEpochMs?.let { append(" | ${java.util.Date(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.carRecording != null && item.file == null) {
                    Button(onClick = onDownload) { Text(t("Download", "下载")) }
                } else if (item.file != null) {
                    Button(onClick = onPlay) { Text(t("Play", "播放")) }
                    OutlinedButton(onClick = onSave) { Text(t("Save", "保存到相册")) }
                    OutlinedButton(onClick = onShare) { Text(t("Share", "分享")) }
                }
                OutlinedButton(onClick = onNote) { Text(t("Note", "备注")) }
                if (item.file != null) {
                    OutlinedButton(onClick = onDelete) { Text(t("Delete", "删除")) }
                }
            }
        }
    }
}

private fun laneLabelsFor(file: File): List<String> {
    val sidecar = listOf(
        File(file.absolutePath + ".sidecar.json"),
        File(file.parentFile, file.nameWithoutExtension + ".json"),
    ).firstOrNull(File::isFile) ?: return emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(sidecar.readText())
            .jsonObject
        val layout = obj["laneLayout"]?.jsonObject ?: return emptyList()
        val lanes = layout["lanes"]?.jsonArray ?: return emptyList()
        lanes.mapNotNull { it.jsonObject["label"]?.jsonPrimitive?.content }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun queryLocalVideos(context: Context): List<File> {
    val results = mutableListOf<File>()
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    try {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx)
                if (path != null) {
                    File(path).takeIf { it.isFile }?.let { results += it }
                }
            }
        }
    } catch (t: Throwable) {
        ServerLog.log("MEDIA_QUERY_FAILED ${t.message}")
    }
    return results.take(200)
}

private fun shareFile(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.lowercase() == "mp4") "video/mp4" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享 ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(chooser, 0) != null) {
            context.startActivity(chooser)
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        ServerLog.log("SHARE_FAILED ${t.message}")
        false
    }
}

private fun saveToMovies(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return shareFile(context, file)
    var uri: Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Zeekr")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) return false
        val out = context.contentResolver.openOutputStream(uri) ?: run {
            context.contentResolver.delete(uri, null, null)
            return false
        }
        try {
            out.use { stream ->
                file.inputStream().use { it.copyTo(stream) }
            }
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            return false
        }
        val done = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, done, null, null)
        true
    } catch (t: Throwable) {
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        false
    }
}

private fun loadNote(context: Context, name: String): String =
    context.getSharedPreferences("media_notes", Context.MODE_PRIVATE).getString(name, "") ?: ""

private fun saveNote(context: Context, name: String, note: String) {
    context.getSharedPreferences("media_notes", Context.MODE_PRIVATE)
        .edit()
        .putString(name, note)
        .apply()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
