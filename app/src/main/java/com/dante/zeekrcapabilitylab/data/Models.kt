package com.dante.zeekrcapabilitylab.data

import kotlinx.serialization.Serializable

@Serializable
data class DeviceSnapshot(
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val fingerprint: String,
    val supportedAbis: List<String>,
    val buildType: String,
    val buildTags: String,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
)

@Serializable
data class TestSession(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val deviceSnapshot: DeviceSnapshot? = null,
    val userNotes: String? = null,
    val endedAtEpochMs: Long? = null,
)

@Serializable
data class ProbeEvent(
    val sequence: Long,
    val sessionId: String,
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
    val category: String,
    val eventName: String,
    val severity: String,
    val sourcePackage: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class LifecycleEvent(
    val sequence: Long,
    val sessionId: String,
    val eventName: String,
    val elapsedRealtimeMs: Long,
    val epochMs: Long,
    val activityState: String,
    val processState: String,
    val hasWindowFocus: Boolean? = null,
    val notes: String? = null,
)

@Serializable
data class Heartbeat(
    val sessionId: String,
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
    val processStartId: String,
    val processId: Int,
    val activityState: String,
    val processForeground: Boolean,
    val foregroundServiceAlive: Boolean,
    val networkAvailable: Boolean,
)

@Serializable
data class MediaSessionSnapshot(
    val tokenHash: String,
    val packageName: String,
    val active: Boolean = true,
    val playbackState: Int? = null,
    val playbackPositionMs: Long? = null,
    val playbackSpeed: Float? = null,
    val supportedActions: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val mediaId: String? = null,
    val artworkAvailable: Boolean = false,
    val queueCount: Int? = null,
    val queueTitle: String? = null,
    val lastCallbackEpochMs: Long? = null,
    val lastCallbackName: String? = null,
)

@Serializable
data class TrackObservation(
    val packageName: String,
    val expectedTracks: Int,
    val observedChanges: Int = 0,
    val uniqueTrackKeys: Int = 0,
    val duplicateCallbacks: Int = 0,
    val missingTransitions: Int = 0,
    val averageCallbackDelayMs: Long = 0L,
    val longestCallbackGapMs: Long = 0L,
    val startedAtEpochMs: Long,
    val running: Boolean = true,
)

@Serializable
data class CrashRecord(
    val epochMs: Long,
    val thread: String,
    val exceptionType: String,
    val message: String? = null,
    val stackTrace: String,
)

@Serializable
data class NotificationEvent(
    val epochMs: Long,
    val eventType: String,
    val packageName: String,
    val keyHash: String,
    val isMedia: Boolean,
    val category: String? = null,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    val mediaSessionTokenHash: String? = null,
)

@Serializable
data class PersistenceMarker(
    val markerId: String,
    val createdAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
)

@Serializable
data class AutoTestResult(
    val id: String,
    val name: String,
    val category: String,
    val status: String,
    val details: Map<String, String> = emptyMap(),
    val epochMs: Long,
    val error: String? = null,
)

@Serializable
data class ManualTestItem(
    val id: String,
    val title: String,
    val titleZh: String,
    val group: String,
    val groupZh: String,
    val condition: String,
    val conditionZh: String,
    val instructions: String,
    val instructionsZh: String,
    val expected: String,
    val expectedZh: String,
    val status: String = ManualTestStatus.NOT_STARTED,
    val note: String? = null,
    val updatedAtEpochMs: Long? = null,
)

@Serializable
data class TestRunReport(
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val generatedAtEpochMs: Long,
    val sessionId: String,
    val device: DeviceSnapshot?,
    val autoTests: List<AutoTestResult>,
    val manualTests: List<ManualTestItem>,
    val events: List<ProbeEvent>,
    val heartbeats: List<Heartbeat>,
    val mediaSessions: List<MediaSessionSnapshot>,
    val permissions: Map<String, String>,
    val errors: List<ProbeEvent>,
)

object ManualTestStatus {
    const val NOT_STARTED = "NOT_STARTED"
    const val PASSED = "PASSED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
}

object Categories {
    const val APP = "APP"
    const val SYSTEM = "SYSTEM"
    const val LIFECYCLE = "LIFECYCLE"
    const val MANUAL_MARKER = "MANUAL_MARKER"
    const val AUTO_TEST = "AUTO_TEST"
    const val MANUAL_TEST = "MANUAL_TEST"
    const val PERMISSION = "PERMISSION"
    const val NOTIFICATION = "NOTIFICATION"
    const val MEDIA_SESSION = "MEDIA_SESSION"
    const val MEDIA_CONTROL = "MEDIA_CONTROL"
    const val BACKGROUND = "BACKGROUND"
    const val WORK_MANAGER = "WORK_MANAGER"
    const val ALARM = "ALARM"
    const val BOOT = "BOOT"
    const val NETWORK = "NETWORK"
    const val WEBSOCKET = "WEBSOCKET"
    const val INSTALL = "INSTALL"
    const val EXPORT = "EXPORT"
    const val ERROR = "ERROR"
}

object Severity {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
    const val SUCCESS = "SUCCESS"
}

object CapabilityStatus {
    const val PASS = "PASS"
    const val FAIL = "FAIL"
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val NOT_TESTED = "NOT_TESTED"
    const val INCONCLUSIVE = "INCONCLUSIVE"
}

object ManualMarkers {
    val ALL: List<String> = listOf(
        "PARKED",
        "DRIVE_SELECTED",
        "REVERSE_SELECTED",
        "MOVEMENT_STARTED",
        "MOVEMENT_STOPPED",
        "HOME_OPENED",
        "APP_REOPENED",
        "DOOR_OPENED",
        "DOOR_CLOSED",
        "VEHICLE_LOCKED",
        "VEHICLE_UNLOCKED",
        "SCREEN_OFF",
        "SCREEN_ON",
        "CAR_REBOOTED",
        "OEM_360_OPENED",
        "OEM_360_CLOSED",
        "CUSTOM_NOTE",
    )
}
