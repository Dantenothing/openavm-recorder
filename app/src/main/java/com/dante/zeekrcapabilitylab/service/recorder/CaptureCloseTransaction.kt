package com.dante.zeekrcapabilitylab.service.recorder

/** Shared by Android adapters and deterministic test queues. */
interface CloseDispatcher {
    fun execute(action: () -> Unit): Boolean
    fun after(delayMs: Long, action: () -> Unit): () -> Unit
}

interface CaptureCloseResources {
    fun stopRepeating()
    fun abortCaptures()
    fun closeSession()
    fun closeDevice()
    fun stopRecorder()
    fun resetRecorder()
    fun releaseRecorder()
    fun closeOutput(lost: Boolean)
}

/** Descriptor is confirmed closed, but durability failed; this is not an unknown camera owner. */
class OutputFlushException(cause: Throwable) : Exception(cause)

data class CaptureCloseResult(
    val safeToContinue: Boolean, val outputLost: Boolean, val terminal: Boolean,
    val producerEvidence: String?, val deviceClosed: Boolean, val errors: Map<String, String>,
)

/**
 * One owner retains native handles. Control/deadlines run separately from native
 * calls. Timeouts revoke continuation; late acknowledgements still settle cleanup.
 */
class CaptureCloseTransaction(
    private val control: CloseDispatcher, private val native: CloseDispatcher,
    private val output: CloseDispatcher, private val resources: CaptureCloseResources,
    private val hasSession: Boolean, private val hasDevice: Boolean,
    private val wasRecording: Boolean, sequences: Set<Int>, terminal: Boolean, lost: Boolean,
    private val trace: (String, String) -> Unit,
    private val unconfirmed: (String) -> Unit,
    private val completed: (CaptureCloseResult) -> Unit,
    private var preferDeviceClose: Boolean = false,
) {
    private val outstanding = sequences.toMutableSet()
    @Volatile private var lost = lost
    private var terminal = terminal
    private var stage = "NEW"
    private var timer: (() -> Unit)? = null
    private var ready = false
    private var sessionClosedAck = false
    private var evidence: String? = null
    private var deviceAck = !hasDevice
    private var sessionCloseIssued = false
    private var deviceCloseIssued = false
    private var mediaStarted = false
    private var mediaDone = false
    private var mediaReleased = false
    private var outputStarted = false
    private var outputDone = false
    private var outputClosed = false
    private var reportedFailure = false
    private var finished = false
    private val errors = linkedMapOf<String, String>()

    fun begin() = submit {
        if (stage != "NEW") return@submit
        trace("BEGIN", "terminal=$terminal lost=$lost outstanding=" + outstanding.sorted())
        if (preferDeviceClose && hasDevice) {
            requestDeviceClose()
        } else if (!hasSession) {
            if (hasDevice) requestDeviceClose() else producerEnded("NO_PRODUCER")
        } else {
            phase("DRAINING", 2_000) { requestSessionClose() }
            work(native, "STOP_PRODUCER", {
                linkedMapOf<String, String>().also {
                    attempt(it, "stopRepeating") { resources.stopRepeating() }
                    attempt(it, "abortCaptures") { resources.abortCaptures() }
                }
            }) { result ->
                errors.putAll(result)
                if (result.isNotEmpty() && !mediaStarted) requestSessionClose()
            }
        }
    }

    fun merge(terminal: Boolean = false, outputLost: Boolean = false, requireDeviceClose: Boolean = false) {
        // Revoke FD use before a previously queued native task can reach stop/reset.
        if (outputLost) lost = true
        submit {
        if (finished) return@submit
        this.terminal = this.terminal || terminal
        lost = lost || outputLost
        if (requireDeviceClose) {
            preferDeviceClose = true
            requestDeviceClose()
        }
        trace("INTENT_MERGED", "terminal=" + this.terminal + " lost=" + lost)
        if (outputDone && !deviceAck && this.terminal) requestDeviceClose()
        }
    }

    fun sequenceEnded(id: Int) = submit {
        if (outstanding.remove(id)) trace("SEQUENCE_ENDED", id.toString())
        if (outstanding.isEmpty()) {
            if (sessionClosedAck) producerEnded("CLOSED_AND_SEQUENCES_ENDED")
            else if (ready) producerEnded("READY_AND_SEQUENCES_ENDED")
        }
    }
    fun sessionReady() = submit {
        ready = true
        if (outstanding.isEmpty()) producerEnded("SESSION_READY")
        else trace("READY_WAITING_FOR_SEQUENCES", outstanding.sorted().toString())
    }
    fun sessionClosed() = submit {
        if (finished || sessionClosedAck) return@submit
        sessionClosedAck = true
        sessionCloseIssued = true
        // Camera2 onClosed stops repeating requests, but in-flight captures may
        // still complete. Never release their encoder/output on this callback alone.
        if (outstanding.isEmpty()) {
            producerEnded("CLOSED_AND_SEQUENCES_ENDED")
        } else if (!mediaStarted && !deviceCloseIssued) {
            trace("CLOSED_WAITING_FOR_SEQUENCES", outstanding.sorted().toString())
            phase("DRAINING_CLOSED_SESSION", 2_000) { fail("CAPTURE_DRAIN_UNCONFIRMED") }
        }
    }
    fun deviceClosed() = submit {
        if (deviceAck) return@submit
        deviceAck = true
        trace("DEVICE_CLOSED", "acknowledged")
        outstanding.clear()
        if (!mediaStarted) producerEnded("CAMERA_DEVICE_CLOSED")
        else if (mediaDone) { if (outputDone) finish() else finishOutput() }
    }

    private fun submit(action: () -> Unit) {
        if (!control.execute(action)) unconfirmed("CLEANUP_CONTROL_REJECTED")
    }
    private fun producerEnded(value: String) {
        if (mediaStarted || finished) return
        // Another session can still use this recorder's surface. Evidence about
        // the current session cannot replace the explicitly requested device fence.
        if (preferDeviceClose && hasDevice && !deviceAck) {
            trace("WAITING_FOR_DEVICE_FENCE", value)
            return
        }
        evidence = value
        trace("PRODUCER_CONFIRMED", value)
        mediaStarted = true
        phase("RELEASING_RECORDER", 8_000) { fail("RECORDER_RELEASE_TIMEOUT") }
        work(native, "RECORDER", {
            linkedMapOf<String, String>().also {
                if (wasRecording && !lost) attempt(it, "stop") { resources.stopRecorder() }
                // A bad output must not go through the normal stop/reset path.
                if (!lost) attempt(it, "reset") { resources.resetRecorder() }
                attempt(it, "release") { resources.releaseRecorder() }
            }
        }) { result ->
            errors.putAll(result)
            mediaDone = true
            mediaReleased = "release" !in result
            if (!mediaReleased) fail("RECORDER_RELEASE_UNCONFIRMED")
            closeSessionAfterMedia()
        }
    }
    private fun requestSessionClose() {
        if (mediaStarted || finished || sessionCloseIssued) return
        sessionCloseIssued = true
        phase("CLOSING_SESSION", 2_000) { requestDeviceClose() }
        work(native, "SESSION_CLOSE", {
            linkedMapOf<String, String>().also { attempt(it, "sessionClose") { resources.closeSession() } }
        }) { result ->
            errors.putAll(result)
            if (result.isNotEmpty() && !mediaStarted) requestDeviceClose()
        }
    }
    private fun closeSessionAfterMedia() {
        if (sessionCloseIssued || !hasSession) { afterSessionClose(); return }
        sessionCloseIssued = true
        phase("CLOSING_SESSION_AFTER_MEDIA", 3_000) { fail("SESSION_CLOSE_CALL_TIMEOUT") }
        work(native, "SESSION_CLOSE", {
            linkedMapOf<String, String>().also { attempt(it, "sessionClose") { resources.closeSession() } }
        }) { result -> errors.putAll(result); afterSessionClose() }
    }
    private fun afterSessionClose() {
        if (terminal || lost || !mediaReleased || "sessionClose" in errors || deviceCloseIssued) {
            if (deviceAck) finishOutput() else requestDeviceClose()
        } else finishOutput()
    }
    private fun requestDeviceClose() {
        if (finished) return
        if (!hasDevice) {
            if (hasSession && !mediaStarted) fail("SESSION_CLOSE_UNCONFIRMED_NO_DEVICE")
            else if (!mediaStarted) producerEnded("NO_PRODUCER") else finishOutput()
            return
        }
        if (deviceAck) { if (mediaDone) finishOutput(); return }
        if (deviceCloseIssued) return
        deviceCloseIssued = true
        phase("CLOSING_DEVICE", 3_000) { fail("CAMERA_CLOSE_UNCONFIRMED") }
        work(native, "DEVICE_CLOSE", {
            linkedMapOf<String, String>().also { attempt(it, "deviceClose") { resources.closeDevice() } }
        }) { result ->
            errors.putAll(result)
            if (result.isNotEmpty()) fail("CAMERA_CLOSE_FAILED")
            // Returning from close is not an acknowledgement.
        }
    }
    private fun finishOutput() {
        if (!mediaDone || outputStarted || finished) return
        if ((terminal || lost || deviceCloseIssued || !mediaReleased) && !deviceAck) {
            requestDeviceClose(); return
        }
        outputStarted = true
        phase("CLOSING_OUTPUT", 8_000) { fail("OUTPUT_CLOSE_TIMEOUT") }
        work(output, "OUTPUT_CLOSE", {
            linkedMapOf<String, String>().also { attempt(it, "outputClose") { resources.closeOutput(lost) } }
        }) { result ->
            errors.putAll(result)
            outputDone = true
            outputClosed = "outputClose" !in result
            if (!outputClosed) fail("OUTPUT_CLOSE_UNCONFIRMED")
            else if ("outputSync" in result) fail("OUTPUT_FLUSH_FAILED")
            if ((terminal || lost) && !deviceAck) requestDeviceClose() else finish()
        }
    }
    private fun finish() {
        if (finished || !outputDone) return
        if ((terminal || lost || deviceCloseIssued) && !deviceAck) return
        finished = true
        timer?.invoke(); timer = null
        val safe = evidence != null && mediaReleased && outputClosed &&
            (!(terminal || lost || deviceCloseIssued) || deviceAck)
        trace("END", "safe=$safe terminal=$terminal lost=$lost deviceAck=$deviceAck")
        completed(CaptureCloseResult(safe, lost, terminal || reportedFailure, evidence, deviceAck, errors.toMap()))
    }
    private fun fail(reason: String) {
        if (finished || reportedFailure) return
        reportedFailure = true
        terminal = true
        trace("UNCONFIRMED", "$stage:$reason")
        unconfirmed(reason)
        if (hasDevice && !deviceAck && !deviceCloseIssued) requestDeviceClose()
    }
    private fun phase(value: String, timeoutMs: Long, expired: () -> Unit) {
        timer?.invoke()
        stage = value
        trace("STAGE", value)
        timer = control.after(timeoutMs) {
            if (!finished && stage == value) { trace("TIMEOUT", value); expired() }
        }
    }
    private fun <T> work(dispatcher: CloseDispatcher, name: String, action: () -> T, done: (T) -> Unit) {
        trace("CALL_QUEUED", name)
        if (!dispatcher.execute {
                trace("CALL_STARTED", name)
                val value = action()
                submit { trace("CALL_RETURNED", name); done(value); if (outputDone && deviceAck) finish() }
            }) fail(name + "_DISPATCH_REJECTED")
    }
    private fun attempt(result: MutableMap<String, String>, name: String, action: () -> Unit) {
        trace("NATIVE_ENTER", name)
        try { action(); trace("NATIVE_RETURN", name) } catch (error: Throwable) {
            val key = if (name == "outputClose" && error is OutputFlushException) "outputSync" else name
            result[key] = (error.javaClass.simpleName + ":" + error.message.orEmpty()).take(240)
            trace("NATIVE_THROW", name + ":" + result[key])
        }
    }
}
