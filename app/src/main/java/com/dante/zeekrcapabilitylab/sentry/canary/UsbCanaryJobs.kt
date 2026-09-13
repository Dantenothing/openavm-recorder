package com.dante.zeekrcapabilitylab.sentry.canary

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.sentry.*
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class UsbCanaryOperation { RUN, RECOVER, CLEANUP }
@Serializable
data class UsbCanaryJobSnapshot(
    val busy: Boolean = false, val operation: String = "NONE", val result: UsbCanaryResult? = null,
    val jobId: String = "", val sourceRunId: String? = null, val sourceClipSha256: String? = null,
    val evidenceArchiveError: String? = null,
)
@Serializable
data class CombinedCanaryReport(
    val schemaVersion: Int = 2,
    val build: String,
    val sdk: Int,
    val ramAndClip: CanarySnapshot,
    val usb: UsbCanaryJobSnapshot,
)

/** One explicit USB job, after RAM capture has fully released. No camera or pre-trigger writes. */
object UsbCanaryJobs {
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(UsbCanaryJobSnapshot())
    val state = mutableState.asStateFlow()

    fun request(context: Context, operation: UsbCanaryOperation, clip: CanaryClipResult?) {
        if (!BuildConfig.SENTRY_CANARY_ENABLED || CanaryEvidenceExportJobs.state.value.busy || !busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        val jobId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val initial = UsbCanaryJobSnapshot(true, operation.name, jobId = jobId,
            sourceRunId = if (operation == UsbCanaryOperation.RUN) SentryCanaryService.state.value.runId else null,
            sourceClipSha256 = if (operation == UsbCanaryOperation.RUN) clip?.sha256 else null)
        fun evidence(snapshot: UsbCanaryJobSnapshot, completed: Boolean) = CanaryEvidenceRecord(
            id = jobId, kind = CanaryEvidenceKind.USB, build = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT,
            startedAtEpochMs = startedAt, finishedAtEpochMs = if (completed) System.currentTimeMillis() else null, usb = snapshot)
        mutableState.value = initial
        executor.execute {
            runCatching { CanaryEvidenceStore.record(app, evidence(initial, false)) }
            val ownership = Any()
            var reserved = false
            val result = try {
                check(Build.VERSION.SDK_INT >= 29) { "USB_CANARY_REQUIRES_API_29" }
                check(!SentryCanaryService.state.value.running && !CameraRecordingService.isRunning()) { "STOP_RECORDING_FIRST" }
                check(CanaryCameraInterlock.reserveCanary(ownership)) { "CAMERA_RELEASE_PENDING" }
                reserved = true
                val journal = AndroidUsbCanaryJournal(app)
                val previous = journal.read()
                val mounted = UsbExportVolumeResolver.mountedTargets(app)
                val target = if (operation == UsbCanaryOperation.RUN) mounted.singleOrNull() else {
                    mounted.firstOrNull { it.collectionUri == previous?.collectionUri }
                }
                check(target != null) { "EXACT_REMOVABLE_USB_REQUIRED" }
                check(target.volumeName.equals(target.storageUuid, true)) { "EXACT_USB_VOLUME_MAPPING_REQUIRED" }
                val source = if (operation == UsbCanaryOperation.RUN) {
                    check(clip?.containerVerified == true && clip.decodedFrameVerified && !clip.partial) { "VERIFIED_INTERNAL_CLIP_REQUIRED" }
                    val name = clip.fileName ?: error("SOURCE_CLIP_MISSING")
                    check(name.matches(Regex("[0-9a-f-]{36}\\.mp4"))) { "SOURCE_CLIP_NAME_INVALID" }
                    File(app.filesDir, "sentry-canary/clips/$name")
                } else null
                UsbMutationCoordinator.withTarget(target.storageUuid) {
                    val backend = if (Build.VERSION.SDK_INT >= 29) AndroidUsbCanaryBackend(app, source)
                        else error("USB_CANARY_REQUIRES_API_29")
                    val engine = UsbCanaryCommit(journal, backend)
                    when (operation) {
                        UsbCanaryOperation.RUN -> {
                            check(target.freeBytes?.let { it > (source?.length() ?: 0) + 64L * 1024 * 1024 } != false) { "USB_FREE_SPACE_LOW" }
                            engine.run(UsbCanaryRecord(UUID.randomUUID().toString(), target.collectionUri, app.packageName))
                        }
                        UsbCanaryOperation.RECOVER -> engine.recover()
                        UsbCanaryOperation.CLEANUP -> engine.cleanup()
                    }
                }
            } catch (error: Exception) {
                UsbCanaryResult(UsbCanaryPhase.INTENDED, reason = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                    ?: "USB_${error.javaClass.simpleName.uppercase()}")
            } finally { if (reserved) CanaryCameraInterlock.canaryClosed(ownership) }
            var snapshot = initial.copy(busy = false, result = result)
            val archiveError = runCatching { CanaryEvidenceStore.record(app, evidence(snapshot, true)) }.exceptionOrNull()
            if (archiveError != null) snapshot = snapshot.copy(evidenceArchiveError = "USB_EVIDENCE_WRITE_FAILED")
            runCatching {
                val file = AtomicFile(File(app.filesDir, "sentry-canary/usb-report.json"))
                file.baseFile.parentFile?.mkdirs()
                val output = file.startWrite()
                try {
                    output.write(SentryCanaryService.json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Throwable) { file.failWrite(output); throw error }
            }
            mutableState.value = snapshot
            busy.set(false)
        }
    }

    fun restore(context: Context) {
        val initial = mutableState.value
        if (initial.operation != "NONE" || busy.get()) return
        runCatching {
            SentryCanaryService.json.decodeFromString<UsbCanaryJobSnapshot>(File(context.filesDir, "sentry-canary/usb-report.json").readText())
        }.getOrNull()?.let { mutableState.compareAndSet(initial, it.copy(busy = false)) }
    }
}
