package com.dante.zeekrbridge.player

import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.dante.zeekrbridge.core.ContinuousRasterSupport
import com.dante.zeekrbridge.core.IndexedLayoutKind
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata

/** Validates decoder size events without replacing the surface used by the playlist. */
internal class FourLaneVideoSizeListener(
    private val raster: RecordingRasterMetadata,
    private val layout: IndexedLayoutKind,
    private val currentVideoSize: () -> VideoSize,
    private val onValidSize: (Int, Int) -> Unit,
    private val onInvalidSize: (String) -> Unit,
) : Player.Listener {
    override fun onVideoSizeChanged(videoSize: VideoSize) {
        // Media3 uses zero for an unknown dimension, including while a seek resets the
        // decoder. Keep the preflight/last valid size until a concrete size arrives.
        // This applies only to runtime events; file geometry validation remains strict.
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val issue = ContinuousRasterSupport.trackError(raster, layout, videoSize.width, videoSize.height)
        if (issue != null) onInvalidSize(issue)
        else onValidSize(videoSize.width, videoSize.height)
    }

    override fun onRenderedFirstFrame() = onVideoSizeChanged(currentVideoSize())
}
