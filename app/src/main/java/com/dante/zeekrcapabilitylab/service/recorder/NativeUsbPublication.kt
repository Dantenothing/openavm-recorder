package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.usbexport.UsbCommittedBundleStore
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCommitEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/** Post-release USB verification must not keep a camera/service teardown waiting for many video hashes. */
internal object NativeUsbPublication {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(256), { task -> Thread(task, "native-usb-publication").apply { isDaemon = true } })
    val pending = AtomicInteger()
    val failed = AtomicInteger()
    val completed = AtomicInteger()
    val revision = MutableStateFlow(0L)

    /** Caller has obtained producer/recorder release and descriptor-close acknowledgement for this file. */
    fun submit(context: Context, output: UsbMediaStoreRecordingOutputHandle, sidecar: SegmentSidecar,
               onCommitted: () -> Unit = {}) {
        val app = context.applicationContext
        pending.incrementAndGet()
        try {
            worker.execute {
                try {
                    if (output.bytesAfterRecorderRelease() == 0L) {
                        output.clearNativeCheckpoint(); output.abort()
                    } else {
                        output.checkpointNative(sidecar)
                        val result = UsbSegmentCommitEngine(app).commitDirect(output.pendingVideo, sidecar,
                            preserveVideoOnFailure = true)
                        check(UsbCommittedBundleStore(app).mark(output.pendingVideo.target.storageUuid,
                            result.bundleId, result.observedOwnerPackage)) { "OWNERSHIP_MARK_FAILED" }
                        output.markCommitted()
                        completed.incrementAndGet()
                        runCatching(onCommitted)
                        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_USB_PUBLISHED",
                            payload = mapOf("segment" to sidecar.segmentNumber.toString()))
                    }
                } catch (failure: Throwable) {
                    // Never delete nonempty native files merely because verification/publication failed.
                    failed.incrementAndGet()
                    EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_USB_RETAINED",
                        payload = mapOf("segment" to sidecar.segmentNumber.toString(), "reason" to failure.javaClass.simpleName))
                } finally {
                    if (pending.decrementAndGet() == 0) revision.value += 1L
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.decrementAndGet(); failed.incrementAndGet()
            // The bounded worker is full. Its checkpoint remains available for next-process recovery.
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_USB_RETAINED",
                payload = mapOf("segment" to sidecar.segmentNumber.toString(), "reason" to "PUBLICATION_QUEUE_FULL"))
        }
    }
}
