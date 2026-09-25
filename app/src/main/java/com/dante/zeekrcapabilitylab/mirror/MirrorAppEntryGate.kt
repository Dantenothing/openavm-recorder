package com.dante.zeekrcapabilitylab.mirror

/** One preview-only request per foreground visit to the home page. Never a recording permit. */
class MirrorAppEntryGate {
    private var consumed = false
    private var wasForeground = false

    fun request(foreground: Boolean, enabled: Boolean, resident: Boolean,
                cameraPermission: Boolean, overlayPermission: Boolean, calibratedRear: Boolean,
                surroundSelected: Boolean = true,
                recordingActive: Boolean = false, auxiliaryActive: Boolean = false,
                controlsActive: Boolean = false): Boolean {
        if (!foreground) { wasForeground = false; return false }
        if (!wasForeground) { consumed = false; wasForeground = true }
        if (consumed) return false
        if (recordingActive || auxiliaryActive || controlsActive || !surroundSelected) {
            consumed = true
            return false
        }
        // The service resolves the profile after admission. Waiting for the page's
        // asynchronous metadata lookup here would lose a quick App -> desktop visit.
        if (!cameraPermission) return false
        consumed = true
        return enabled && resident && overlayPermission && calibratedRear
    }
}
