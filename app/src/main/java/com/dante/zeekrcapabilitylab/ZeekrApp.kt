package com.dante.zeekrcapabilitylab

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.CrashHandler
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.diagnostic.VehicleAwayProbe
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.transfer.PhoneSoundRelay
import com.dante.zeekrcapabilitylab.transfer.TransferRepository
import com.dante.zeekrcapabilitylab.usbexport.UsbExportRepository
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
        TransferRepository.init(this)
        PhoneSoundRelay.init(this)
        UsbExportRepository.init(this)
        registerSoundRelayStorageReceiver()

        CrashHandler.install(this)

        EventLogger.logEvent(
            category = Categories.APP,
            eventName = "PROCESS_STARTED",
            severity = Severity.INFO,
            payload = mapOf(
                "processStartId" to processStartId,
                "pid" to Process.myPid().toString(),
                "elapsedRealtimeMs" to SystemClock.elapsedRealtime().toString(),
            ),
        )

        VehicleAwayProbe.init(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isForeground.value = true
                VehicleAwayProbe.recordAppState(true)
                TransferRepository.reconnectInBackground()
                PhoneSoundRelay.onForeground()
                UsbExportRepository.resumeQueuedWhenForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                _isForeground.value = false
                VehicleAwayProbe.recordAppState(false)
                PhoneSoundRelay.onBackground()
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

    private fun registerSoundRelayStorageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    PhoneSoundRelay.onStorageChanged()
                }
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
