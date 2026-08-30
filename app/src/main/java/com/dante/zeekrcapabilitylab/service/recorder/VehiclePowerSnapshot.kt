package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.dante.zeekrcapabilitylab.ZeekrApp

/** One atomic, conservative view of the Android power state used for decisions. */
data class VehiclePowerSnapshot(
    val appForeground: Boolean,
    val interactive: Boolean,
    val mainDisplayOn: Boolean,
    val mainDisplayState: String,
)

object VehiclePowerSnapshotReader {
    fun read(context: Context): VehiclePowerSnapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val displayState = display?.state
        return VehiclePowerSnapshot(
            appForeground = ZeekrApp.isForeground.value,
            // Missing services are treated as ON. Unknown evidence must never stop a Session.
            interactive = powerManager?.isInteractive ?: true,
            mainDisplayOn = displayState == null || displayState != Display.STATE_OFF,
            mainDisplayState = displayStateName(displayState),
        )
    }

    private fun displayStateName(state: Int?): String = when (state) {
        null -> "UNKNOWN"
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> state.toString()
    }
}
