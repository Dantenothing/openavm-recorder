package com.dante.zeekrcapabilitylab.service

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.util.Utils

/** Shared controls for the video mirror and the compact recording status window. */
object RecordingModeOverlayMenu {
    fun label(mode: RecordingMode, multiplier: Int) = when (mode) {
        RecordingMode.NORMAL -> Utils.t("Normal recording", "普通录像")
        RecordingMode.TIME_LAPSE -> Utils.t("Time-lapse · {0}×", "延时录像 · {0}×", multiplier)
    }

    fun progressLabel(value: RecordingModeSwitchProgress): String = when {
        value.cancelled -> Utils.t("Switch cancelled · finishing recording", "切换已取消 · 正在收尾")
        value.releasing -> Utils.t("Saving before switching…", "正在保存，准备切换…")
        else -> Utils.t("Starting {0}…", "正在启动{0}…", label(value.target.mode, value.target.multiplier))
    }

    fun available(): Boolean = CameraRecordingService.modeSwitchProgress.value == null &&
        RecordingModeSwitchGate.eligible(CameraRecordingService.state.value)

    fun show(context: Context, anchor: View) {
        val state = CameraRecordingService.state.value
        val expectedSession = state.recordingSessionId ?: return
        if (!available()) return
        val active = RecordingModeChoice(state.recordingMode, state.timeLapseMultiplier)
        val remembered = SettingsStore.get(context).timeLapseMultiplier
        val menu = PopupMenu(context, anchor)
        val choices = mutableMapOf<Int, RecordingModeChoice>()
        fun item(parent: android.view.Menu, id: Int, target: RecordingModeChoice, text: String) {
            choices[id] = target
            parent.add(0, id, id, text).apply {
                isCheckable = true; isChecked = target == active; isEnabled = target != active
            }
        }
        item(menu.menu, 1, RecordingModeChoice(RecordingMode.NORMAL), label(RecordingMode.NORMAL, 1))
        item(menu.menu, 2, RecordingModeChoice(RecordingMode.TIME_LAPSE, remembered), label(RecordingMode.TIME_LAPSE, remembered))
        val speeds = menu.menu.addSubMenu(0, 3, 3, Utils.t("Time-lapse speed", "延时倍率"))
        TimeLapsePolicy.MULTIPLIERS.forEachIndexed { index, speed ->
            item(speeds, 100 + index, RecordingModeChoice(RecordingMode.TIME_LAPSE, speed), "${speed}×")
        }
        menu.setOnMenuItemClickListener { entry ->
            choices[entry.itemId]?.let { CameraRecordingService.switchMode(it, expectedSession); true } ?: false
        }
        menu.show()
    }
}
