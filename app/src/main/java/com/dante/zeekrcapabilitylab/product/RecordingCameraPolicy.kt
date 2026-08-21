package com.dante.zeekrcapabilitylab.product

/**
 * Pure selection rules for which camera drives recording and the standby
 * preview.
 *
 * Rules (covered by JVM tests):
 *  - [AUTO] keeps the legacy behaviour: camera [LEGACY_DEFAULT_CAMERA_ID] when
 *    it is usable, otherwise the first usable camera.
 *  - An explicit selection is honoured only while that camera is still usable;
 *    otherwise the choice falls back to the auto rule and says so, so callers
 *    can log the fallback instead of failing or silently ignoring it.
 *  - [usableIds] must preserve CameraManager order; the policy never invents
 *    ids that are not in it.
 */
object RecordingCameraPolicy {

    /** Sentinel stored in settings meaning "let the app pick". */
    const val AUTO = "auto"

    /** The id the known Zeekr units expose the AVM surround composite on. */
    const val LEGACY_DEFAULT_CAMERA_ID = "2"

    data class Selection(
        val cameraId: String,
        val fallbackFromRequested: Boolean,
    )

    fun choose(requestedId: String, usableIds: List<String>): Selection? {
        if (usableIds.isEmpty()) return null
        if (requestedId != AUTO && requestedId in usableIds) {
            return Selection(cameraId = requestedId, fallbackFromRequested = false)
        }
        val auto = usableIds.firstOrNull { it == LEGACY_DEFAULT_CAMERA_ID } ?: usableIds.first()
        return Selection(cameraId = auto, fallbackFromRequested = requestedId != AUTO)
    }
}
