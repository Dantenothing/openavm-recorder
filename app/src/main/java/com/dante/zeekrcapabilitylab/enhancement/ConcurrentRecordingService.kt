package com.dante.zeekrcapabilitylab.enhancement

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sharing.BrowserShareManager
import com.dante.zeekrcapabilitylab.usbexport.UsbExportRepository
import com.dante.zeekrcapabilitylab.util.Utils

/** An explicit parked test, never an automatic replacement for ordinary recording. */
class ConcurrentRecordingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preparation: HandlerThread
    private var owner: String? = null
    @Volatile private var run: ConcurrentRecordingRun? = null
    @Volatile private var stopping = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if(intent?.action == Intent.ACTION_SCREEN_OFF) stopTest() }
    }
    override fun onCreate() {
        super.onCreate(); instance = this; CaptureCleanupRuntime.initialize(this); RecorderNotification.ensureChannel(this)
        preparation = HandlerThread("multi-inspect").apply { start() }
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 30) { intent?.getStringExtra("owner")?.let(CameraWorkCoordinator::finish); stopSelf(); return START_NOT_STICKY }
        if(intent?.action == STOP) { stopTest(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("owner")
        if (intent?.action != START || id == null || CameraWorkCoordinator.state.value.owner != id || CameraRecordingService.isRunning()) {
            if(owner == null) { id?.let(CameraWorkCoordinator::finish); stopSelf() }; return START_NOT_STICKY
        }
        if(owner != null) return START_NOT_STICKY
        owner = id
        val stop = PendingIntent.getService(this, 95, Intent(this, ConcurrentRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 96, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, RecorderNotification.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OpenAVM").setContentText(Utils.t("Two-camera test · 60 s", "两路录像测试 · 60 秒"))
            .setOngoing(true).setContentIntent(open).addAction(0, Utils.t("Stop", "停止"), stop).build()
        try { startForeground(0x5E49, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) }
        catch(t: Throwable) { finish(); return START_NOT_STICKY }
        val secondary = runCatching { RecordingSourceRole.valueOf(intent.getStringExtra("secondary").orEmpty()) }.getOrDefault(RecordingSourceRole.CABIN)
        CameraWorkCoordinator.publish(id, Utils.t("Checking camera and USB capabilities…", "正在检查相机及 USB 能力…"))
        Handler(preparation.looper).post {
            val plan = runCatching {
                check(BrowserShareManager.stopForRecording()) { "BROWSER_DOWNLOAD_ACTIVE" }
                check(UsbExportRepository.tasks.value.none { !it.terminal() }) { "USB_EXPORT_ACTIVE" }
                ConcurrentRecordingPreflight.inspect(this, secondary)
            }
            val declaration = (plan.exceptionOrNull() as? ConcurrentPreflightRejected)?.declaration
            val report = kotlinx.serialization.json.buildJsonObject {
                put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                put("format", kotlinx.serialization.json.JsonPrimitive("OPENAVM_CONCURRENT_TEST"))
                put("version", kotlinx.serialization.json.JsonPrimitive(BuildConfig.VERSION_NAME))
                put("versionCode", kotlinx.serialization.json.JsonPrimitive(BuildConfig.VERSION_CODE))
                put("androidSdk", kotlinx.serialization.json.JsonPrimitive(Build.VERSION.SDK_INT))
                put("run", kotlinx.serialization.json.JsonPrimitive(id))
                put("secondary", kotlinx.serialization.json.JsonPrimitive(secondary.name))
                put("cameraOpened", kotlinx.serialization.json.JsonPrimitive(false))
                put("phase", kotlinx.serialization.json.JsonPrimitive(if(plan.isFailure || stopping) "PREFLIGHT_REJECTED" else "WAITING_FOR_OWNERSHIP"))
                put("reason", kotlinx.serialization.json.JsonPrimitive(if(stopping) "CANCELLED" else plan.exceptionOrNull()?.message ?: "PREPARING"))
                if (declaration != null) {
                    put("requestedCameraIds", kotlinx.serialization.json.JsonArray(declaration.requestedIds.take(4).map {
                        kotlinx.serialization.json.JsonPrimitive(it.take(80)) }))
                    put("declaredConcurrentSets", kotlinx.serialization.json.JsonArray(declaration.declaredSets
                        .sortedBy { it.sorted().joinToString() }.take(12).map { set -> kotlinx.serialization.json.JsonArray(
                            set.sorted().take(8).map { kotlinx.serialization.json.JsonPrimitive(it.take(80)) }) }))
                    put("omittedConcurrentSets", kotlinx.serialization.json.JsonPrimitive((declaration.declaredSets.size - 12).coerceAtLeast(0)))
                }
            }.toString()
            runCatching { android.util.AtomicFile(java.io.File(filesDir, "multi-camera-last.json")).let { file ->
                val stream = file.startWrite(); try { stream.write(report.toByteArray()); file.finishWrite(stream) } catch(t: Throwable) { file.failWrite(stream); throw t }
            } }
            main.post {
                if(stopping || plan.isFailure) {
                    val reason = if(stopping) "CANCELLED" else plan.exceptionOrNull()?.message.orEmpty()
                    val message = if (reason == "PAIR_NOT_DECLARED") Utils.t(
                        "Not started: the system does not declare this camera pair. Neither camera was opened. Repeating this test will return the same result. (PAIR_NOT_DECLARED)",
                        "未开始：系统未声明支持这组相机，两路均未打开。重复点击不会进行实际录像测试。（PAIR_NOT_DECLARED）")
                        else Utils.t("Two-camera test unavailable: {0}", "两路测试不可用：{0}", reason)
                    getSharedPreferences("multi_camera", 0).edit().putString("result", message).apply()
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    run = ConcurrentRecordingRun(this, id, plan.getOrThrow()) { finish() }.also { it.start() }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun stopTest() { stopping = true; if(Build.VERSION.SDK_INT >= 30) run?.stop() }
    private fun finish() { owner?.let(CameraWorkCoordinator::finish); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        stopping = true; if(Build.VERSION.SDK_INT >= 30) run?.stop("SERVICE_DESTROYED"); instance = null
        runCatching { unregisterReceiver(screen) }; preparation.quitSafely(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val START = "openavm.multi.START"
        private const val STOP = "openavm.multi.STOP"
        @Volatile private var instance: ConcurrentRecordingService? = null
        fun start(context: Context, secondary: RecordingSourceRole): Boolean {
            if (Build.VERSION.SDK_INT < 30 || !ZeekrApp.isForeground.value || CameraRecordingService.isRunning()) return false
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            val id = CameraWorkCoordinator.claim("MULTI") ?: return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, ConcurrentRecordingService::class.java)
                .setAction(START).putExtra("owner", id).putExtra("secondary", secondary.name)); true }
                .getOrElse { CameraWorkCoordinator.finish(id); false }
        }
        fun stop() { instance?.main?.post { instance?.stopTest() } }
    }
}
