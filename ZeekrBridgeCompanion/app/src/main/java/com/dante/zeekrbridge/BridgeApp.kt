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
        PhoneLanguage.init(this)
        ServerLog.init(this)
        PairingManager.init(this)
        ReceivedStore.init(this)
        SavedMediaStore.init(this)
        ReceivedSentryRegistrar.reconcile(ReceivedStore.files.value)
        TrashStore.init(this)
        LocalMediaMaintenance.schedule(this)
        OutboundOfferStore.init(this)
    }
}
