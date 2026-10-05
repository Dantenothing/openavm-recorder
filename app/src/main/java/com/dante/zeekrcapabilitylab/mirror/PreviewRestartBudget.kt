package com.dante.zeekrcapabilitylab.mirror

/** One clean reopen per explicit preview request, still subject to every handoff/release gate. */
internal class PreviewRestartBudget {
    private var spent = false
    fun newRequest() { spent = false }
    fun request(reason: String, allowed: Boolean): Boolean {
        if (!allowed || spent || reason !in setOf("PREVIEW_FIRST_FRAME_TIMEOUT", "PREVIEW_SOURCE_STALLED")) return false
        spent = true
        return true
    }
}
