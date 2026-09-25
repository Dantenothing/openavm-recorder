package com.dante.zeekrcapabilitylab.diagnostic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Application-scope power/lifecycle monitor and persistent evidence probe.
 * It owns no WakeLock and never mutates Camera resources directly; active-session
 * decisions are forwarded to RecorderSession and serialized on its Camera thread.
 */
object VehicleAwayProbe {
    const val TEST_NORMAL_BACKGROUND = "NORMAL_BACKGROUND"
    const val TEST_OEM_CAMERA = "OEM_CAMERA"
    const val TEST_VEHICLE_AWAY = "VEHICLE_AWAY"
    private lateinit var context: Context
    private lateinit var scheduler: ScheduledExecutorService
    private var lastIncidentId: String = "startup"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> recordPowerEdge("SCREEN_ON", delayed = false)
                Intent.ACTION_SCREEN_OFF -> recordPowerEdge("SCREEN_OFF", delayed = true)
                Intent.ACTION_SHUTDOWN -> recordPowerEdge("SYSTEM_SHUTDOWN", delayed = false)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            snapshot("DISPLAY_ADDED", displayId.toString())
            refreshPowerSnapshot("DISPLAY_ADDED")
        }

        override fun onDisplayRemoved(displayId: Int) {
            snapshot("DISPLAY_REMOVED", displayId.toString())
            refreshPowerSnapshot("DISPLAY_REMOVED")
        }

        override fun onDisplayChanged(displayId: Int) {
            snapshot("DISPLAY_CHANGED", displayId.toString())
            if (displayId == Display.DEFAULT_DISPLAY) {
                refreshPowerSnapshot("DISPLAY_CHANGED")
            }
        }
    }

    fun init(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        AwayJournal.init(context)
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "vehicle-away-probe").apply { isDaemon = true }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.getSystemService(DisplayManager::class.java)?.let { displayManager ->
            displayManager.registerDisplayListener(displayListener, null)
        }
        recordCapabilities()
        snapshot("PROBE_INITIALIZED")
        refreshPowerSnapshot("PROBE_INITIALIZED")
        scheduler.scheduleWithFixedDelay({
            if (AwayJournal.observing || CameraRecordingService.isRunning() ||
                com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.isRunning()) {
                snapshot("POWER_PASSIVE_SNAPSHOT")
            }
        }, 60, 60, TimeUnit.SECONDS)
    }

    fun recordAppState(foreground: Boolean) {
        val event = if (foreground) "APP_FOREGROUND" else "APP_BACKGROUND"
        snapshot(event)
        refreshPowerSnapshot(event)
        if (!foreground) scheduleSnapshots(event)
    }

    fun recordActivityState(state: String) = snapshot("ACTIVITY_$state")

    fun startTestMarker(testType: String = "GENERAL"): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        lastIncidentId = id
        snapshot("VEHICLE_AWAY_TEST_MARKER", id, testType = testType)
        scheduler.execute { EventLogger.flushBlocking(1_500L) }
        return id
    }

    private fun recordPowerEdge(event: String, delayed: Boolean) {
        snapshot(event)
        refreshPowerSnapshot(event)
        if (delayed) scheduleSnapshots(event)
        scheduler.execute { EventLogger.flushBlocking(1_500L) }
    }

    private fun scheduleSnapshots(trigger: String) {
        val incident = "${trigger.take(3)}-${System.currentTimeMillis().toString().takeLast(6)}"
        lastIncidentId = incident
        listOf(5L, 30L, 120L).forEach { delaySeconds ->
            scheduler.schedule(
                {
                    snapshot("POWER_DELAYED_SNAPSHOT", "$trigger+$delaySeconds", incident)
                    refreshPowerSnapshot("${trigger}_DELAYED_$delaySeconds")
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun refreshPowerSnapshot(source: String) {
        if (!::context.isInitialized) return
        CameraRecordingService.refreshVehiclePowerSnapshot(context, source)
    }

    private fun snapshot(
        event: String,
        detail: String? = null,
        incidentId: String = lastIncidentId,
        testType: String? = null,
    ) {
        if (!::context.isInitialized) return
        val power = context.getSystemService(PowerManager::class.java)
        val displays = context.getSystemService(DisplayManager::class.java)
            ?.displays
            ?.joinToString(",") { "${it.displayId}:${displayState(it.state)}" }
            .orEmpty()
        val recorder = CameraRecordingService.state.value
        EventLogger.logEvent(
            category = Categories.LIFECYCLE,
            eventName = event,
            payload = buildMap {
                put("probeId", incidentId)
                put("processStartId", ZeekrApp.processStartId)
                put("interactive", (power?.isInteractive == true).toString())
                put("mainDisplayId", Display.DEFAULT_DISPLAY.toString())
                put("elapsed", android.os.SystemClock.elapsedRealtime().toString())
                put("uptime", android.os.SystemClock.uptimeMillis().toString())
                put("standalonePreview", com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.isPreviewing().toString())
                put("floatingControls", com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.active().toString())
                put("captureCallbackAgeMs", com.dante.zeekrcapabilitylab.service.recorder.RecorderCaptureEvidence.latest.lastReceivedElapsedMs
                    ?.let { (android.os.SystemClock.elapsedRealtime()-it).coerceAtLeast(0).toString() } ?: "UNKNOWN")
                put("cleanupOwners", com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.pendingOwners.value.toString())
                put("experimentalC0Active", com.dante.zeekrcapabilitylab.runtime.C0ParkingService.state.value.active.toString())
                put("displays", displays)
                put("appForeground", ZeekrApp.isForeground.value.toString())
                put("activity", MainActivity.currentState.value)
                put("recorder", recorder.status)
                put("source", recorder.sourceRole?.name ?: "NONE")
                put("camera", recorder.cameraId ?: "NONE")
                put("segment", recorder.segmentNumber.toString())
                put("wakeLock", recorder.wakeLockHeld.toString())
                put("recordingMode", recorder.recordingMode.name)
                put("timeLapseMultiplier", recorder.timeLapseMultiplier.toString())
                detail?.let { put("detail", it) }
                testType?.let { put("testType", it) }
            },
        )
    }

    private fun recordCapabilities() {
        val pm = context.packageManager
        val carClass = runCatching { Class.forName("android.car.Car") }.isSuccess
        val carPowerPermission = pm.checkPermission(
            "android.car.permission.READ_CAR_POWER_POLICY",
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED
        EventLogger.logEvent(
            Categories.SYSTEM,
            "VEHICLE_POWER_CAPABILITY",
            payload = mapOf(
                "automotiveFeature" to pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE).toString(),
                "androidCarClass" to carClass.toString(),
                "carPowerPermission" to carPowerPermission.toString(),
                "mode" to "OBSERVE_ONLY",
            ),
        )
    }

    private fun displayState(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> state.toString()
    }
}
