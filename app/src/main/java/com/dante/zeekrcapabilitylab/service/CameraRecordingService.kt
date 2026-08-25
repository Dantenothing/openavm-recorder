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

    override fun onCreate() {
        super.onCreate()
        RecorderNotification.ensureChannel(this)
        instance = this
        session = RecorderSession(this, ::publishState, ::onStopped)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
    }

    private fun refreshNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                RecorderNotification.NOTIFICATION_ID,
                RecorderNotification.build(this, state.value),
            )
        } catch (t: Throwable) {
            // Notification updates must never crash the recorder.
        }
    }

    private fun publishState(s: RecorderState) {
        _state.value = s
        RecorderHealthStore.save(this, s)
        if (foreground) refreshNotification()
    }

    private fun onStopped() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do NOT stop here; the foreground service keeps segmenting
        // while the user drives away from the launcher/home screen.
    }

    override fun onDestroy() {
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
        fun setPreviewOutputEnabled(context: Context, enabled: Boolean) {
            val active = instance ?: return
            val intent = Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_SET_PREVIEW_OUTPUT)
                .putExtra(RecorderCommands.EXTRA_PREVIEW_ENABLED, enabled)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                active.session.setPreviewOutputEnabled(enabled)
            }
        }

        fun isRunning(): Boolean = instance != null

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
