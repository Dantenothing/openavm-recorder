package com.dante.zeekrcapabilitylab.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File

/** One decoder pipeline and one output for an entire recording, including minute boundaries. */
@androidx.annotation.OptIn(UnstableApi::class)
class RecordingPlaylistPlayer(
    context: Context,
    files: List<File>,
    textureView: TextureView,
    val timeline: PlaybackTimeline,
    private val onPrepared: (Long) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onCompleted: () -> Unit,
    private val onError: () -> Unit,
    private val onVideoFormat: (Int, Int, Int) -> Boolean = { _, _, _ -> true },
) {
    private var closed = false
    private var preparedReported = false
    private val openedAt = SystemClock.elapsedRealtime()
    private var firstFrameReported = false
    private val engine = ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 6_000, 100, 250)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build())
        .build()
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(value: Timeline, reason: Int) {
            val window = Timeline.Window()
            repeat(value.windowCount) { index -> timeline.confirm(index, value.getWindow(index, window).durationMs) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (closed) return
            if (state == Player.STATE_READY && !preparedReported) {
                preparedReported = true
                onPrepared(timeline.total())
            }
            if (state == Player.STATE_ENDED) onCompleted()
        }
        override fun onRenderedFirstFrame() {
            if (closed) return
            if (!validateRaster()) return
            if (!firstFrameReported) {
                firstFrameReported = true
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_PLAYBACK_FIRST_FRAME", payload = mapOf(
                    "elapsedMs" to (SystemClock.elapsedRealtime() - openedAt).toString()))
            }
            onFirstFrame()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) { if (!closed) validateRaster() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!closed) validateRaster()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (closed) return
            engine.pause()
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_PLAYBACK_ERROR",
                payload = mapOf("reason" to error.errorCodeName))
            onError()
        }
    }
    private fun validateRaster(): Boolean {
        val size = engine.videoSize
        if (size.width <= 0 || size.height <= 0) return true
        if (onVideoFormat(segmentIndex(), size.width, size.height)) return true
        engine.pause()
        onError()
        return false
    }
    init {
        try {
            require(files.isNotEmpty())
            engine.addListener(listener)
            engine.repeatMode = Player.REPEAT_MODE_OFF
            // Media3 owns its output wrapper and tracks TextureView attach/detach.
            engine.setVideoTextureView(textureView)
            engine.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            engine.prepare()
            engine.play()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }
    fun isPlaying(): Boolean = !closed && engine.isPlaying
    fun segmentIndex(): Int = if (closed) 0 else engine.currentMediaItemIndex.coerceAtLeast(0)
    fun currentPosition(): Long = if (closed) 0L else timeline.position(segmentIndex(), engine.currentPosition)
    fun toggle(): Boolean {
        if (closed) return false
        if (engine.playWhenReady && engine.playbackState != Player.STATE_ENDED) {
            engine.pause(); return false
        }
        if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0, 0L)
        engine.play(); return true
    }
    fun seekGlobal(ms: Long) { timeline.seek(ms)?.let { seekSegment(it.index, it.positionMs) } }
    fun seekSegment(index: Int, positionMs: Long = 0) {
        if (!closed && index in 0 until engine.mediaItemCount) {
            val duration = timeline.durations()[index]
            engine.seekTo(index, if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L))
        }
    }
    fun close() {
        if (closed) return
        closed = true
        engine.removeListener(listener)
        engine.release()
    }
}
