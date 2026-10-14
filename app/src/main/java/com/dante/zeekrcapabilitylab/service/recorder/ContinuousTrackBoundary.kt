package com.dante.zeekrcapabilitylab.service.recorder

/** A queued boundary owns its first keyframe and prepared destination. Stop cannot revoke it.
 * Finish errors still prevent a new track; cancellation is allowed only before queue admission. */
internal fun <Summary : Any, Format : Any> finishContinuousTrackBoundary(
    finish: () -> Summary?,
    newFormat: Format?,
    applyFormat: (Format) -> Unit,
    done: (Summary) -> Unit,
): Boolean {
    val summary = finish() ?: return false
    if (newFormat != null) applyFormat(newFormat)
    done(summary)
    return true
}
