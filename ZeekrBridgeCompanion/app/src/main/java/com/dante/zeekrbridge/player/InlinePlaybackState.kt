package com.dante.zeekrbridge.player

/** Preserve the user's intent while another window or the background temporarily stops playback. */
data class InlinePlaybackIntent(
    val wantsToPlay: Boolean = true,
    val foreground: Boolean = true,
    val obstructed: Boolean = false,
) {
    val shouldPlay: Boolean get() = wantsToPlay && foreground && !obstructed
}

/** The sequence allows tapping the current segment to restart it without rebuilding the player. */
data class PlaybackSeekRequest(val mediaId: String, val sequence: Int)

fun playableIndexForId(ids: List<String>, requestedId: String?): Int? =
    ids.indexOf(requestedId).takeIf { it >= 0 }

data class VideoFrameSize(val width: Float, val height: Float)

/** Fit the actual video, allowing a landscape recording to use less vertical space than a square. */
fun fitVideoFrame(availableWidth: Float, maximumHeight: Float, aspectRatio: Float?): VideoFrameSize {
    if (!availableWidth.isFinite() || !maximumHeight.isFinite() || availableWidth <= 0 || maximumHeight <= 0) {
        return VideoFrameSize(0f, 0f)
    }
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = minOf(availableWidth / ratio, maximumHeight)
    return VideoFrameSize(minOf(height * ratio, availableWidth), height)
}
