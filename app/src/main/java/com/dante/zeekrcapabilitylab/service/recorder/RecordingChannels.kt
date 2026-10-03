package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** One process-wide admission owner. Native cleanup timeouts retain the owner; no replacement threads are spawned. */
object RecordingChannels {
    private val control = Handler(HandlerThread("recording-channels-control").apply { start() }.looper)
    private val metadata = Handler(HandlerThread("recording-channels-metadata").apply { start() }.looper)
    private val journal = Handler(HandlerThread("recording-channels-journal").apply { start() }.looper)
    private val mutable = MutableStateFlow<List<RecordingChannelHealth>>(emptyList())
    val state = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val history = linkedMapOf<String, LinkedHashMap<String, RecordingChannelHealth>>()
    private val owners = linkedMapOf<String, Owner>()
    private val admission = RecordingChannelAdmission()
    private var activeSession: String? = null
    private var primaryState = RecorderState()
    private var primaryEvidence: CaptureEvidence? = null
    private var primaryRecorder: RecorderSession? = null
    private var activeConfig: RecorderConfig? = null
    private var currentContext: Context? = null
    private var attempted = false
    private var revoked = false
    private var metadataBusy = false
    private var metadataGeneration = 0L
    private var tickScheduled = false
    private var writePending = false
    private var journalRevision = 0L
    private var primarySettleUntilMs = 0L
    private var primaryReferenceReleased = false

    private class Owner(val identity: RecordingChannelIdentity, val session: RecorderSession, val requestedAt: Long, val token: Any) {
        var closing = false
        var lastState = RecorderState(status = RecorderStatus.STARTING)
    }

    /** Caller supplies cached evidence only; this method never waits for cameras, SDK getters or storage. */
    fun follow(context: Context, config: RecorderConfig?, primary: RecorderState, recorder: RecorderSession) {
        val app = context.applicationContext
        control.post {
            val id = primary.recordingSessionId ?: return@post
            currentContext = app
            if (activeSession != id) {
                owners.values.toList().forEach(::release)
                activeSession = id; attempted = false; revoked = false; metadataGeneration++; primarySettleUntilMs = 0L
                primaryReferenceReleased = false
                history.getOrPut(id) { linkedMapOf() }
                while (history.size > 3) history.remove(history.keys.first())
                config?.let(RecordingChannelPolicy::roles)?.forEach { role ->
                    put(RecordingChannelHealth(id, RecordingChannelId.forRole(role)))
                }
            }
            primaryState = primary
            if (!primaryReferenceReleased) {
                activeConfig = config
                primaryRecorder = recorder
            }
            primaryEvidence = recorder.captureEvidence().takeIf { it.sessionId == id }
            updatePrimary()
            if (primary.status in setOf(RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE, RecorderStatus.WAITING_CAMERA,
                    RecorderStatus.STOPPED, RecorderStatus.IDLE)) {
                metadataGeneration++
                // Auxiliary channels do not independently recover behind a stopped/interrupted primary.
                owners.values.filter { it.identity.sessionId == id }.toList().forEach(::release)
                history[id]?.values?.toList()?.filter { it.status == "STARTING" && it.channelId != primary.channelId }?.forEach {
                    put(it.copy(status = "UNAVAILABLE", errorCode = "PRIMARY_INTERRUPTED"))
                }
            }
            beginIfReady()
            releasePrimaryReferenceIfDone()
            publish(); scheduleTick()
        }
    }

    fun stop(sessionId: String?) {
        control.post {
            if (sessionId == null || activeSession == sessionId) {
                if (!revoked) primarySettleUntilMs = SystemClock.elapsedRealtime() + 20_000L
                revoked = true; metadataGeneration++
            }
            owners.values.filter { sessionId == null || it.identity.sessionId == sessionId }.toList().forEach(::release)
            history[sessionId ?: activeSession]?.values?.toList()?.filter { it.status == "STARTING" }?.forEach {
                put(it.copy(status = "STOPPED"))
            }
            updatePrimary(); releasePrimaryReferenceIfDone()
            publish(); scheduleTick()
        }
    }

    private fun begin(context: Context, config: RecorderConfig, primary: RecorderState) {
        val id = primary.recordingSessionId ?: return
        val origin = primary.sessionStartedElapsedMs ?: return
        val roles = RecordingChannelPolicy.roles(config)
        if (roles.isEmpty()) return
        if (metadataBusy) {
            roles.forEach { unavailable(id, it, "CAMERA_METADATA_BUSY") }; return
        }
        val generation = ++metadataGeneration
        metadataBusy = true
        val timeout = Runnable {
            if (metadataGeneration == generation) {
                metadataGeneration++
                roles.forEach { unavailable(id, it, "CAMERA_METADATA_TIMEOUT") }; publish()
            }
        }
        control.postDelayed(timeout, RecordingChannelPolicy.START_TIMEOUT_MS)
        metadata.post {
            val resolved = roles.associateWith { role -> runCatching {
                val source = requireNotNull(ProductRecorderConfigFactory.resolveSource(context, role)) { "SOURCE_UNAVAILABLE" }
                require(source.cameraId != config.cameraId) { "CAMERA_ALREADY_PRIMARY" }
                config.copy(source = source.copy(profile = CameraFormatProfile(ProfileSize(RecordingChannelPolicy.WIDTH,
                    RecordingChannelPolicy.HEIGHT), RecordingChannelPolicy.BITRATE), layoutKind = RecordingLayoutKind.SINGLE_V1, laneLayout = null),
                    requestedFrameRate = RecordingChannelPolicy.FPS, strictFrameRate = false,
                    sharedInputRecordingEnabled = false, mirrorPreviewEnabled = false, auxiliaryChannels = emptyList(),
                    storagePreference = RecordingStoragePreference.INTERNAL_ONLY)
            } }
            control.post {
                metadataBusy = false; control.removeCallbacks(timeout)
                if (generation != metadataGeneration || activeSession != id || revoked) return@post
                val ids = mutableSetOf(config.cameraId)
                for (role in roles) {
                    val cfg = resolved.getValue(role).getOrNull()
                    if (cfg == null) { unavailable(id, role, "SOURCE_UNAVAILABLE"); continue }
                    val token = Any()
                    if (!ids.add(cfg.cameraId) || !admission.admit(cfg.cameraId, token)) {
                        unavailable(id, role, "CAMERA_RELEASE_PENDING"); continue
                    }
                    val identity = RecordingChannelIdentity(id, origin, RecordingChannelId.forRole(role))
                    lateinit var owner: Owner
                    val session = runCatching { RecorderSession(context, { value -> control.post {
                        if (owners[cfg.cameraId] !== owner) return@post
                        owner.lastState = value
                        updateOwner(owner)
                        if (value.lastError != null || value.cleanupUnconfirmed || value.status in
                            setOf(RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE, RecorderStatus.WAITING_CAMERA)) release(owner)
                        publish()
                    } }, { control.post { if (owners[cfg.cameraId] === owner) release(owner) } },
                        externallyManagedPresence = true, auxiliaryChannel = true) }.getOrNull()
                    if (session == null) {
                        admission.release(cfg.cameraId, token, true)
                        unavailable(id, role, "AUXILIARY_OWNER_UNAVAILABLE")
                        continue
                    }
                    owner = Owner(identity, session, SystemClock.elapsedRealtime(), token)
                    owners[cfg.cameraId] = owner
                    session.start(cfg, sharedIdentity = identity)
                }
                publish(); scheduleTick()
            }
        }
    }

    private fun unavailable(id: String, role: RecordingSourceRole, reason: String) {
        put(RecordingChannelHealth(id, RecordingChannelId.forRole(role), status = "UNAVAILABLE", errorCode = reason))
    }

    private fun release(owner: Owner) {
        if (owner.closing) return
        owner.closing = true
        val old = health(owner.identity)
        put(old.copy(status = if (old.status in setOf("UNAVAILABLE", "INTERRUPTED")) old.status else "STOPPING", cleanupPending = true))
        owner.session.releaseForModeHandoff { control.post {
            val entry = owners.entries.firstOrNull { it.value === owner } ?: return@post
            updateOwner(owner)
            val settled = health(owner.identity)
            put(settled.copy(status = if (settled.errorCode == null) "STOPPED" else settled.status,
                cleanupPending = false, cleanupUnconfirmed = false))
            owners.remove(entry.key)
            admission.release(entry.key, owner.token, true)
            publish()
        } }
    }

    private fun health(identity: RecordingChannelIdentity): RecordingChannelHealth = history[identity.sessionId]?.get(identity.channelId)
        ?: RecordingChannelHealth(identity.sessionId, identity.channelId)

    private fun updateOwner(owner: Owner) {
        val old = health(owner.identity)
        val snapshot = owner.session.stateSnapshot()
        var next = RecordingChannelPolicy.update(old, snapshot, owner.session.captureEvidence(), SystemClock.elapsedRealtime())
        if (old.errorCode != null && next.errorCode == null) next = next.copy(errorCode = old.errorCode,
            status = if (old.writtenFrames > 0 || old.capturedFrames > 0) "INTERRUPTED" else "UNAVAILABLE")
        if (owner.closing && !snapshot.cleanupUnconfirmed) next = next.copy(cleanupPending = true)
        put(next)
    }

    private fun updatePrimary() {
        primaryRecorder?.let { recorder ->
            recorder.stateSnapshot().takeIf { it.recordingSessionId == activeSession }?.let { primaryState = it }
            primaryEvidence = recorder.captureEvidence().takeIf { it.sessionId == activeSession }
        }
        val id = primaryState.recordingSessionId ?: return
        val channel = primaryState.channelId ?: RecordingChannelId.forRole(primaryState.sourceRole ?: RecordingSourceRole.SURROUND)
        val old = history[id]?.get(channel) ?: RecordingChannelHealth(id, channel)
        put(RecordingChannelPolicy.update(old, primaryState, primaryEvidence, SystemClock.elapsedRealtime()))
    }

    private fun beginIfReady() {
        val context = currentContext ?: return
        val config = activeConfig ?: return
        val started = (primaryEvidence?.encoder?.writtenFrames ?: 0) > 0 ||
            primaryState.recordingBackend == "MEDIA_RECORDER" && (primaryEvidence?.frames?.count ?: 0) >= 2
        if (!attempted && !revoked && primaryState.status == RecorderStatus.RECORDING && started) {
            attempted = true
            begin(context, config, primaryState)
        }
    }

    private fun releasePrimaryReferenceIfDone() {
        if (primaryRecorder == null || RecordingChannelRetention.retainPrimary(revoked, primaryState.status,
                primaryState.cleanupPending, primaryState.cleanupUnconfirmed, primarySettleUntilMs,
                SystemClock.elapsedRealtime())) return
        // This is only the presentation observer. Native/IO ownership remains with the
        // recorder's existing close transaction and CaptureCleanupRuntime until acknowledged.
        primaryRecorder = null
        activeConfig = null
        primaryReferenceReleased = true
    }

    private fun scheduleTick() {
        val settlingPrimary = primaryRecorder != null && revoked && SystemClock.elapsedRealtime() < primarySettleUntilMs
        val observingAuxiliary = owners.values.any {
            RecordingChannelRetention.pollAuxiliary(it.closing, it.session.stateSnapshot().cleanupUnconfirmed)
        }
        if (tickScheduled || !observingAuxiliary && !settlingPrimary &&
            (revoked || primaryState.status !in setOf(RecorderStatus.STARTING, RecorderStatus.RECORDING))) return
        tickScheduled = true
        control.postDelayed({
            tickScheduled = false
            updatePrimary()
            releasePrimaryReferenceIfDone()
            beginIfReady()
            owners.values.toList().forEach { owner ->
                updateOwner(owner)
                val sample = health(owner.identity)
                val timeout = SystemClock.elapsedRealtime() - owner.requestedAt >= RecordingChannelPolicy.START_TIMEOUT_MS && sample.writtenFrames == 0L
                val stalled = sample.status == "INTERRUPTED"
                if (!owner.closing && (timeout || stalled)) {
                    put(sample.copy(status = if (sample.capturedFrames > 0) "INTERRUPTED" else "UNAVAILABLE",
                        errorCode = if (timeout) "AUXILIARY_NO_WRITTEN_FRAMES" else "AUXILIARY_CAPTURE_OR_WRITE_STALE"))
                    release(owner)
                }
            }
            publish(); scheduleTick()
        }, 1_000)
    }

    private fun put(value: RecordingChannelHealth) {
        history.getOrPut(value.recordingSessionId) { linkedMapOf() }[value.channelId] = value
        // Late release acknowledgements may reinsert an old session. Keep those callbacks bounded too.
        while (history.size > 3) {
            val oldest = history.keys.firstOrNull { it != activeSession && it != value.recordingSessionId }
                ?: history.keys.first()
            history.remove(oldest)
        }
    }
    private fun publish() {
        journalRevision++
        mutable.value = history[activeSession]?.values?.toList().orEmpty()
        if (writePending) return
        val context = currentContext ?: return
        writePending = true
        val revision = journalRevision
        val saved = history.values.flatMap { it.values }.takeLast(9)
        journal.post { runCatching {
            val target = AtomicFile(File(context.filesDir, "recordings/channel-health.json"))
            target.baseFile.parentFile?.mkdirs()
            val out = target.startWrite()
            try { out.write(json.encodeToString(saved).toByteArray()); target.finishWrite(out) }
            catch (error: Throwable) { target.failWrite(out); throw error }
        }; control.post { writePending = false; if (journalRevision != revision) publish() } }
    }

    /** Invoke on the diagnostic journal worker. No camera queries, coordinates or private file paths. */
    fun inspect(context: Context, sessionId: String): JsonObject = buildJsonObject {
        val current = state.value.filter { it.recordingSessionId == sessionId }
        val values = if (current.isNotEmpty()) current else runCatching {
            val file = AtomicFile(File(context.filesDir, "recordings/channel-health.json"))
            file.openRead().use { input ->
                require(input.channel.size() <= 32 * 1024)
                json.decodeFromString<List<RecordingChannelHealth>>(input.bufferedReader().readText())
                    .takeLast(9).filter { it.recordingSessionId == sessionId }
            }
        }.getOrDefault(emptyList())
        put("status", if (values.isEmpty()) "NOT_RECORDED" else "OBSERVED")
        put("channels", JsonArray(values.take(3).map { json.encodeToJsonElement(it) }))
    }
}
