package com.dante.zeekrcapabilitylab.service.recorder

/** The original MediaRecorder configuration, with native byte-limit file handoffs. No new camera input. */
internal class NativeFileRecordingEncoder(
    first: UsbMediaStoreRecordingOutputHandle,
    private val onError: (RecordingEncoder, String) -> Unit,
    private val onInfo: (NativeFileRecordingEncoder, Int) -> Unit,
) : LegacyRecordingEncoder(onError) {
    override val name = "CONTINUOUS_MEDIA_RECORDER"
    val rotation = NativeFileRotation(first, NativeFileRotationPolicy.MAX_FILES)

    override fun beforePrepare(config: RecorderConfig, output: RecordingOutputHandle) {
        check(output === rotation.current)
        recorder.setMaxFileSize(NativeFileRotationPolicy.maxBytes(config.profile.bitrateBps, 60))
        recorder.setOnInfoListener { _, what, _ ->
            try { onInfo(this, what) }
            catch (failure: Throwable) { onError(this, "NATIVE_INFO_FAILED:${failure.javaClass.simpleName}") }
        }
    }

    /** Ownership is registered BEFORE calling native code: even a throwing call may have duplicated the FD. */
    fun queue(output: UsbMediaStoreRecordingOutputHandle): Boolean {
        if (!rotation.offer(output)) return false
        output.bindNext(recorder)
        return true
    }

    override fun stop() {
        rotation.stop()
        super.stop()
    }

    /** Called by the existing close transaction, on IO, after producer fencing and recorder release. */
    fun closeOutputs(lost: Boolean) {
        val failures = mutableListOf<Throwable>()
        rotation.owned.forEach { output ->
            try { if (lost) output.abandonUnavailableTarget() else output.close() }
            catch (failure: Throwable) { failures += failure }
        }
        // A descriptor-close failure must not be softened into a recoverable fsync error.
        val primary = failures.firstOrNull { it !is OutputFlushException } ?: failures.firstOrNull()
        if (primary != null) {
            failures.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }
}
