package com.dante.zeekrbridge.ui

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dante.zeekrbridge.core.IndexedLayoutKind
import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedSourceRole
import com.dante.zeekrbridge.core.IndexedLane
import com.dante.zeekrbridge.core.MediaIndexScanner
import com.dante.zeekrbridge.core.ContinuousRasterSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import com.dante.zeekrbridge.player.FourLaneGlView
import com.dante.zeekrbridge.player.FourLaneLensMode
import com.dante.zeekrbridge.player.FourLaneVideoSizeListener
import com.dante.zeekrbridge.player.VideoDisplayGeometry
import com.dante.zeekrbridge.player.canPreparePlayback
import java.io.File
import java.text.DateFormat
import java.util.Date

internal data class PlaybackEntry(
    val id: String,
    val uri: Uri,
    val readable: Boolean,
    val durationMs: Long,
    val sourceRole: IndexedSourceRole,
    val layoutKind: IndexedLayoutKind,
    val laneLabels: List<String>,
    val laneOrder: List<Int>,
    val originalWidth: Int?,
    val originalHeight: Int?,
    val raster: RecordingRasterMetadata = RecordingRasterMetadata.Original,
    val lanes: List<IndexedLane> = emptyList(),
)

@Composable
fun MediaPlaybackDialog(
    file: File,
    laneLabels: List<String>,
    laneOrder: List<Int> = if (laneLabels.size == 4) listOf(1, 2, 3, 4) else emptyList(),
    onDismiss: () -> Unit,
) {
    val indexed by produceState<Pair<String, IndexedMediaSegment>?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { file.absolutePath to MediaIndexScanner.readSegment(file) }
    }
    val segment = indexed?.takeIf { it.first == file.absolutePath }?.second ?: return
    val hasRaster = segment.raster != RecordingRasterMetadata.Original || segment.layoutKind != IndexedLayoutKind.UNKNOWN
    PlaylistPlaybackDialog(
        title = file.name,
        entries = listOf(
            PlaybackEntry(
                id = file.absolutePath,
                uri = Uri.fromFile(file),
                readable = file.isFile,
                durationMs = 0L,
                sourceRole = if (hasRaster) segment.sourceRole else IndexedSourceRole.UNKNOWN,
                layoutKind = if (hasRaster) segment.layoutKind else if (laneLabels.size == 4) IndexedLayoutKind.FOUR_LANE_V1 else IndexedLayoutKind.UNKNOWN,
                laneLabels = if (hasRaster) segment.playbackLabels else laneLabels,
                laneOrder = if (hasRaster) segment.playbackLaneOrder else laneOrder,
                originalWidth = segment.originalWidth,
                originalHeight = segment.originalHeight,
                raster = segment.raster, lanes = segment.lanes,
            ),
        ),
        initialIndex = 0,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun MediaSessionPlayer(
    segments: List<IndexedMediaSegment>,
    seekRequest: com.dante.zeekrbridge.player.PlaybackSeekRequest?,
    obstructed: Boolean,
    onActiveMediaChanged: (String) -> Unit,
) {
    val entries = remember(segments) {
        segments.map { segment ->
            PlaybackEntry(
                id = segment.id, uri = Uri.fromFile(segment.file), readable = segment.file.isFile,
                durationMs = segment.durationMs, sourceRole = segment.sourceRole,
                layoutKind = segment.layoutKind, laneLabels = segment.playbackLabels,
                laneOrder = segment.playbackLaneOrder, originalWidth = segment.originalWidth,
                originalHeight = segment.originalHeight,
                raster = segment.raster, lanes = segment.lanes,
            )
        }
    }
    InlineMediaPlayer(entries = entries, seekRequest = seekRequest, obstructed = obstructed,
        onActiveMediaChanged = onActiveMediaChanged)
}

/** Plays a factory Sentry recording directly from a user-authorized USB tree. */
@Composable
fun UsbSentryPlaybackDialog(
    uri: Uri,
    displayName: String,
    durationMs: Long,
    layoutKind: IndexedLayoutKind,
    laneLabels: List<String>,
    laneOrder: List<Int>,
    originalWidth: Int?,
    originalHeight: Int?,
    onDismiss: () -> Unit,
) {
    UriMediaPlaybackDialog(
        uri = uri,
        displayName = displayName,
        durationMs = durationMs,
        sourceRole = IndexedSourceRole.SURROUND,
        layoutKind = layoutKind,
        laneLabels = laneLabels,
        laneOrder = laneOrder,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        onDismiss = onDismiss,
    )
}

/** Plays an app-owned MediaStore item without relying on the deprecated DATA column. */
@Composable
fun UriMediaPlaybackDialog(
    uri: Uri,
    displayName: String,
    durationMs: Long,
    sourceRole: IndexedSourceRole = IndexedSourceRole.UNKNOWN,
    layoutKind: IndexedLayoutKind = IndexedLayoutKind.UNKNOWN,
    laneLabels: List<String> = emptyList(),
    laneOrder: List<Int> = emptyList(),
    originalWidth: Int? = null,
    originalHeight: Int? = null,
    raster: RecordingRasterMetadata = RecordingRasterMetadata.Original,
    lanes: List<IndexedLane> = emptyList(),
    onDismiss: () -> Unit,
) {
    PlaylistPlaybackDialog(
        title = displayName,
        entries = listOf(
            PlaybackEntry(
                id = uri.toString(),
                uri = uri,
                readable = true,
                durationMs = durationMs,
                sourceRole = sourceRole,
                layoutKind = layoutKind,
                laneLabels = laneLabels,
                laneOrder = laneOrder,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                raster = raster, lanes = lanes,
            ),
        ),
        initialIndex = 0,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PlaylistPlaybackDialog(
    title: String,
    entries: List<PlaybackEntry>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
                    Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    InlineMediaPlayer(entries, initialIndex)
                }
            }
        }
    }
}

@Composable
internal fun FourLaneVideoSurface(
    player: ExoPlayer,
    entry: PlaybackEntry,
    mode: Int,
    lensMode: FourLaneLensMode,
    onModeChanged: (Int) -> Unit,
    onFirstFrame: () -> Unit,
    onSurfaceReady: () -> Unit,
    onRenderError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val glView = remember { FourLaneGlView(context) }
    val currentOnModeChanged by rememberUpdatedState(onModeChanged)
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentOnSurfaceReady by rememberUpdatedState(onSurfaceReady)
    val currentOnRenderError by rememberUpdatedState(onRenderError)
    val detectedSize by produceState<Pair<PlaybackEntry, Pair<Int, Int>>?>(null, entry) {
        value = withContext(Dispatchers.IO) { entry to readVideoSize(context, entry) }
    }
    val size = detectedSize?.takeIf { it.first == entry }?.second
    LaunchedEffect(mode, lensMode, entry.laneOrder) {
        glView.setMode(mode, entry.laneOrder)
        glView.setLensMode(lensMode)
        glView.requestRender()
    }
    DisposableEffect(player, glView, entry, size) {
        if (size == null) return@DisposableEffect onDispose { }
        var disposed = false
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        glView.setPlaybackRaster(entry.raster, entry.lanes)
        val formatListener = FourLaneVideoSizeListener(
            raster = entry.raster,
            layout = entry.layoutKind,
            currentVideoSize = { player.videoSize },
            onValidSize = glView::setVideoSize,
            onInvalidSize = { issue ->
                player.pause()
                glView.setPlaybackRaster(RecordingRasterMetadata.Rejected(issue), entry.lanes)
                currentOnRenderError(issue)
            },
        )
        player.addListener(formatListener)
        glView.setModeChangedCallback { selectedMode -> currentOnModeChanged(selectedMode) }
        glView.setPlaybackCallbacks(
            onFirstFrame = { currentOnFirstFrame() },
            onSurfaceFrame = null,
            onGlMaxTextureSize = null,
            onGlDiagnostic = null,
            onRenderError = { message -> currentOnRenderError(message) },
        )
        glView.createSurfaceTexture { created ->
            if (disposed) {
                created.release()
                return@createSurfaceTexture
            }
            texture = created
            ContinuousRasterSupport.trackError(entry.raster, entry.layoutKind, size.first, size.second)?.let {
                currentOnRenderError(it)
                glView.setPlaybackRaster(RecordingRasterMetadata.Rejected(it), entry.lanes)
                return@createSurfaceTexture
            }
            if (size.first > 0 && size.second > 0) {
                created.setDefaultBufferSize(size.first, size.second)
                glView.setVideoSize(size.first, size.second)
            }
            val nextSurface = Surface(created)
            surface = nextSurface
            glView.setSource(created)
            player.setVideoSurface(nextSurface)
            currentOnSurfaceReady()
            if (
                canPreparePlayback(entry.layoutKind, customSurfaceAttached = true) &&
                player.playbackState == Player.STATE_IDLE &&
                player.mediaItemCount > 0
            ) {
                player.prepare()
            }
        }
        onDispose {
            disposed = true
            player.removeListener(formatListener)
            glView.setModeChangedCallback(null)
            glView.setPlaybackCallbacks(null, null, null, null, null)
            surface?.let { runCatching { player.clearVideoSurface(it) } }
            glView.setSource(null)
            surface?.release()
            texture?.release()
            glView.onPause()
        }
    }
    AndroidView(factory = { glView }, modifier = modifier.testTag("recorder_video_surface"))
}

@Composable
internal fun PlainVideoSurface(player: ExoPlayer, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { SurfaceView(context) }
    DisposableEffect(player, view) {
        player.setVideoSurfaceView(view)
        onDispose { player.clearVideoSurfaceView(view) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

internal fun readDisplayGeometry(context: android.content.Context, entry: PlaybackEntry): VideoDisplayGeometry? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, entry.uri)
        VideoDisplayGeometry(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            metadataRotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
        )
    } catch (_: Exception) {
        VideoDisplayGeometry(entry.originalWidth ?: 0, entry.originalHeight ?: 0)
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun resolvedDurations(
    entries: List<PlaybackEntry>,
    player: ExoPlayer,
    playerIndex: Int,
): List<Long> = entries.mapIndexed { index, entry ->
    when {
        index == playerIndex && player.duration > 0L -> player.duration
        entry.durationMs > 0L -> entry.durationMs
        else -> 0L
    }
}

private fun readVideoSize(context: android.content.Context, entry: PlaybackEntry): Pair<Int, Int> {
    if (entry.raster == RecordingRasterMetadata.Original && !entry.uri.scheme.equals("file", ignoreCase = true)) {
        val known = (entry.originalWidth ?: 0) to (entry.originalHeight ?: 0)
        if (known.first > 0 && known.second > 0) return known
    }
    val retriever = MediaMetadataRetriever()
    val detected = try {
        if (entry.uri.scheme.equals("file", ignoreCase = true)) {
            retriever.setDataSource(entry.uri.path)
        } else {
            retriever.setDataSource(context, entry.uri)
        }
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (entry.raster is RecordingRasterMetadata.Repacked && rotation % 360 != 0) return 0 to 0
        width to height
    } catch (_: Throwable) {
        0 to 0
    } finally {
        runCatching { retriever.release() }
    }
    return detected.takeIf { it.first > 0 && it.second > 0 }
        ?: if (entry.raster is RecordingRasterMetadata.Repacked) (0 to 0)
        else ((entry.originalWidth ?: 0) to (entry.originalHeight ?: 0))
}

internal fun formatPlaybackRange(segments: List<IndexedMediaSegment>): String {
    if (segments.isEmpty()) return "—"
    val start = segments.minOf { it.startedAtEpochMs }
    val end = segments.maxOf { segment ->
        segment.stoppedAtEpochMs
            ?: (segment.startedAtEpochMs + segment.durationMs.coerceAtLeast(0L))
    }.coerceAtLeast(start)
    val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT, PhoneLanguage.locale)
    val sameDay = dateFormat.format(Date(start)) == dateFormat.format(Date(end))
    val formatter = if (sameDay) {
        DateFormat.getTimeInstance(DateFormat.SHORT, PhoneLanguage.locale)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, PhoneLanguage.locale)
    }
    return "${formatter.format(Date(start))}–${formatter.format(Date(end))}"
}

internal fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
