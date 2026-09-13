package com.dante.zeekrcapabilitylab.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger

/** Service-scoped listener for removable-media loss while the recorder owns a USB FD. */
internal class RemovableStorageMonitor(
    context: Context,
    private val onUnavailable: (action: String, directoryPath: String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            onUnavailable(action, intent.data?.path)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
            addDataScheme("file")
        }
        val outcome = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        registered = outcome.isSuccess
        outcome.exceptionOrNull()?.let { failure ->
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_USB_MONITOR_REGISTRATION_FAILED",
                failure.message ?: failure.javaClass.simpleName,
                failure,
            )
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
