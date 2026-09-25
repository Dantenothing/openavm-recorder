package com.dante.zeekrcapabilitylab.mirror

enum class MirrorReturnMode {
    OFF, LOGO, PREVIEW, RECORD;
    val retained get() = this != OFF
    val automatic get() = this == PREVIEW || this == RECORD
}

internal data class MirrorReturnChoice(val mode: MirrorReturnMode, val revision: Long, val confirmed: Boolean)

internal object MirrorReturnPromptPolicy {
    fun needsReview(enabled: Boolean, currentInstallation: String?, acceptedInstallation: String?): Boolean =
        enabled && (currentInstallation.isNullOrBlank() || currentInstallation != acceptedInstallation)

    fun effectiveMode(enabled: Boolean, selected: MirrorReturnMode, needsReview: Boolean): MirrorReturnMode = when {
        !enabled -> MirrorReturnMode.OFF
        needsReview && selected.automatic -> MirrorReturnMode.LOGO
        else -> selected
    }
}

/** One opt-in action per observed dark -> usable-screen cycle. Never a boot/startup permit. */
internal class MirrorReturnGate(private val stableMs: Long = 1_500, private val waitMs: Long = 30_000) {
    enum class Decision { NONE, WAIT, PREVIEW, RECORD, FOLLOW_EXISTING, TIMED_OUT, SETTINGS_CHANGED }
    private var armed: MirrorReturnChoice? = null
    private var usableSince: Long? = null

    fun arm(choice: MirrorReturnChoice, screenUsable: Boolean) {
        // SCREEN_OFF broadcasts can arrive after the real screen-on edge. Only a fresh
        // unavailable-screen observation may mint another one-shot return opportunity.
        if (screenUsable) return
        armed = choice.takeIf { it.confirmed && it.mode.automatic }
        usableSince = null
    }
    fun cancel() { armed = null; usableSince = null }

    fun poll(now: Long, screenUsable: Boolean, attached: Boolean, resources: MirrorSleepResources,
             current: MirrorReturnChoice, sameRecordingContinues: Boolean = false): Decision {
        val choice = armed ?: return Decision.NONE
        if (!current.confirmed || choice != current) { cancel(); return Decision.SETTINGS_CHANGED }
        if (!screenUsable) { usableSince = null; return Decision.WAIT }
        val since = usableSince ?: now.also { usableSince = it }
        if (now < since) { cancel(); return Decision.TIMED_OUT }
        if (now - since >= waitMs) { cancel(); return Decision.TIMED_OUT }
        if (now - since < stableMs || !attached || !resources.released && !sameRecordingContinues) return Decision.WAIT
        cancel() // Consume before dispatch: failure/duplicate broadcasts must not retry recording.
        if (sameRecordingContinues) return Decision.FOLLOW_EXISTING
        return if (choice.mode == MirrorReturnMode.RECORD) Decision.RECORD else Decision.PREVIEW
    }
}

internal object MirrorExistingRecording {
    fun canRestore(expectedSession: String?, currentSession: String?, running: Boolean, recording: Boolean,
                   mirrorManaged: Boolean, cleanupUnconfirmed: Boolean, hasError: Boolean): Boolean =
        !expectedSession.isNullOrBlank() && expectedSession == currentSession && running && recording &&
            mirrorManaged && !cleanupUnconfirmed && !hasError
}
