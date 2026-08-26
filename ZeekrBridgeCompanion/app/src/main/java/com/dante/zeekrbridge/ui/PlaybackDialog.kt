package com.dante.zeekrbridge.ui

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dante.zeekrbridge.player.FourLaneGlView
import java.io.File

/**
 * Playback dialog. For a four-lane composite it uses a single MediaPlayer +
 * GL crop (2x2 grid / single lane); ordinary videos use a standard VideoView.
 */
@Composable
fun MediaPlaybackDialog(
    file: File,
    laneLabels: List<String>,
    onDismiss: () -> Unit,
) {
    if (laneLabels.size == 4) {
        FourLanePlaybackDialog(file, laneLabels, onDismiss)
    } else {
        PlainVideoDialog(file, onDismiss)
    }
}

@Composable
private fun FourLanePlaybackDialog(
    file: File,
    labels: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glView = remember { FourLaneGlView(context) }
    var mode by remember { mutableIntStateOf(FourLaneGlView.MODE_GRID) }
    var playing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(file) {
        var playerRef: MediaPlayer? = null
        var stRef: SurfaceTexture? = null
        var surfaceRef: Surface? = null
        glView.createSurfaceTexture { st ->
            stRef = st
            try {
                val meta = MediaMetadataRetriever()
                try {
                    meta.setDataSource(file.absolutePath)
                    val w = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        st.setDefaultBufferSize(w, h)
                        glView.setVideoSize(w, h)
                    }
                } catch (t: Throwable) {
                    // Ignore.
                } finally {
                    try {
                        meta.release()
                    } catch (t: Throwable) {
                        // Ignore.
                    }
                }
                val mp = MediaPlayer()
                playerRef = mp
                val surface = Surface(st)
                surfaceRef = surface
                mp.setSurface(surface)
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener {
                    glView.setSource(st)
                    glView.setMode(FourLaneGlView.MODE_GRID)
                    glView.requestRender()
                    mp.start()
                    playing = true
                }
                mp.setOnErrorListener { _, _, _ ->
                    error = "无法解码该复合录像"
                    playing = false
                    true
                }
                mp.prepareAsync()
                player = mp
            } catch (t: Throwable) {
                error = "播放失败：${t.message ?: t.javaClass.simpleName}"
                try {
                    playerRef?.release()
                } catch (t2: Throwable) {
                    // Ignore.
                }
            }
        }
        onDispose {
            try {
                playerRef?.stop()
            } catch (t: Throwable) {
                // Ignore.
            }
            try {
                playerRef?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
            try {
                surfaceRef?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
            try {
                stRef?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
            glView.onPause()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (mode == FourLaneGlView.MODE_GRID) 2f else 1.6f),
                ) {
                    AndroidView(
                        factory = { glView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                error?.let {
                    Text(it, color = Color(0xFFEF5350))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = {
                        mode = FourLaneGlView.MODE_GRID
                        glView.setMode(mode)
                        glView.requestRender()
                    }) { Text("2×2") }
                    (0..3).forEach { index ->
                        OutlinedButton(onClick = {
                            mode = FourLaneGlView.MODE_LANE_1 + index
                            glView.setMode(mode)
                            glView.requestRender()
                        }) {
                            Text(labels.getOrElse(index) { "视角${index + 1}" })
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = {
                        val mp = player
                        if (mp != null) {
                            if (mp.isPlaying) {
                                mp.pause()
                                playing = false
                            } else {
                                mp.start()
                                playing = true
                            }
                        }
                    }) {
                        Text(if (playing) "暂停" else "继续")
                    }
                    Text(
                        "原始单文件四路（${file.length() / (1024L * 1024L)} MB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PlainVideoDialog(file: File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).also { view ->
                        val controls = MediaController(ctx)
                        controls.setAnchorView(view)
                        view.setMediaController(controls)
                        view.setVideoPath(file.absolutePath)
                        view.setOnPreparedListener { view.start() }
                        view.requestFocus()
                    }
                },
                modifier = Modifier
                    .width(360.dp)
                    .height(220.dp),
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
