package com.dante.zeekrbridge.service

import android.app.Notification
import android.app.NotificationChannel
import com.dante.zeekrbridge.ui.t
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.dante.zeekrbridge.MainActivity
import com.dante.zeekrbridge.R
import com.dante.zeekrbridge.core.MediaExportJob
import com.dante.zeekrbridge.core.MediaExportQueue
import com.dante.zeekrbridge.core.MediaExportTarget
import com.dante.zeekrbridge.core.PixelCrop
import com.dante.zeekrbridge.core.SavedMediaRecord
import com.dante.zeekrbridge.core.SavedMediaStore
import com.dante.zeekrbridge.core.ServerLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MediaExportService : Service() {
    companion object {
        private const val ACTION_START = "com.dante.zeekrbridge.action.START_MEDIA_EXPORT"
        private const val ACTION_CANCEL = "com.dante.zeekrbridge.action.CANCEL_MEDIA_EXPORT"
        private const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "media_exports"
        private const val NOTIFICATION_ID = 2300

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MediaExportService::class.java).setAction(ACTION_START))
        }

        fun cancel(context: Context, jobId: String) {
            context.startService(
                Intent(context, MediaExportService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: MediaExportJob? = null
    private var transformer: Transformer? = null
    private var progressTask: Job? = null
    private var operationTask: Job? = null
    private var activeTemp: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(activeJob))
        if (intent?.action == ACTION_CANCEL) {
            cancelActive(intent.getStringExtra(EXTRA_JOB_ID))
        } else {
            drainQueue()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressTask?.cancel()
        operationTask?.cancel()
        transformer?.cancel()
        activeTemp?.delete()
        activeJob?.let { MediaExportQueue.markFailed(it.id, "Export service stopped") }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeJob?.let { MediaExportQueue.markFailed(it.id, "Android stopped a long-running export") }
        progressTask?.cancel()
        operationTask?.cancel()
        transformer?.cancel()
        activeTemp?.delete()
        activeJob = null
        stopSelf()
    }

    private fun drainQueue() {
        if (activeJob != null) return
        val next = MediaExportQueue.nextQueued()
        if (next == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        activeJob = next
        MediaExportQueue.markRunning(next.id)
        updateNotification(next)
        if (canCopyDirectly(next)) {
            operationTask = scope.launch { publishDirectCopy(next) }
        } else {
            startTransformer(next)
        }
    }

    private fun canCopyDirectly(job: MediaExportJob): Boolean {
        val clip = job.plan.clips.singleOrNull() ?: return false
        return job.plan.target == MediaExportTarget.ORIGINAL &&
            clip.clipStartMs == 0L && clip.clipEndMs >= clip.sourceDurationMs
    }

    private suspend fun publishDirectCopy(job: MediaExportJob) {
        val source = File(job.plan.clips.single().filePath)
        val result = runCatching { publishFile(source, job.outputName) }
        result.onSuccess { finishCompleted(job, it) }
            .onFailure { finishFailed(job, it.message ?: "Save failed") }
    }

    private fun startTransformer(job: MediaExportJob) {
        val workDir = File(filesDir, "export-work").apply { mkdirs() }
        val temp = File(workDir, "${job.id}.partial.mp4")
        temp.delete()
        activeTemp = temp
        val composition = buildComposition(job)
        val nextTransformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setPortraitEncodingEnabled(true)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressTask?.cancel()
                    operationTask = scope.launch {
                        val result = runCatching { publishFile(temp, job.outputName) }
                        temp.delete()
                        result.onSuccess { finishCompleted(job, it) }
                            .onFailure { finishFailed(job, it.message ?: "Save failed") }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    progressTask?.cancel()
                    temp.delete()
                    finishFailed(job, "${exportException.errorCodeName}: ${exportException.message.orEmpty()}")
                }
            })
            .build()
        transformer = nextTransformer
        nextTransformer.start(composition, temp.absolutePath)
        progressTask = scope.launch {
            val holder = ProgressHolder()
            while (activeJob?.id == job.id) {
                if (nextTransformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    MediaExportQueue.markProgress(job.id, holder.progress)
                    updateNotification(MediaExportQueue.jobs.value.firstOrNull { it.id == job.id })
                }
                delay(500)
            }
        }
    }

    private fun buildComposition(job: MediaExportJob): Composition {
        val items = job.plan.clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(File(clip.filePath)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.clipStartMs)
                        .setEndPositionMs(clip.clipEndMs)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true)
                .setEffects(
                    clip.crop?.let { Effects(emptyList(), listOf(it.toMedia3Crop())) }
                        ?: Effects.EMPTY,
                )
                .build()
        }
        return Composition.Builder(EditedMediaItemSequence.withVideoFrom(items))
            // The 1280x5140 composite is decodable on the test phone but may exceed an
            // encoder's maximum dimension. Original Session exports therefore copy
            // encoded samples instead of risking an unnecessary re-encode.
            .setTransmuxVideo(job.plan.target == MediaExportTarget.ORIGINAL)
            .build()
    }

    private fun PixelCrop.toMedia3Crop(): Crop {
        val normalized = normalizedForMedia3()
        return Crop(normalized.left, normalized.right, normalized.bottom, normalized.top)
    }

    private fun cancelActive(jobId: String?) {
        val current = activeJob ?: return
        if (jobId != current.id) return
        progressTask?.cancel()
        operationTask?.cancel()
        transformer?.cancel()
        activeTemp?.delete()
        MediaExportQueue.markCancelled(current.id)
        clearActiveAndContinue()
    }

    private fun finishCompleted(job: MediaExportJob, output: PublishedOutput) {
        if (activeJob?.id != job.id) return
        job.libraryMetadata?.let { metadata ->
            runCatching {
                SavedMediaStore.register(
                    SavedMediaRecord(
                        outputUri = output.uri,
                        outputPath = output.filePath,
                        displayName = job.outputName,
                        origin = metadata.origin,
                        sourceId = metadata.sourceId,
                        exportTarget = job.plan.target.name,
                        createdAtEpochMs = System.currentTimeMillis(),
                        durationMs = job.plan.outputDurationMs,
                        sizeBytes = output.sizeBytes,
                        layoutKind = metadata.layoutKind.name,
                        laneLabels = metadata.laneLabels,
                        laneOrder = metadata.laneOrder,
                        originalWidth = metadata.originalWidth,
                        originalHeight = metadata.originalHeight,
                    ),
                )
            }.onFailure {
                ServerLog.log("MEDIA_LIBRARY_REGISTER_FAILED job=${job.id} error=${it.message}")
            }
        }
        MediaExportQueue.markCompleted(job.id, output.uri, output.filePath)
        clearActiveAndContinue()
    }

    private fun finishFailed(job: MediaExportJob, message: String) {
        if (activeJob?.id != job.id) return
        MediaExportQueue.markFailed(job.id, message)
        clearActiveAndContinue()
    }

    private fun clearActiveAndContinue() {
        progressTask?.cancel()
        progressTask = null
        operationTask = null
        transformer = null
        activeTemp = null
        activeJob = null
        drainQueue()
    }

    private suspend fun publishFile(source: File, requestedName: String): PublishedOutput = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) { "Export produced no video" }
        if (Build.VERSION.SDK_INT < 29) {
            val root = File(filesDir, "exports").apply { mkdirs() }
            val target = uniqueFile(root, requestedName)
            source.inputStream().use { input -> target.outputStream().use { output -> copyCancellable(input, output) } }
            return@withContext PublishedOutput(uri = null, filePath = target.absolutePath, sizeBytes = target.length())
        }
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OpenAVM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create Movies/OpenAVM output")
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> copyCancellable(input, output) }
            } ?: error("Cannot write output video")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            PublishedOutput(uri = uri.toString(), filePath = null, sizeBytes = source.length())
        } catch (error: Throwable) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            throw error
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private fun uniqueFile(root: File, requestedName: String): File {
        val direct = File(root, requestedName)
        if (!direct.exists()) return direct
        val stem = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "mp4")
        var suffix = 2
        while (true) {
            val candidate = File(root, "$stem ($suffix).$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, t("OpenAVM media exports", "OpenAVM 媒体导出"), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun updateNotification(job: MediaExportJob?) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(job))
    }

    private fun notification(job: MediaExportJob?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(job?.let { t("Exporting {0}", "正在导出 {0}", it.outputName) } ?: t("Preparing media export", "正在准备媒体导出"))
            .setContentIntent(openIntent)
            .setOngoing(job != null)
        if (job != null) {
            builder.setProgress(100, job.progressPercent, job.progressPercent <= 0)
            val cancelIntent = PendingIntent.getService(
                this,
                job.id.hashCode(),
                Intent(this, MediaExportService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_JOB_ID, job.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, t("Cancel", "取消"), cancelIntent)
        }
        return builder.build()
    }

    private data class PublishedOutput(val uri: String?, val filePath: String?, val sizeBytes: Long)
}
