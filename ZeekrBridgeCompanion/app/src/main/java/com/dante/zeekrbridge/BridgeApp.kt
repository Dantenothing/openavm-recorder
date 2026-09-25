package com.dante.zeekrbridge

import android.app.Application
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.LocalMediaMaintenance
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.ReceivedSentryRegistrar
import com.dante.zeekrbridge.core.SavedMediaStore
import com.dante.zeekrbridge.core.ServerLog
import com.dante.zeekrbridge.core.TrashStore
import com.dante.zeekrbridge.ui.PhoneLanguage

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenAvmRuntime.initialize(this)
    }
}

/** Reused by the standalone companion and the unified phone host. Does not start a receiver. */
object OpenAvmRuntime {
    @Volatile private var initialized = false
    @Synchronized fun initialize(context: android.content.Context) {
        if (initialized) return
        val app = context.applicationContext
        PhoneLanguage.init(app)
        ServerLog.init(app)
        PairingManager.init(app)
        ReceivedStore.init(app)
        SavedMediaStore.init(app)
        ReceivedSentryRegistrar.reconcile(ReceivedStore.files.value)
        TrashStore.init(app)
        LocalMediaMaintenance.schedule(app)
        OutboundOfferStore.init(app)
        initialized = true
    }
}
