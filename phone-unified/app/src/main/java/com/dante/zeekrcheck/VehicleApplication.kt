package com.dante.zeekrcheck

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Main UI and widget sheets share one controller and one climate queue for this process. */
class VehicleApplication : Application(), ViewModelStoreOwner {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.dante.zeekrbridge.ui.PhoneLanguage.updateSystemLocale(newConfig.locales[0])
        VehicleWidgetProvider.updateAll(this)
    }
    override val viewModelStore = ViewModelStore()
    override fun onCreate() {
        super.onCreate()
        CloudAccess.initialize(this)
        com.dante.zeekrbridge.ui.PhoneLanguage.limitTo(setOf(
            io.github.dantenothing.openavm.i18n.UiLanguage.ENGLISH,
            io.github.dantenothing.openavm.i18n.UiLanguage.SIMPLIFIED_CHINESE))
        com.dante.zeekrbridge.ui.PhoneLanguage.init(this)
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                if (intent.action == android.content.Intent.ACTION_USER_PRESENT)
                    AwayGuardService.trigger(context, com.dante.zeekrcheck.core.SyncReason.UNLOCK)
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            val file=android.util.AtomicFile(java.io.File(filesDir,"network-diagnostic.json"))
            com.dante.zeekrcheck.core.NetworkTrace.changed={text ->
                val output=file.startWrite()
                try { output.write(text.toByteArray(Charsets.UTF_8));file.finishWrite(output) }
                catch(e:Exception) { file.failWrite(output);throw e }
            }
        }
    }
}

@Composable internal fun assistantModel(): CheckViewModel {
    val application = LocalContext.current.applicationContext as VehicleApplication
    return remember(application) {
        ViewModelProvider(application, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java]
    }
}
