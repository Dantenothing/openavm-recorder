package com.dante.zeekrcapabilitylab.mirror

import android.app.KeyguardManager
import android.content.Context
import com.dante.zeekrcapabilitylab.service.recorder.VehiclePowerSnapshotReader

/** Power broadcasts request reconciliation; only a current snapshot may gate camera/display work. */
internal object MirrorDisplayState {
    fun usable(context: Context): Boolean = runCatching {
        val power = VehiclePowerSnapshotReader.read(context)
        power.interactive && power.mainDisplayState == "ON" &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }.getOrDefault(false)
}
