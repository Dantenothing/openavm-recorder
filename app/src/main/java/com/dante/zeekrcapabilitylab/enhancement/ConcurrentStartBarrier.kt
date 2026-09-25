package com.dante.zeekrcapabilitylab.enhancement

/**
 * Pure protocol for the explicitly started, bounded concurrent camera experiment.
 * An external owner must atomically reserve every camera before constructing it.
 * All events run on one control thread. Device keys identify actual object lifetimes.
 * Existing per-channel close transactions still own native producer/consumer release.
 * DEVICES_CLOSED proves only the device/open ledger is settled, not that encoders,
 * files, consumers or global camera reservations can already be released.
 */
class ConcurrentStartBarrier(val runToken: String, cameraIds: Set<String>, private val usbIdentity: String) {
    enum class Phase { OPENING, CONFIGURING, AWAITING_PROGRESS, RECORDING, STOPPING, CLEANUP_UNKNOWN, DEVICES_CLOSED }
    enum class OpenAction { WAIT, CONFIGURE_ALL, CLOSE_DEVICE, DUPLICATE }
    enum class StopReason { USER, USB_LOST, OPEN_FAILED, CHANNEL_FAILED, DEADLINE, UNEXPECTED_DEVICE }
    private enum class OpenState { PENDING, OPEN, FAILED_WITHOUT_DEVICE, CLOSED }
    private val opens = cameraIds.associateWith { OpenState.PENDING }.toMutableMap()
    private val devices = mutableMapOf<String, String>() // actual device key -> cameraId
    private val primary = mutableMapOf<String, String>()
    private val ready = mutableSetOf<String>()
    private val progressing = mutableSetOf<String>()
    private val reasons = linkedSetOf<StopReason>()
    var phase = Phase.OPENING; private set
    var configurationEpoch = 0L; private set
    val stopReasons: Set<StopReason> get() = reasons.toSet()
    val ownedDevices: Set<String> get() = devices.keys.toSet()
    val pendingOpenIds: Set<String> get() = opens.filterValues { it == OpenState.PENDING }.keys.toSet()
    val mayConfigure: Boolean get() = reasons.isEmpty() && phase == Phase.CONFIGURING
    val maySubmitCapture: Boolean get() = reasons.isEmpty() && phase in setOf(Phase.AWAITING_PROGRESS, Phase.RECORDING)
    val mayCreateUsbOutput: Boolean get() = reasons.isEmpty() && phase in setOf(Phase.OPENING, Phase.CONFIGURING, Phase.AWAITING_PROGRESS, Phase.RECORDING)

    init {
        require(runToken.isNotBlank() && usbIdentity.isNotBlank())
        require(cameraIds.size in 2..3 && cameraIds.none(String::isBlank))
    }

    fun opened(token: String, cameraId: String, deviceKey: String): OpenAction {
        require(deviceKey.isNotBlank())
        // A foreign run belongs to its old owner; this run may only tell the adapter to close it.
        if (token != runToken || cameraId !in opens) return OpenAction.CLOSE_DEVICE
        if (devices[deviceKey] == cameraId) return OpenAction.DUPLICATE
        if (deviceKey in devices) { stop(StopReason.UNEXPECTED_DEVICE); return OpenAction.CLOSE_DEVICE }
        devices[deviceKey] = cameraId
        if (opens[cameraId] != OpenState.PENDING) {
            // A contradictory late object creates a new cleanup obligation even
            // after earlier terminal evidence. It must never restore a permit.
            if (phase == Phase.DEVICES_CLOSED) phase = Phase.CLEANUP_UNKNOWN
            stop(StopReason.UNEXPECTED_DEVICE)
            return OpenAction.CLOSE_DEVICE
        }
        primary[cameraId] = deviceKey
        opens[cameraId] = OpenState.OPEN
        if (reasons.isNotEmpty()) return OpenAction.CLOSE_DEVICE
        if (opens.values.all { it == OpenState.OPEN }) {
            phase = Phase.CONFIGURING
            configurationEpoch++
            return OpenAction.CONFIGURE_ALL
        }
        return OpenAction.WAIT
    }

    /** Only use when the platform guarantees this attempt produced no device. Timeout is not failure evidence. */
    fun failedWithoutDevice(token: String, cameraId: String) {
        if (token != runToken || opens[cameraId] != OpenState.PENDING) return
        opens[cameraId] = OpenState.FAILED_WITHOUT_DEVICE
        stop(StopReason.OPEN_FAILED)
        settle()
    }

    fun consumerReady(token: String, epoch: Long, cameraId: String, deviceKey: String) {
        if (token != runToken || epoch != configurationEpoch || !mayConfigure || primary[cameraId] != deviceKey) return
        ready += cameraId
        if (ready.size == opens.size) phase = Phase.AWAITING_PROGRESS
    }

    /** Progress means a real encoded sample accepted by the writer, not a configured/FPS counter. */
    fun writtenSample(token: String, epoch: Long, cameraId: String, deviceKey: String) {
        if (token != runToken || epoch != configurationEpoch || !maySubmitCapture || primary[cameraId] != deviceKey) return
        progressing += cameraId
        if (progressing.size == opens.size) phase = Phase.RECORDING
    }

    fun observeUsb(identity: String?) { if (identity != usbIdentity) stop(StopReason.USB_LOST) }

    fun stop(reason: StopReason) {
        reasons += reason
        if (phase != Phase.DEVICES_CLOSED && phase != Phase.CLEANUP_UNKNOWN) phase = Phase.STOPPING
        settle()
    }

    fun cleanupDeadline() {
        stop(StopReason.DEADLINE)
        if (phase != Phase.DEVICES_CLOSED) phase = Phase.CLEANUP_UNKNOWN
    }

    /** Called only on that actual CameraDevice.onClosed, never on session onClosed or close() return. */
    fun deviceClosed(token: String, deviceKey: String) {
        if (token != runToken) return
        val id = devices.remove(deviceKey) ?: return
        if (primary[id] == deviceKey) opens[id] = OpenState.CLOSED
        if (reasons.isEmpty()) stop(StopReason.CHANNEL_FAILED)
        settle()
    }

    private fun settle() {
        if (reasons.isNotEmpty() && devices.isEmpty() && opens.values.all {
            it == OpenState.FAILED_WITHOUT_DEVICE || it == OpenState.CLOSED
        }) phase = Phase.DEVICES_CLOSED
    }
}
