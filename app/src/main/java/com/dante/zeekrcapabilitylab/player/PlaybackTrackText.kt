package com.dante.zeekrcapabilitylab.player

import java.util.Locale
import kotlin.math.abs

object PlaybackTrackText {
    fun bitrate(bitrateBps: Long?): String = if (bitrateBps == null || bitrateBps <= 0L) {
        "—"
    } else {
        String.format(Locale.US, "%.1f Mbps", bitrateBps / 1_000_000.0)
    }

    fun frameRate(frameRateFps: Float?): String = if (frameRateFps == null || frameRateFps <= 0f) {
        "—"
    } else if (abs(frameRateFps - frameRateFps.toInt()) < 0.005f) {
        "${frameRateFps.toInt()} fps"
    } else {
        String.format(Locale.US, "%.2f fps", frameRateFps)
    }
}
