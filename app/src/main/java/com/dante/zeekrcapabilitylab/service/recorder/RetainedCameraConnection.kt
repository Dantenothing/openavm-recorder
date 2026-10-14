package com.dante.zeekrcapabilitylab.service.recorder

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter

/** Owns one actual connection across finite, explicit preview/record handoffs.
 * Producer sessions, encoders and Surfaces remain owned by their original workers.
 * Only a fully acknowledged producer release may park the connection here. */
internal object RetainedCameraConnection {
    private data class Owner(val callback: CameraDevice.StateCallback, val handler: Handler)
    private class Slot(val lease: CameraDeviceLease<CameraDevice, Owner>) {
        var retirement: CaptureCloseTransaction? = null
    }
    private val lock = Any()
    private val callbacks by lazy { Handler(HandlerThread("retained-camera-callbacks").apply { start() }.looper) }
    private val nativeClose by lazy { Handler(HandlerThread("retained-camera-close").apply { start() }.looper) }
    private var current: Slot? = null
    val enabled get() = BuildConfig.RETAINED_CAMERA_HANDOFF_ENABLED

    fun open(manager: CameraManager, cameraId: String, ticket: String,
             callback: CameraDevice.StateCallback, destination: Handler, reservation: Any? = null) {
        check(enabled)
        val owner = Owner(callback, destination)
        var reused: CameraDevice? = null
        val slot = synchronized(lock) {
            val previous = current
            if (previous != null) {
                check(reservation == null || reservation === previous.lease.reservation) { "RETAINED_RESERVATION_MISMATCH" }
                reused = previous.lease.claim(ticket, cameraId, SystemClock.elapsedRealtime(), owner)
                check(reused != null) { "RETAINED_CAMERA_BUSY" }
                previous
            } else {
                val token = reservation ?: Any().also {
                    check(CanaryCameraInterlock.beginNormalOpen(it, cameraId)) { "CAMERA_RELEASE_PENDING" }
                }
                check(CanaryCameraInterlock.ownsNormalReservation(token, cameraId)) { "CAMERA_RESERVATION_MISSING" }
                Slot(CameraDeviceLease(cameraId, token, owner)).also { current = it }
            }
        }
        reused?.let { camera ->
            log("LEASE_ATTACHED", slot)
            // Consumer admission, not a fabricated native onClosed/open cycle. The
            // callback gives the new producer its device; diagnostics distinguish it.
            deliver(slot, camera, owner) { it.onOpened(camera) }
            return
        }
        log("NATIVE_OPEN_REQUESTED", slot)
        try {
            CanaryCameraOpenAdapter.openReserved(manager, cameraId, slot.lease.reservation,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val target = synchronized(lock) { slot.lease.opened(camera); slot.lease.owner }
                        log("NATIVE_OPENED", slot)
                        if (target != null) deliver(slot, camera, target) { it.onOpened(camera) }
                        else retire(slot, camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) = fault(slot, camera) { it.onDisconnected(camera) }
                    override fun onError(camera: CameraDevice, error: Int) = fault(slot, camera) { it.onError(camera, error) }
                    override fun onClosed(camera: CameraDevice) {
                        val target = synchronized(lock) {
                            slot.lease.closeAcknowledged(camera).also { if (current === slot) current = null }
                        }
                        log("NATIVE_CLOSED", slot)
                        slot.retirement?.deviceClosed()
                        // Native acknowledgement already reached CaptureCleanupRuntime in
                        // the adapter. Never substitute a lease handoff for this callback.
                        target?.handler?.post { target.callback.onClosed(camera) }
                    }
                }, callbacks)
        } catch (error: Throwable) {
            synchronized(lock) { if (current === slot) current = null }
            throw error
        }
    }

    private fun deliver(slot: Slot, camera: CameraDevice, owner: Owner, action: (CameraDevice.StateCallback) -> Unit) {
        if (!owner.handler.post {
            val stillOwned = synchronized(lock) { current === slot && slot.lease.owner === owner }
            if (stillOwned) action(owner.callback)
        }) {
            synchronized(lock) { slot.lease.fault(camera) }
            retire(slot, camera)
        }
    }
    private fun fault(slot: Slot, camera: CameraDevice, action: (CameraDevice.StateCallback) -> Unit) {
        val owner = synchronized(lock) { slot.lease.fault(camera); slot.lease.owner }
        log("NATIVE_FAULT", slot)
        if (owner != null) deliver(slot, camera, owner, action) else retire(slot, camera)
    }

    fun expect(cameraId: String, ticket: String, deadlineMs: Long): Boolean {
        val accepted = synchronized(lock) {
            current?.lease?.expect(cameraId, ticket, SystemClock.elapsedRealtime(), deadlineMs) == true
        }
        if (accepted) callbacks.postDelayed({ cancel(ticket) }, (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return accepted
    }
    fun prepare(camera: CameraDevice): CameraDeviceLease.Transfer? = synchronized(lock) {
        current?.lease?.prepare(camera, SystemClock.elapsedRealtime())
    }

    /** Caller relinquishes its handle only here, after CaptureCloseTransaction's real fences. */
    fun producerReleased(camera: CameraDevice, transfer: CameraDeviceLease.Transfer, healthy: Boolean): Boolean {
        val slot: Slot
        val parked: Boolean
        synchronized(lock) {
            slot = current?.takeIf { it.lease.device === camera } ?: return false
            parked = slot.lease.producerReleased(camera, transfer, SystemClock.elapsedRealtime(), healthy)
        }
        log(if (parked) "PRODUCER_RELEASED_RETAINED" else "PRODUCER_RELEASED_RETIRING", slot)
        if (!parked) retire(slot, camera)
        return true // Broker now owns the handle, including confirmed-close retirement.
    }
    fun reservation(ticket: String?, cameraId: String): Any? = synchronized(lock) {
        current?.lease?.takeIf { ticket != null && it.canClaim(ticket, cameraId, SystemClock.elapsedRealtime()) }?.reservation
    }
    fun ownsReservation(token: Any): Boolean = synchronized(lock) { current?.lease?.reservation === token }
    fun idle(): Boolean = synchronized(lock) { current == null && CanaryCameraInterlock.normalIdle() }
    fun ready(ticket: String?, cameraId: String?): Boolean = synchronized(lock) {
        val lease = current?.lease ?: return false
        ticket != null && cameraId != null && lease.canClaim(ticket, cameraId, SystemClock.elapsedRealtime()) &&
            CanaryCameraInterlock.normalIdleExcept(lease.reservation)
    }
    fun cancel(ticket: String? = null) {
        val retiring = synchronized(lock) {
            current?.takeIf { it.lease.cancel(ticket) }?.let { slot -> slot.lease.device?.let { slot to it } }
        }
        retiring?.let { retire(it.first, it.second) }
    }
    fun discardIfDifferent(ticket: String, cameraId: String) {
        val mismatch = synchronized(lock) {
            current?.lease?.let { it.transfer?.ticket == ticket && it.cameraId != cameraId } == true
        }
        if (mismatch) cancel(ticket)
    }
    private fun retire(slot: Slot, camera: CameraDevice) {
        synchronized(lock) {
            if (slot.retirement != null || slot.lease.phase == CameraDeviceLease.Phase.CLOSED) return
            val hold = Any()
            val tx = CaptureCloseTransaction(CaptureCleanupRuntime.control, HandlerCloseDispatcher(nativeClose),
                HandlerCloseDispatcher(nativeClose), object : CaptureCloseResources {
                    override fun closeDevice() { camera.close() }
                    override fun stopRepeating() = Unit
                    override fun abortCaptures() = Unit
                    override fun closeSession() = Unit
                    override fun stopRecorder() = Unit
                    override fun resetRecorder() = Unit
                    override fun releaseRecorder() = Unit
                    override fun closeOutput(lost: Boolean) = Unit
                }, false, true, false, emptySet(), true, false,
                trace = { step, detail -> CaptureCleanupRuntime.trace("retained-${System.identityHashCode(slot)}", step, detail) },
                unconfirmed = { log("RETIREMENT_UNCONFIRMED", slot) },
                completed = { if (it.safeToContinue) { CaptureCleanupRuntime.settled(hold, true); log("RETIREMENT_CONFIRMED", slot) } },
                preferDeviceClose = true)
            slot.retirement = tx
            CaptureCleanupRuntime.retain(hold, slot.lease.cameraId, camera, tx)
            tx.begin()
        }
    }
    private fun log(stage: String, slot: Slot) {
        EventLogger.logEvent(Categories.SYSTEM, "CAMERA_CONNECTION_$stage", payload = mapOf(
            "cameraId" to slot.lease.cameraId, "connection" to System.identityHashCode(slot).toString(),
            "nativeDevice" to (slot.lease.device?.let { System.identityHashCode(it).toString() } ?: "NONE")))
    }
}
