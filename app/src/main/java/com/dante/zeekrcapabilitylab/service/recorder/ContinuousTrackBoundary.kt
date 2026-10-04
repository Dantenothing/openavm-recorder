package com.dante.zeekrcapabilitylab.service.recorder

/** Writer-thread ordering shared by production and tests. No native calls or waiting in this helper.
 * The callback retains its existing asynchronous output-open path and ownership token checks. */
internal fun <Summary : Any, Format : Any> finishContinuousTrackBoundary(
    finish: () -> Summary?,
    canContinue: () -> Boolean,
    newFormat: Format?,
    applyFormat: (Format) -> Unit,
    done: (Summary) -> Unit,
): Boolean {
    val summary = finish() ?: return false
    if (!canContinue()) return false
    if (newFormat != null) applyFormat(newFormat)
    // Stop can revoke the request while finishing a slow muxer or applying its replacement format.
    if (!canContinue()) return false
    done(summary)
    return true
}
