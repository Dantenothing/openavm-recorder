package com.dante.zeekrbridge.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.MediaIndexStore
import com.dante.zeekrbridge.core.TrashEntry
import com.dante.zeekrbridge.core.TrashStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val entries by TrashStore.entries.collectAsState()
    var deleteTarget by remember { mutableStateOf<TrashEntry?>(null) }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
            Column(Modifier.weight(1f)) {
                Text(t("Trash", "回收站"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    t("Items stay here until you restore or permanently delete them.", "录像会保留在这里，直到恢复或永久删除。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Text(t("Trash is empty", "回收站为空"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                entry.videoNames.firstOrNull() ?: t("Recording", "录像"),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                t(
                                    "{0} videos · {1} · deleted {2}", "{0} 个视频 · {1} · 删除于 {2}", entry.videoNames.size, formatTrashBytes(entry.sizeBytes), formatTrashDate(entry.deletedAtEpochMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val restored = TrashStore.restore(entry.id)
                                    MediaIndexStore.invalidate()
                                    message = t("Restored {0} videos", "已恢复 {0} 个视频", restored)
                                }) { Text(t("Restore", "恢复")) }
                                TextButton(onClick = { deleteTarget = entry }) {
                                    Text(t("Delete permanently", "永久删除"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("Delete permanently?", "永久删除？")) },
            text = { Text(t("These files cannot be recovered.", "这些文件将无法恢复。")) },
            confirmButton = {
                TextButton(onClick = {
                    TrashStore.deletePermanently(entry.id)
                    deleteTarget = null
                }) { Text(t("Delete", "删除")) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("Cancel", "取消")) } },
        )
    }
}

private fun formatTrashDate(epochMs: Long): String = if (epochMs <= 0L) "—" else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, PhoneLanguage.locale).format(Date(epochMs))

private fun formatTrashBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
