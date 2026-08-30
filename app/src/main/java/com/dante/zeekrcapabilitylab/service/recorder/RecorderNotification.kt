package com.dante.zeekrcapabilitylab.service.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.util.Utils

object RecorderNotification {
    const val CHANNEL_ID = "avm_recorder_recording"
    const val NOTIFICATION_ID = 0x5E47

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            Utils.t("Dashcam recording", "行车记录"),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: RecorderState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bookmarkIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_BOOKMARK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val profile = state.profile
        val title = buildString {
            if (state.recordingMode == RecordingMode.TIME_LAPSE) {
                append(Utils.t("Time-lapse ", "延时摄影 "))
                append(state.timeLapseMultiplier)
                append("× · ")
            } else {
                append(Utils.t("Camera ", "摄像头 "))
            }
            append(state.cameraId ?: "?")
            append(" ")
            append(profile?.label ?: "")
            append(Utils.t(" · segment ", " · 分段 "))
            append(state.segmentNumber)
        }
        val text = buildString {
            append(statusLabel(state.status))
            append(
                if (state.wakeLockHeld) {
                    Utils.t(" | background active", " | 后台保持中")
                } else {
                    Utils.t(" | background idle", " | 后台待机")
                },
            )
            state.currentFile?.let {
                append(" | ")
                append(it.substringAfterLast('/'))
            }
            state.segmentStartedAtEpochMs?.let {
                append(" | ")
                append(Utils.formatEpoch(it))
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
        if (state.recordingMode == RecordingMode.NORMAL) {
            builder.addAction(0, Utils.t("Save clip", "保存片段"), bookmarkIntent)
        }
        return builder
            .addAction(0, Utils.t("Stop", "停止"), stopIntent)
            .build()
    }

    private fun statusLabel(status: String): String = when (status) {
        RecorderStatus.STARTING -> Utils.t("Preparing", "正在准备")
        RecorderStatus.RECORDING -> Utils.t("Recording", "录像中")
        RecorderStatus.FINALIZING -> Utils.t("Saving", "正在保存")
        RecorderStatus.WAITING_CAMERA -> Utils.t("Waiting for camera", "正在等待摄像头")
        RecorderStatus.RESUMING -> Utils.t("Recovering recording", "正在恢复录像")
        RecorderStatus.CAMERA_UNAVAILABLE -> Utils.t("Camera unavailable", "摄像头不可用")
        RecorderStatus.ERROR -> Utils.t("Recording error", "录像异常")
        RecorderStatus.STOPPED -> Utils.t("Stopped", "已停止")
        else -> Utils.t("Standby", "待机")
    }
}
