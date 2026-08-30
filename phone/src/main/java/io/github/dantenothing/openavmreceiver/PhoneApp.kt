package io.github.dantenothing.openavmreceiver

import android.app.Application

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PairingStore.init(this)
        PhoneUploadStore.init(this)
    }
}
