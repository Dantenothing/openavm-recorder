package com.dante.zeekrcapabilitylab.diagnostic

/** Drives existing product controls. Owns no camera, encoder, thread, or cleanup permit. */
internal class ProductTrialEngine(private val started: Long, private val host: Host,
                                  private val recordingMs: Long = 75_000, private val previewOnly: Boolean = false) {
    data class Sample(val idle: Boolean = false, val previewFresh: Boolean = false, val previewFrames: Long = 0,
                      val recording: Boolean = false, val allChannelsWriting: Boolean = false,
                      val writtenFrames: Long = 0, val error: String? = null, val online: Boolean = true)
    interface Host { fun sample(): Sample; fun preview(); fun record(); fun stop(); fun close() }
    var phase = "RELEASING"; private set
    var reason = ""; private set
    var outcome = "PENDING"; private set
    var cleanup = "PENDING"; private set
    var terminal = false; private set
    var completedRecordings = 0; private set
    private var entered = started
    private var freshAt: Long? = null
    private var freshFrames = -1L
    private var writeAt = started
    private var written = 0L
    init { require(recordingMs in 2_000..780_000); host.close() }
    private fun enter(next: String, now: Long) { phase = next; entered = now; freshAt = null; freshFrames = -1 }
    fun cancel(now: Long, cause: String = "USER_CANCELLED") = end(now, "CANCELLED", cause)
    private fun end(now: Long, result: String, cause: String) {
        if (terminal || phase == "FINISHING") return
        reason = cause; outcome = result; enter("FINISHING", now); host.close()
    }
    fun tick(now: Long) {
        if (terminal) return
        val s = host.sample()
        if (phase == "FINISHING") {
            if (s.idle || now - entered >= 65_000) {
                cleanup = if (s.idle) "CONFIRMED" else "UNCONFIRMED"
                if (!s.idle) outcome = "INCONCLUSIVE"
                terminal = true; phase = "FINISHED"
            }
            return
        }
        if (now - started > 1_400_000) { end(now, "INCONCLUSIVE", "TRIAL_DEADLINE"); return }
        if (s.error != null) { end(now, "FAIL", s.error); return }
        when (phase) {
            "RELEASING" -> if (s.idle) {
                if (!s.online) { end(now, "CANCELLED", "CONNECTION_LOST"); return }
                enter("PREVIEW", now); host.preview()
            } else if (now - entered >= 65_000) end(now, "INCONCLUSIVE", "INITIAL_RELEASE_UNCONFIRMED")
            "PREVIEW", "RETURN_PREVIEW" -> {
                if (s.previewFresh && !s.recording) {
                    if (freshAt == null) { freshAt = now; freshFrames = s.previewFrames }
                    if (now - requireNotNull(freshAt) >= (if (previewOnly) 30_000 else 2_000) && s.previewFrames > freshFrames) {
                        if (previewOnly || completedRecordings == 2) end(now, "PASS", "PRODUCT_SEQUENCE_COMPLETED")
                        else if (!s.online) end(now, "CANCELLED", "CONNECTION_LOST")
                        else { enter("WAIT_WRITING", now); host.record() }
                    }
                } else { freshAt = null; freshFrames = -1 }
                if (phase in setOf("PREVIEW", "RETURN_PREVIEW") && now - entered >= 65_000)
                    end(now, "FAIL", if (phase == "PREVIEW") "PRODUCT_PREVIEW_NO_FRESH_FRAME" else "PRODUCT_STOP_PREVIEW_NO_FRESH_FRAME")
            }
            "WAIT_WRITING" -> if (s.recording && s.allChannelsWriting && s.writtenFrames > 0) {
                enter("RECORDING", now); written = s.writtenFrames; writeAt = now
            } else if (now - entered >= 35_000) end(now, "FAIL", "PRODUCT_CHANNEL_START_TIMEOUT")
            "RECORDING" -> {
                if (!s.recording || !s.allChannelsWriting) { end(now, "FAIL", "PRODUCT_RECORDING_INTERRUPTED"); return }
                if (s.writtenFrames > written) { written = s.writtenFrames; writeAt = now }
                if (now - writeAt > 6_000) { end(now, "FAIL", "PRODUCT_WRITE_PROGRESS_STALLED"); return }
                val duration = if (completedRecordings == 0) recordingMs else 75_000L.coerceAtMost(recordingMs)
                if (now - entered >= duration) { completedRecordings++; enter("RETURN_PREVIEW", now); host.stop() }
            }
        }
    }
}
