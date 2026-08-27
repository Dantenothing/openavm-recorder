package com.dante.zeekrcapabilitylab

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.CrashHandler
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ZeekrApp : Application() {

    companion object {
        lateinit var appContext: Context
            private set
        val processStartEpochMs: Long = System.currentTimeMillis()
        val processStartElapsedMs: Long = SystemClock.elapsedRealtime()
        val processStartId: String = "${Process.myPid()}-${System.currentTimeMillis()}"

        private val _isForeground = MutableStateFlow(false)
        val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AppLanguage.init(this)

        EventLogger.init(this)
        SettingsStore.init(this)
        CameraRecordingService.restoreTerminalState(this)

        CrashHandler.install(this)

        EventLogger.logEvent(
            category = Categories.APP,
            eventName = "PROCESS_STARTED",
            severity = Severity.INFO,
            payload = mapOf(
                "processStartId" to processStartId,
                "pid" to Process.myPid().toString(),
                "elapsedRealtimeMs" to SystemClock.elapsedRealtime().toString(),
                "applicationId" to BuildConfig.APPLICATION_ID,
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE.toString(),
                "buildType" to BuildConfig.BUILD_TYPE,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
            ),
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isForeground.value = true
                EventLogger.logEvent(
                    Categories.LIFECYCLE,
                    "PROCESS_FOREGROUND",
                    payload = mapOf("recorderStatus" to CameraRecordingService.state.value.status),
                )
            }

            override fun onStop(owner: LifecycleOwner) {
                _isForeground.value = false
                EventLogger.logEvent(
                    Categories.LIFECYCLE,
                    "PROCESS_BACKGROUND",
                    payload = mapOf("recorderStatus" to CameraRecordingService.state.value.status),
                )
            }
        })

        CrashHandler.readLast(this)?.let { crash ->
            EventLogger.logEvent(
                category = Categories.APP,
                eventName = "PREVIOUS_CRASH",
                severity = Severity.ERROR,
                payload = mapOf(
                    "crashTime" to Utils.formatEpoch(crash.epochMs),
                    "thread" to crash.thread,
                    "exception" to crash.exceptionType,
                    "message" to (crash.message ?: ""),
                ),
                errorType = crash.exceptionType,
                errorMessage = crash.message,
            )
            CrashHandler.clear(this)
        }

    }
}
