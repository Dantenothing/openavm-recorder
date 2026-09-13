package com.dante.zeekrcapabilitylab.transfer
import com.dante.zeekrcapabilitylab.util.Utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import io.github.dantenothing.avmtransfer.protocol.SoundInstallStatusUpdate
import io.github.dantenothing.avmtransfer.protocol.SoundOfferListResponse
import io.github.dantenothing.avmtransfer.protocol.SoundOfferMetadata
import io.github.dantenothing.avmtransfer.protocol.SoundOfferStates
import io.github.dantenothing.avmtransfer.protocol.SoundTransferProtocol
import io.github.dantenothing.avmtransfer.protocol.SoundTransferValidation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Serializable
data class SoundDirectoryObservation(
    val directoryName: String,
    val finalPath: String,
    val existedBefore: Boolean = false,
    val tempWritten: Boolean = false,
    val backupWritten: Boolean = false,
    val finalWriteStarted: Boolean = false,
    val finalVerified: Boolean = false,
    val rollbackCompleted: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SoundRelayTask(
    val schemaVersion: Int = 1,
    val offer: SoundOfferMetadata,
    val operationId: String = UUID.randomUUID().toString(),
    val state: String = SoundOfferStates.QUEUED,
    val boundStorageUuid: String? = null,
    val targetDescription: String? = null,
    val cachedFile: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val directories: List<SoundDirectoryObservation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class SoundRelayReport(
    val schemaVersion: Int = 1,
    val generatedAtEpochMs: Long,
    val buildVersion: String,
    val buildGitSha: String,
    val tasks: List<SoundRelayTask>,
    val safetyNotes: List<String> = listOf(
        "Only /Lock Status Tones/ and /解闭锁音效/ are writable targets",
        "/SentryMode/, Download/OpenAVM, recordings, and unknown files are never modified",
        "Success requires reread SHA-256 verification in both target directories",
    ),
)

object PhoneSoundRelay {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val safeTaskId = Regex("^[A-Za-z0-9_-]{1,128}$")
    private lateinit var appContext: Context
    private lateinit var taskRoot: File
    private lateinit var payloadRoot: File
    private val _tasks = MutableStateFlow<List<SoundRelayTask>>(emptyList())
    val tasks: StateFlow<List<SoundRelayTask>> = _tasks.asStateFlow()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var fallbackPollJob: Job? = null
    private val reconcileRunning = AtomicBoolean(false)
    @Volatile private var hintsEnabled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        taskRoot = File(appContext.filesDir, "sound-relay/tasks").apply { mkdirs() }
        payloadRoot = File(appContext.filesDir, "sound-relay/payloads").apply { mkdirs() }
        synchronized(lock) { reloadLocked() }
    }

    fun onForeground() {
        hintsEnabled = true
        connectHints()
        startFallbackPolling()
    }

    fun onBackground() {
        hintsEnabled = false
        fallbackPollJob?.cancel()
        fallbackPollJob = null
        webSocket?.close(1000, "background")
        webSocket = null
    }

    fun onStorageChanged() {
        scope.launch {
            delay(800)
            reconcileOnce()
        }
    }

    fun reconcileInBackground() {
        scope.launch { reconcileOnce() }
    }

    private fun startFallbackPolling() {
        if (fallbackPollJob?.isActive == true) return
        fallbackPollJob = scope.launch {
            while (hintsEnabled) {
                reconcileOnce()
                delay(FOREGROUND_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun reconcileOnce() {
        if (!reconcileRunning.compareAndSet(false, true)) return
        try {
            runCatching { reconcile() }
        } finally {
            reconcileRunning.set(false)
        }
        runCatching { startWorker() }
    }

    fun retry(offerId: String) {
        mutate(offerId) { it.copy(state = SoundOfferStates.QUEUED, errorCode = null, message = null) }
        startWorker()
    }

    fun cancel(offerId: String) {
        mutate(offerId) { it.copy(state = SoundOfferStates.CANCELLED, errorCode = null, message = "Cancelled on vehicle") }
        scope.launch { postCurrent(offerId) }
    }

    /** Deletes only terminal relay journals and their app-private cached payloads. */
    fun clearFinished(): Int = synchronized(lock) {
        val removedIds = mutableSetOf<String>()
        _tasks.value
            .filter { it.state in SoundOfferStates.terminal }
            .forEach { task ->
                val id = task.offer.offerId
                if (!safeTaskId.matches(id)) return@forEach
                val cachedFiles = listOf(
                    File(payloadRoot, "$id.wav"),
                    File(payloadRoot, "$id.partial"),
                )
                if (cachedFiles.any { it.exists() && !it.delete() }) return@forEach
                val tempJournal = File(taskRoot, "$id.json.tmp")
                if (tempJournal.exists() && !tempJournal.delete()) return@forEach
                val journal = File(taskRoot, "$id.json")
                if (!journal.exists() || journal.delete()) {
                    removedIds += id
                }
            }
        if (removedIds.isNotEmpty()) {
            _tasks.value = _tasks.value.filterNot { it.offer.offerId in removedIds }
        }
        removedIds.size
    }

    fun selectUsb(offerId: String, storageUuid: String) {
        mutate(offerId) { it.copy(boundStorageUuid = storageUuid, state = SoundOfferStates.QUEUED, errorCode = null, message = null) }
        startWorker()
    }

    fun reportJson(): String = json.encodeToString(
        SoundRelayReport.serializer(),
        SoundRelayReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            buildGitSha = BuildConfig.GIT_SHA,
            tasks = _tasks.value.sortedByDescending { it.createdAt },
        ),
    )

    internal fun nextRunnable(): SoundRelayTask? = synchronized(lock) {
        _tasks.value.sortedBy { it.createdAt }.firstOrNull {
            it.state in setOf(SoundOfferStates.QUEUED, SoundOfferStates.DOWNLOADING, SoundOfferStates.INSTALLING, SoundOfferStates.WAITING_FOR_USB)
        }
    }

    internal fun process(task: SoundRelayTask) {
        var current = task
        val endpoint = PhoneConnectionStore.saved()
        if (endpoint == null) {
            update(current.copy(state = SoundOfferStates.FAILED_RECOVERABLE, errorCode = "PHONE_NOT_PAIRED", message = "Pair the phone again"))
            return
        }
        try {
            val validation = SoundTransferValidation.validateOffer(current.offer)
            require(validation == null && current.offer.targetCarDeviceId == PhoneConnectionStore.carId) { validation ?: "WRONG_TARGET" }
            var payload = current.cachedFile?.let(::File)?.takeIf { it.isFile }
            if (payload == null || payload.length() != current.offer.sizeBytes || sha256(payload) != current.offer.sha256) {
                current = current.copy(state = SoundOfferStates.DOWNLOADING, message = "Downloading and validating phone WAV")
                update(current); postCurrent(current.offer.offerId)
                payload = download(endpoint, current.offer)
                validatePayload(payload, current.offer)
                current = current.copy(cachedFile = payload.absolutePath)
                update(current)
            } else {
                validatePayload(payload, current.offer)
            }

            val mounted = UsbExportVolumeResolver.mountedTargets(appContext).filter { it.directoryPath != null }
            val target = when {
                current.boundStorageUuid != null -> mounted.firstOrNull { it.storageUuid.equals(current.boundStorageUuid, true) }
                mounted.size == 1 -> mounted.single()
                mounted.isEmpty() -> null
                else -> {
                    update(current.copy(state = SoundOfferStates.WAITING_FOR_USB_SELECTION, errorCode = null, message = "Choose one mounted USB"))
                    postCurrent(current.offer.offerId)
                    return
                }
            }
            if (target == null) {
                update(current.copy(state = SoundOfferStates.WAITING_FOR_USB, errorCode = null, message = "Insert ${current.targetDescription ?: "the selected"} USB"))
                postCurrent(current.offer.offerId)
                return
            }

            current = current.copy(
                state = SoundOfferStates.INSTALLING,
                boundStorageUuid = target.storageUuid,
                targetDescription = target.description,
                errorCode = null,
                message = "Installing to both Zeekr sound directories",
            )
            update(current); postCurrent(current.offer.offerId)
            val result = ZeekrSoundInstaller.install(current, target, payload) { observations ->
                current = current.copy(directories = observations)
                update(current)
            }
            current = current.copy(
                state = if (result.ok) SoundOfferStates.COMPLETED else SoundOfferStates.FAILED_RECOVERABLE,
                directories = result.directories,
                errorCode = result.errorCode,
                message = result.message,
            )
            update(current); postCurrent(current.offer.offerId)
        } catch (t: Throwable) {
            update(current.copy(state = SoundOfferStates.FAILED_RECOVERABLE, errorCode = "SOUND_RELAY_FAILED", message = t.message ?: t.javaClass.simpleName))
            postCurrent(current.offer.offerId)
        }
    }

    private fun reconcile() {
        val endpoint = PhoneConnectionStore.saved() ?: return
        val request = Request.Builder().url(url(endpoint, "api/outbound"))
            .header("Authorization", "Bearer ${endpoint.token}").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Offer list failed (${response.code})")
            val list = wireJson.decodeFromString(SoundOfferListResponse.serializer(), response.body?.string().orEmpty())
            list.offers.forEach { offer ->
                if (offer.targetCarDeviceId != PhoneConnectionStore.carId || SoundTransferValidation.validateOffer(offer) != null) return@forEach
                synchronized(lock) {
                    if (_tasks.value.none { it.offer.offerId == offer.offerId }) {
                        val task = SoundRelayTask(offer = offer)
                        persistLocked(task)
                        _tasks.value = (_tasks.value + task).sortedByDescending { it.createdAt }
                    }
                }
            }
        }
    }

    private fun download(endpoint: PhoneEndpoint, offer: SoundOfferMetadata): File {
        val final = File(payloadRoot, "${offer.offerId}.wav")
        val partial = File(payloadRoot, "${offer.offerId}.partial")
        partial.delete()
        val request = Request.Builder().url(url(endpoint, "api/outbound/${offer.offerId}"))
            .header("Authorization", "Bearer ${endpoint.token}").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Sound download failed (${response.code})")
            val announced = response.body?.contentLength() ?: -1
            if (announced > SoundTransferProtocol.MAX_WAV_BYTES) error("Phone payload exceeds limit")
            FileOutputStream(partial).use { out ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SoundTransferProtocol.MAX_WAV_BYTES) error("Phone payload exceeds limit")
                        out.write(buffer, 0, read)
                    }
                    out.flush(); out.fd.sync()
                } ?: error("Empty payload")
            }
        }
        if (partial.length() != offer.sizeBytes || sha256(partial) != offer.sha256) error("Downloaded WAV integrity mismatch")
        final.delete()
        if (!partial.renameTo(final)) {
            copyVerified(partial, final, offer.sha256)
            partial.delete()
        }
        return final
    }

    private fun validatePayload(file: File, offer: SoundOfferMetadata) {
        require(file.length() == offer.sizeBytes && file.length() < SoundTransferProtocol.MAX_WAV_BYTES) { "WAV size mismatch" }
        require(sha256(file) == offer.sha256) { "WAV checksum mismatch" }
        val parsed = parseWav(file)
        require(parsed == offer.wav) { "WAV parameters differ from phone metadata" }
        require(parsed.format == 1 && parsed.channels in 1..2 && parsed.sampleRate in setOf(44_100, 48_000) && parsed.bitsPerSample == 16) { "Unsupported WAV" }
    }

    private fun postCurrent(offerId: String) {
        val task = _tasks.value.firstOrNull { it.offer.offerId == offerId } ?: return
        val endpoint = PhoneConnectionStore.saved() ?: return
        runCatching {
            val update = SoundInstallStatusUpdate(
                state = task.state, operationId = task.operationId, targetDescription = task.targetDescription,
                targetStorageUuid = task.boundStorageUuid, errorCode = task.errorCode, message = task.message,
            )
            val body = wireJson.encodeToString(SoundInstallStatusUpdate.serializer(), update)
            val request = Request.Builder().url(url(endpoint, "api/outbound/$offerId/status"))
                .header("Authorization", "Bearer ${endpoint.token}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { if (!it.isSuccessful) error("Status failed (${it.code})") }
        }
    }

    private fun connectHints() {
        if (!hintsEnabled) return
        val endpoint = PhoneConnectionStore.saved() ?: return
        webSocket?.cancel()
        val request = Request.Builder().url("ws://${endpoint.host}:${endpoint.port}/control")
            .header("Authorization", "Bearer ${endpoint.token}").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"HELLO","sequence":${System.currentTimeMillis()},"payload":{"carDeviceId":"${PhoneConnectionStore.carId}"}}""")
                reconcileInBackground()
                scope.launch {
                    while (hintsEnabled && this@PhoneSoundRelay.webSocket === webSocket) {
                        delay(10_000)
                        if (!webSocket.send("""{"type":"HEARTBEAT","sequence":${System.currentTimeMillis()},"payload":{}}""")) break
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"FILE_OFFER\"")) reconcileInBackground()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleHintReconnect(webSocket)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleHintReconnect(webSocket)
            }
        })
    }

    private fun scheduleHintReconnect(failed: WebSocket) {
        if (!hintsEnabled || webSocket !== failed) return
        webSocket = null
        scope.launch { delay(3_000); if (hintsEnabled) connectHints() }
    }

    private fun startWorker() {
        if (_tasks.value.any { it.state !in SoundOfferStates.terminal }) SoundRelayService.start(appContext)
    }

    private fun update(task: SoundRelayTask) = mutate(task.offer.offerId) { task }

    private fun mutate(offerId: String, block: (SoundRelayTask) -> SoundRelayTask) {
        synchronized(lock) {
            val old = _tasks.value.firstOrNull { it.offer.offerId == offerId } ?: return
            val next = block(old).copy(updatedAt = System.currentTimeMillis())
            persistLocked(next)
            _tasks.value = _tasks.value.map { if (it.offer.offerId == offerId) next else it }.sortedByDescending { it.createdAt }
        }
    }

    private fun reloadLocked() {
        _tasks.value = taskRoot.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
            runCatching { json.decodeFromString(SoundRelayTask.serializer(), it.readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private fun persistLocked(task: SoundRelayTask) {
        val final = File(taskRoot, "${task.offer.offerId}.json")
        val temp = File(taskRoot, "${task.offer.offerId}.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(SoundRelayTask.serializer(), task).toByteArray())
            out.flush(); out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun url(endpoint: PhoneEndpoint, path: String): HttpUrl = HttpUrl.Builder()
        .scheme("http").host(endpoint.host).port(endpoint.port).addPathSegments(path.trim('/')).build()

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun copyVerified(source: File, target: File, expectedSha: String) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024); out.flush(); out.fd.sync() }
        }
        if (target.length() != source.length() || sha256(target) != expectedSha) error("Destination reread verification failed")
    }

    internal fun parseWav(file: File): io.github.dantenothing.avmtransfer.protocol.SoundWavParameters {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44 && raf.readAscii(4) == "RIFF") { "Not RIFF" }
            raf.skipBytes(4); require(raf.readAscii(4) == "WAVE") { "Not WAVE" }
            var format = -1; var channels = -1; var sampleRate = -1; var bits = -1; var data = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val id = raf.readAscii(4); val size = raf.readLeUInt(); val start = raf.filePointer
                require(size >= 0 && start + size <= raf.length()) { "Invalid WAV chunk" }
                if (id == "fmt " && size >= 16) {
                    format = raf.readLeUShort(); channels = raf.readLeUShort(); sampleRate = raf.readLeUInt().toInt()
                    raf.skipBytes(6); bits = raf.readLeUShort()
                } else if (id == "data") data = size
                raf.seek(start + size + (size and 1L))
            }
            require(format >= 0 && data > 0) { "Missing WAV chunks" }
            return io.github.dantenothing.avmtransfer.protocol.SoundWavParameters(format, channels, sampleRate, bits, data)
        }
    }

    private fun RandomAccessFile.readAscii(count: Int): String = ByteArray(count).also(::readFully).toString(Charsets.US_ASCII)
    private fun RandomAccessFile.readLeUShort(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.readLeUInt(): Long = readLeUShort().toLong() or (readLeUShort().toLong() shl 16)

    private const val FOREGROUND_POLL_INTERVAL_MS = 5_000L
}

class SoundRelayService : Service() {
    companion object {
        private const val CHANNEL = "openavm_sound_relay"
        private const val NOTIFICATION_ID = 2121
        private val running = AtomicBoolean(false)
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SoundRelayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL, Utils.t("OpenAVM sound transfer", "OpenAVM 音效传输"), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(Utils.t("OpenAVM sound transfer", "OpenAVM 音效传输"))
            .setContentText(Utils.t("Preparing vehicle USB sound", "正在准备车机 USB 音效"))
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            Thread {
                try {
                    while (true) {
                        val task = PhoneSoundRelay.nextRunnable() ?: break
                        PhoneSoundRelay.process(task)
                        if (PhoneSoundRelay.nextRunnable()?.state in setOf(SoundOfferStates.WAITING_FOR_USB, SoundOfferStates.WAITING_FOR_USB_SELECTION)) break
                    }
                } finally {
                    running.set(false)
                    removeForegroundNotification()
                    stopSelf()
                }
            }.apply { isDaemon = true; start() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeForegroundNotification()
        super.onDestroy()
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
