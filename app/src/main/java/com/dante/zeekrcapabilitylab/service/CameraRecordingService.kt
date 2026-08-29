package com.dante.zeekrcapabilitylab.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommands
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderNotification
import com.dante.zeekrcapabilitylab.service.recorder.RecorderHealthStore
import com.dante.zeekrcapabilitylab.service.recorder.RecorderSession
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground camera segment recorder. The service (not the UI) owns
 * CameraDevice / CaptureSession / MediaRecorder; the UI only sends commands and
 * collects [state]. START_NOT_STICKY: a killed process is never restarted.
 */
class CameraRecordingService : Service() {

    private lateinit var session: RecorderSession
    private var foreground = false
    private var notificationFailureLogged = false

    override fun onCreate() {
        super.onCreate()
        RecorderNotification.ensureChannel(this)
        instance = this
        session = RecorderSession(this, ::publishState, ::onStopped)
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_SERVICE_COMMAND",
            payload = mapOf(
                "action" to (intent?.action ?: "NULL_RESTART"),
                "flags" to flags.toString(),
                "startId" to startId.toString(),
                "foreground" to foreground.toString(),
                "state" to state.value.status,
            ),
        )
        when (intent?.action) {
            RecorderCommands.ACTION_START -> {
                val previewSurface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE) as? Surface
                }
                val configJson = intent.getStringExtra(RecorderCommands.EXTRA_CONFIG_JSON)
                val config = configJson?.let {
                    runCatching {
                        RecorderCommands.json.decodeFromString(RecorderConfig.serializer(), it)
                    }.getOrNull()
                }
                if (config == null) {
                    runCatching { previewSurface?.release() }
                    EventLogger.markError(
                        Categories.SYSTEM,
                        "RECORDER_INVALID_START_INTENT",
                        "missing or unparseable config",
                        null,
                    )
                    publishState(RecorderState(status = RecorderStatus.ERROR, lastError = "INVALID_CONFIG"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                goForeground()
                session.start(config, previewSurface)
                replayVehicleEnvironment(session)
            }

            RecorderCommands.ACTION_STOP -> session.stop()
            RecorderCommands.ACTION_BOOKMARK -> session.bookmark()
            RecorderCommands.ACTION_RETRY -> session.retry()
            RecorderCommands.ACTION_SET_PREVIEW_OUTPUT -> session.setPreviewOutputEnabled(
                intent.getBooleanExtra(RecorderCommands.EXTRA_PREVIEW_ENABLED, false),
            )

            else -> {
                // START_NOT_STICKY: a null intent (system restart) must not auto-start.
                if (intent == null) {
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_RESTART_REJECTED",
                        payload = mapOf("startId" to startId.toString()),
                    )
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (foreground) {
            refreshNotification()
            return
        }
        val notification = RecorderNotification.build(this, state.value)
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_FOREGROUND_START_REQUESTED",
            payload = mapOf("status" to state.value.status),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                RecorderNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(RecorderNotification.NOTIFICATION_ID, notification)
        }
        foreground = true
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_FOREGROUND_STARTED")
    }

    private fun refreshNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                RecorderNotification.NOTIFICATION_ID,
                RecorderNotification.build(this, state.value),
            )
            notificationFailureLogged = false
        } catch (t: Throwable) {
            // Notification updates must never crash or flood the recorder log.
            if (!notificationFailureLogged) {
                notificationFailureLogged = true
                EventLogger.markError(
                    Categories.SYSTEM,
                    "RECORDER_NOTIFICATION_UPDATE_FAILED",
                    t.message ?: t.javaClass.simpleName,
                    t,
                )
            }
        }
    }

    private fun publishState(s: RecorderState) {
        _state.value = s
        RecorderHealthStore.save(this, s)
        if (foreground) refreshNotification()
    }

    private fun onStopped() {
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_SERVICE_STOPPING",
            payload = mapOf("status" to state.value.status),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do NOT stop here; the foreground service keeps segmenting
        // while the user drives away from the launcher/home screen.
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_TASK_REMOVED_CONTINUING",
            payload = mapOf("status" to state.value.status),
        )
    }

    override fun onDestroy() {
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_SERVICE_DESTROYING",
            payload = mapOf("status" to state.value.status, "foreground" to foreground.toString()),
        )
        session.release()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        @Volatile
        private var instance: CameraRecordingService? = null
        @Volatile
        private var lastAppForeground = true
        @Volatile
        private var lastScreenOn = true
        @Volatile
        private var lastMainDisplayOn = true

        /** UI-visible Activity calls this after the user explicitly taps Start. */
        fun start(context: Context, config: RecorderConfig, previewSurface: Surface? = null) {
            val intent = Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_START)
                .putExtra(
                    RecorderCommands.EXTRA_CONFIG_JSON,
                    RecorderCommands.json.encodeToString(RecorderConfig.serializer(), config),
                )
            if (previewSurface != null) {
                intent.putExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE, previewSurface)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_STOP)
        }

        fun bookmark(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_BOOKMARK)
        }

        fun retry(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_RETRY)
        }

        /** Changes only the repeating request targets; the encoder keeps running. */
        fun setPreviewOutputEnabled(enabled: Boolean) {
            val active = instance ?: return
            // This is an in-process UI lifecycle signal. Posting directly to the
            // session's camera thread preserves detach/reattach ordering across
            // fast tab changes without starting or restarting the service.
            active.session.setPreviewOutputEnabled(enabled)
        }

        /** Replaces a destroyed UI preview without restarting the recorder. */
        fun replacePreviewSurface(surface: Surface) {
            val active = instance
            if (active == null) {
                runCatching { surface.release() }
                return
            }
            active.session.replacePreviewSurface(surface)
        }

        fun isRunning(): Boolean = instance != null

        /** Application power/lifecycle signals; RecorderSession serializes them on its camera thread. */
        fun reportAppForeground(foreground: Boolean) {
            lastAppForeground = foreground
            instance?.session?.onAppForegroundChanged(foreground)
        }

        fun reportScreenPower(screenOn: Boolean) {
            lastScreenOn = screenOn
            instance?.session?.onScreenPowerChanged(screenOn)
        }

        fun reportMainDisplayPower(displayOn: Boolean) {
            lastMainDisplayOn = displayOn
            instance?.session?.onMainDisplayPowerChanged(displayOn)
        }

        private fun replayVehicleEnvironment(session: RecorderSession) {
            session.onAppForegroundChanged(lastAppForeground)
            session.onScreenPowerChanged(lastScreenOn)
            session.onMainDisplayPowerChanged(lastMainDisplayOn)
        }

        /** Restores evidence only; this deliberately does not start the service or camera. */
        fun restoreTerminalState(context: Context) {
            if (instance == null) _state.value = RecorderHealthStore.load(context)
        }

        /**
         * Command delivery consistent with the notification's Stop action: send an
         * explicit service intent with the action. Guarded by the instance check so a
         * dead service is never accidentally started without going through START
         * (which is the only path that calls startForegroundService).
         */
        private fun deliverCommand(context: Context, action: String) {
            if (instance == null) return
            val intent = Intent(context, CameraRecordingService::class.java).setAction(action)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // In-process fallback when the platform rejects background startService.
                EventLogger.markError(
                    Categories.SYSTEM,
                    "RECORDER_COMMAND_DELIVERY_FALLBACK",
                    "$action: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
                instance?.onCommand(action)
            }
        }
    }

    private fun onCommand(action: String) {
        when (action) {
            RecorderCommands.ACTION_STOP -> session.stop()
            RecorderCommands.ACTION_BOOKMARK -> session.bookmark()
            RecorderCommands.ACTION_RETRY -> session.retry()
        }
    }
}
