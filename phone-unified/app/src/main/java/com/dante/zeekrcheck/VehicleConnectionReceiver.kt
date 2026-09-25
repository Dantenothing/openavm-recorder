package com.dante.zeekrcheck

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dante.zeekrcheck.core.SyncReason

class VehicleConnectionReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val bound = AssistantStore.get(context).state.value.carBluetoothAddress
        if (bound.isBlank()) return
        val matches = runCatching { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address.equals(bound, true) }.getOrDefault(false)
        if (matches) AwayGuardService.trigger(context, SyncReason.BLUETOOTH)
    }
}
