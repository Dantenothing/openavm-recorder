package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.diagnostic.RecordingGroupFailureEvidence
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

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
    private var currentContext: Context? = null
    private var revoked = false
    private var metadataBusy = false
    private var metadataGeneration = 0L
    private var tickScheduled = false
    private var writePending = false
    private var journalRevision = 0L
    private var primarySettleUntilMs = 0L
    private var primaryReferenceReleased = false
    private var startGroup: StartGroup? = null

    // One start intent on the existing control queue, not another camera/resource owner.
    // Every actual CameraDevice, encoder, file and late close stays with RecorderSession.
    private class Member(val config: RecorderConfig, val session: RecorderSession, var prepared: Boolean = false) {
        var reservation = Any()
    }
    private class StartGroup(val config: RecorderConfig, val primary: RecorderSession,
                             val storage: RecordingStorageIdentity, val identity: RecordingChannelIdentity,
                             val closeTrial: TerminalCloseTrial?) {
        val members = linkedMapOf(config.cameraId to Member(config, primary, true))
        val opened = mutableSetOf<String>()
        var submitted = false
        var cancelled = false
        var deadline: Runnable? = null
    }

    private class Owner(val identity: RecordingChannelIdentity, val session: RecorderSession, val requestedAt: Long, val token: Any) {
        var closing = false
        var lastState = RecorderState(status = RecorderStatus.STARTING)
    }

    /** Freeze auxiliary configuration and storage before opening any member of the requested set. */
    fun start(context: Context, config: RecorderConfig, primary: RecorderSession, preview: Surface? = null,
              requiredStorage: RecordingStorageIdentity? = null, previewReleased: (() -> Unit)? = null,
              closeTrial: TerminalCloseTrial? = null) {
        if (RecordingChannelPolicy.roles(config).isEmpty()) {
            primary.start(config, preview, requiredStorage, previewReleased = previewReleased, closeTrial = closeTrial)
            return
        }
        var requestedGroup: StartGroup? = null
        primary.start(config, preview, requiredStorage, previewReleased = previewReleased, closeTrial = closeTrial, prepared = { storage, identity ->
            follow(context, config, primary.stateSnapshot(), primary)
            control.post {
                if (!primary.preparedStartAllowed(identity.sessionId) || revoked || activeSession != identity.sessionId) return@post
                startGroup?.let { cancelStart(it, "GROUP_REPLACED", stopPrimary = true) }
                val group = StartGroup(config, primary, storage, identity, closeTrial)
                requestedGroup = group
                startGroup = group
                begin(context.applicationContext, group)
            }
        }, groupEnded = { id, error -> control.post {
            startGroup?.takeIf { it.identity.sessionId == id }?.let { cancelStart(it, error, stopPrimary = true) }
        } }, groupStorageLost = { id, storage, reason -> control.post {
            requestedGroup?.takeIf { it.identity.sessionId == id }?.let { storageLost(it, storage, reason) }
        } })
    }

    /** Caller supplies cached evidence only; this method never waits for cameras, SDK getters or storage. */
    fun follow(context: Context, config: RecorderConfig?, primary: RecorderState, recorder: RecorderSession) {
        val app = context.applicationContext
        control.post {
            val id = primary.recordingSessionId ?: return@post
            currentContext = app
            if (activeSession != id) {
                startGroup?.let { cancelStart(it, "GROUP_REPLACED", stopPrimary = true) }
                owners.values.toList().forEach(::release)
                activeSession = id; revoked = false; metadataGeneration++; primarySettleUntilMs = 0L
                primaryReferenceReleased = false
                history.getOrPut(id) { linkedMapOf() }
                while (history.size > 3) history.remove(history.keys.first())
                config?.let(RecordingChannelPolicy::roles)?.forEach { role ->
                    put(RecordingChannelHealth(id, RecordingChannelId.forRole(role)))
                }
            }
            primaryState = primary
            if (!primaryReferenceReleased) {
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
            releasePrimaryReferenceIfDone()
            publish(); scheduleTick()
        }
    }

    fun stop(sessionId: String?) {
        control.post {
            startGroup?.takeIf { sessionId == null || it.identity.sessionId == sessionId }?.let {
                // The Service owns the primary Stop/mode-switch operation. Do not race its handoff.
                cancelStart(it, null, stopPrimary = false)
            }
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

    private fun begin(context: Context, group: StartGroup) {
        val config = group.config
        val id = group.identity.sessionId
        val origin = group.identity.sessionStartedElapsedMs
        val roles = RecordingChannelPolicy.roles(config)
        if (metadataBusy) {
            roles.forEach { unavailable(id, it, "CAMERA_METADATA_BUSY") }
            cancelStart(group, "CAMERA_METADATA_BUSY", stopPrimary = true)
            return
        }
        val generation = ++metadataGeneration
        metadataBusy = true
        val timeout = Runnable {
            if (startGroup === group && !group.cancelled) {
                roles.filter { role -> health(RecordingChannelIdentity(id, origin, RecordingChannelId.forRole(role))).writtenFrames == 0L }
                    .forEach { unavailable(id, it, "GROUP_START_TIMEOUT") }
                cancelStart(group, "GROUP_START_TIMEOUT", stopPrimary = true)
            }
        }
        group.deadline = timeout
        control.postDelayed(timeout, RecordingChannelPolicy.START_TIMEOUT_MS)
        metadata.post {
            val resolved = roles.associateWith { role -> runCatching {
                val source = requireNotNull(ProductRecorderConfigFactory.resolveSource(context, role)) { "SOURCE_UNAVAILABLE" }
                require(source.cameraId != config.cameraId) { "CAMERA_ALREADY_PRIMARY" }
                val profile = requireNotNull(CabinRecordingProfile.choose(
                    CameraRuntime.videoSizeCandidates(context, source.cameraId).map { ProfileSize(it.width, it.height) }
                )) { "CABIN_SIZE_UNAVAILABLE" }
                RecordingChannelPolicy.configuration(config, source, profile, group.storage.kind)
            } }
            control.post {
                metadataBusy = false
                if (generation != metadataGeneration || startGroup !== group || group.cancelled || revoked) return@post
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
                    val session = runCatching { group.primary.auxiliarySession({ value -> control.post {
                        if (owners[cfg.cameraId] !== owner) return@post
                        owner.lastState = value
                        updateOwner(owner)
                        if (value.lastError != null || value.cleanupUnconfirmed || value.status in
                            setOf(RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE, RecorderStatus.WAITING_CAMERA)) {
                            cancelStart(group, value.lastError ?: "GROUP_CHANNEL_INTERRUPTED", stopPrimary = true)
                            release(owner)
                        }
                        publish()
                    } }, { control.post { if (owners[cfg.cameraId] === owner) {
                        cancelStart(group, owner.lastState.lastError ?: "GROUP_CHANNEL_STOPPED", stopPrimary = true)
                        release(owner)
                    } } }) }.getOrNull()
                    if (session == null) {
                        admission.release(cfg.cameraId, token, true)
                        unavailable(id, role, "AUXILIARY_OWNER_UNAVAILABLE")
                        continue
                    }
                    owner = Owner(identity, session, SystemClock.elapsedRealtime(), token)
                    owners[cfg.cameraId] = owner
                    group.members[cfg.cameraId] = Member(cfg, session)
                    session.start(cfg, requiredStorage = group.storage, sharedIdentity = identity, closeTrial = group.closeTrial,
                        prepared = { storage, _ -> control.post {
                            if (group.cancelled || startGroup !== group) return@post
                            if (!RecordingModeSwitchGate.storageMatches(group.storage, storage)) {
                                cancelStart(group, "GROUP_STORAGE_CHANGED", stopPrimary = true)
                            } else {
                                group.members.getValue(cfg.cameraId).prepared = true
                                openPreparedSet(group)
                            }
                        } }, groupEnded = { _, error -> control.post {
                            cancelStart(group, error, stopPrimary = true)
                        } }, groupStorageLost = { _, storage, reason -> control.post {
                            storageLost(group, storage, reason)
                        } })
                }
                openPreparedSet(group)
                publish(); scheduleTick()
            }
        }
    }

    private fun openPreparedSet(group: StartGroup) {
        if (startGroup !== group || group.cancelled || group.submitted || group.members.values.any { !it.prepared }) return
        val id = group.identity.sessionId
        if (group.members.values.any { !it.session.preparedStartAllowed(id) }) {
            cancelStart(group, "GROUP_START_REVOKED", stopPrimary = true); return
        }
        val retained = group.primary.retainedReservation(group.config.cameraId)
        if (retained != null) group.members.getValue(group.config.cameraId).reservation = retained
        val tokens = group.members.values.associate { it.reservation to it.config.cameraId }
        val reserved = if (tokens.size == 1) CanaryCameraInterlock.beginNormalOpen(tokens.keys.single(), tokens.values.single())
            else CanaryCameraInterlock.beginNormalGroup(tokens, retained?.let { setOf(it) } ?: emptySet())
        if (!reserved) { cancelStart(group, "CAMERA_RELEASE_PENDING", stopPrimary = true); return }
        group.submitted = true
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_GROUP_OPEN_REQUEST", payload = mapOf(
            "recordingSessionId" to id, "cameraIds" to group.members.keys.joinToString(",")))
        // Every reservation is handed to exactly one camera worker. Only an unsubmitted
        // request or its own adapter's real onClosed may release that reservation.
        group.members.forEach { (cameraId, member) ->
            member.session.openPreparedCamera(id, member.reservation) { control.post {
                if (startGroup !== group || group.cancelled || !group.opened.add(cameraId)) return@post
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_GROUP_MEMBER_OPENED", payload = mapOf(
                    "recordingSessionId" to id, "cameraId" to cameraId))
                if (group.opened.size == group.members.size) {
                    group.deadline?.let(control::removeCallbacks); group.deadline = null
                    EventLogger.logEvent(Categories.SYSTEM, "RECORDER_GROUP_ALL_OPENED", payload = mapOf("recordingSessionId" to id))
                    group.members.values.forEach { it.session.configurePreparedCamera(id) }
                }
            } }
        }
    }

    private fun storageLost(group: StartGroup, storage: RecordingStorageIdentity, reason: String) {
        if (storage.kind != RecordingStorageKind.USB_MEDIASTORE ||
            !RecordingModeSwitchGate.storageMatches(group.storage, storage)) return
        // Only latch loss here; no native close, provider call or wait on the control queue.
        // Mark ALL members before any stop can reach a queued recorder/muxer finalization.
        // A late loss after manual Stop still merges into its existing cleanup transaction.
        group.members.values.forEach { it.session.markConcurrentStorageLost(group.identity.sessionId, storage) }
        cancelStart(group, reason, stopPrimary = true)
    }

    private fun cancelStart(group: StartGroup, error: String?, stopPrimary: Boolean) {
        if (group.cancelled) return
        group.cancelled = true
        if (error != null) {
            // Freeze every member before stop/release can erase encoder state or create teardown failures.
            val context = currentContext
            val snapshot = runCatching { RecordingGroupFailureEvidence.capture(
                group.identity.sessionId, error, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                BuildConfig.VERSION_NAME, group.members.values.map { member ->
                    RecordingGroupFailureEvidence.Member(member.config, member.session.stateSnapshot(), member.session.captureEvidence())
                }) }.getOrNull()
            // Diagnostics must never prevent a requested group Stop, including a serialization/IO failure.
            if (context != null && snapshot != null) journal.post { runCatching { RecordingChannelJournal.writeFailure(context, snapshot) } }
        }
        group.deadline?.let(control::removeCallbacks); group.deadline = null
        if (startGroup === group) {
            startGroup = null; metadataGeneration++; revoked = true
            primarySettleUntilMs = SystemClock.elapsedRealtime() + 20_000L
        }
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_GROUP_STOP", payload = mapOf(
            "recordingSessionId" to group.identity.sessionId, "errorCode" to (error ?: "NONE")))
        if (stopPrimary) group.primary.stopConcurrentGroup(group.identity.sessionId, error)
        owners.values.filter { it.identity.sessionId == group.identity.sessionId }.toList().forEach(::release)
        publish(); scheduleTick()
    }

    private fun unavailable(id: String, role: RecordingSourceRole, reason: String) {
        put(RecordingChannelHealth(id, RecordingChannelId.forRole(role), status = "UNAVAILABLE", errorCode = reason))
    }

    private fun release(owner: Owner) {
        if (owner.closing) return
        startGroup?.takeIf { !it.cancelled && it.identity.sessionId == owner.identity.sessionId }?.let {
            // Revoke the whole intent before an individual member starts native teardown.
            cancelStart(it, health(owner.identity).errorCode ?: "GROUP_CHANNEL_INTERRUPTED", stopPrimary = true)
            return
        }
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

    private fun releasePrimaryReferenceIfDone() {
        if (primaryRecorder == null || RecordingChannelRetention.retainPrimary(revoked, primaryState.status,
                primaryState.cleanupPending, primaryState.cleanupUnconfirmed, primarySettleUntilMs,
                SystemClock.elapsedRealtime())) return
        // This is only the presentation observer. Native/IO ownership remains with the
        // recorder's existing close transaction and CaptureCleanupRuntime until acknowledged.
        primaryRecorder = null
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
        journal.post { runCatching { RecordingChannelJournal.write(context, saved) }
            control.post { writePending = false; if (journalRevision != revision) publish() } }
    }

    /** Invoke on the diagnostic journal worker. No camera queries, coordinates or private file paths. */
    fun inspect(context: Context, sessionId: String): JsonObject = buildJsonObject {
        val current = state.value.filter { it.recordingSessionId == sessionId }
        val values = if (current.isNotEmpty()) current else RecordingChannelJournal.read(context).channels
            .filter { it.recordingSessionId == sessionId }
        put("status", if (values.isEmpty()) "NOT_RECORDED" else "OBSERVED")
        put("channels", JsonArray(values.take(3).map { json.encodeToJsonElement(it) }))
    }
}
