package com.dante.zeekrcapabilitylab.product

/**
 * Pure resolution of where the recording tree lives.
 *
 * The user can target a removable USB drive; recording must never become
 * impossible because of that choice, so an absent drive resolves to internal
 * storage with an explicit fallback flag the caller logs. The whole tree
 * (segments AND quarantine) lives under one root so every finalize/quarantine
 * rename stays on a single filesystem.
 */
object RecordingStorageTargetPolicy {

    const val TARGET_INTERNAL = "internal"
    const val TARGET_USB = "usb"

    /** Recording tree location inside the app-specific directory on the drive. */
    const val USB_RECORDINGS_SUBDIR = "AVMRecorder/recordings"

    data class Resolution(
        val recordingsRootPath: String?,
        val fellBackToInternal: Boolean,
    )

    fun resolve(target: String, usbRecordingsRootPath: String?): Resolution = when {
        target != TARGET_USB -> Resolution(recordingsRootPath = null, fellBackToInternal = false)
        usbRecordingsRootPath.isNullOrBlank() -> Resolution(recordingsRootPath = null, fellBackToInternal = true)
        else -> Resolution(recordingsRootPath = usbRecordingsRootPath, fellBackToInternal = false)
    }
}
