package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_VISIBLE_LOG_EVENTS = 2_000

/** Read-only view of retained structured events. Loading disk history stays off the UI thread. */
@Composable
fun LogsDialog(onDismiss: () -> Unit) {
    val liveEvents by EventLogger.events.collectAsState()
    val storedEvents by produceState<List<ProbeEvent>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            EventLogger.readAllEvents().takeLast(MAX_VISIBLE_LOG_EVENTS)
        }
    }
    val events = remember(storedEvents, liveEvents) {
        (storedEvents.orEmpty() + liveEvents)
            .distinct()
            .sortedWith(
                compareByDescending<ProbeEvent> { it.epochMs }
                    .thenByDescending { it.sequence },
            )
            .take(MAX_VISIBLE_LOG_EVENTS)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Utils.t("Logs", "日志"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            Utils.t(
                                "Newest first · up to $MAX_VISIBLE_LOG_EVENTS events",
                                "最新事件优先 · 最多显示 $MAX_VISIBLE_LOG_EVENTS 条",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(Utils.t("Close", "关闭"))
                    }
                }

                Spacer(Modifier.height(10.dp))
                when {
                    storedEvents == null && events.isEmpty() -> {
                        Text(
                            Utils.t("Loading logs…", "正在加载日志…"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    events.isEmpty() -> {
                        Text(
                            Utils.t("No logs recorded yet.", "尚无日志。"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        SelectionContainer {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(
                                    items = events,
                                    key = { event ->
                                        "${event.epochMs}:${event.elapsedRealtimeMs}:${event.sequence}:${event.eventName}"
                                    },
                                ) { event ->
                                    LogEventRow(event)
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEventRow(event: ProbeEvent) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
    ) {
        Text(
            "${Utils.formatEpoch(event.epochMs)}  ${event.severity}  ${event.category}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (event.severity == Severity.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            event.eventName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        eventDetails(event)?.let { details ->
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Unspecified,
            )
        }
    }
}

private fun eventDetails(event: ProbeEvent): String? {
    val details = buildList {
        event.errorType?.takeIf(String::isNotBlank)?.let(::add)
        event.errorMessage?.takeIf(String::isNotBlank)?.let(::add)
        event.sourcePackage?.takeIf(String::isNotBlank)?.let { add("source=$it") }
        event.payload.toSortedMap().forEach { (key, value) -> add("$key=$value") }
    }
    return details.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}
