package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Copies finalized recordings (and their sidecars) to a removable USB volume.
 *
 * Safety properties:
 *  - Every source file is upload-pinned for the duration of its copy, so
 *    automatic eviction and gallery deletion cannot pull it away mid-copy; a
 *    file that cannot be pinned (it is being deleted) is counted as failed and
 *    skipped, never half-copied.
 *  - Copies go to a `.part` file, are fsynced, then renamed, and a stale
 *    `.part` from an earlier unplug is removed first; [UsbExportPolicy] then
 *    treats a same-size final file as already exported, which makes repeat
 *    exports incremental.
 *  - Free space is re-checked before every file with a safety margin; running
 *    out stops the export with a clear reason instead of filling the stick.
 *  - Exports never delete or modify anything on the head unit.
 */
object UsbExporter {

    data class Progress(val done: Int, val total: Int, val currentName: String?)

    data class Result(
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val bytesCopied: Long,
        val firstError: String?,
    )

    fun exportAll(
        volume: UsbStorage.UsbVolume,
        recordings: List<File>,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val targetDir = File(volume.root, UsbExportPolicy.EXPORT_SUBDIR)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return Result(copied = 0, skipped = 0, failed = recordings.size, bytesCopied = 0L, firstError = "TARGET_DIR_CREATE_FAILED")
        }
        var copied = 0
        var skipped = 0
        var failed = 0
        var bytesCopied = 0L
        var firstError: String? = null
        recordings.forEachIndexed { index, source ->
            onProgress(Progress(done = index, total = recordings.size, currentName = source.name))
            val outcome = exportOne(source, targetDir)
            when (outcome) {
                Outcome.COPIED -> {
                    copied++
                    bytesCopied += source.length()
                }
                Outcome.SKIPPED -> skipped++
                else -> {
                    failed++
                    if (firstError == null) firstError = outcome.name
                    if (outcome == Outcome.NO_SPACE) {
                        failed += recordings.size - index - 1
                        onProgress(Progress(done = recordings.size, total = recordings.size, currentName = null))
                        return finish(copied, skipped, failed, bytesCopied, firstError, volume)
                    }
                }
            }
        }
        onProgress(Progress(done = recordings.size, total = recordings.size, currentName = null))
        return finish(copied, skipped, failed, bytesCopied, firstError, volume)
    }

    private enum class Outcome { COPIED, SKIPPED, PIN_FAILED, NO_SPACE, COPY_FAILED }

    private fun exportOne(source: File, targetDir: File): Outcome {
        if (!RecorderLibrary.pinForUpload(source)) return Outcome.PIN_FAILED
        try {
            val target = File(targetDir, source.name)
            File(targetDir, source.name + UsbExportPolicy.PART_SUFFIX).delete()
            if (!UsbExportPolicy.shouldCopy(target.exists(), source.length(), target.length())) {
                return Outcome.SKIPPED
            }
            if (!UsbExportPolicy.hasSpace(targetDir.usableSpace, source.length())) {
                return Outcome.NO_SPACE
            }
            if (!copyWithSync(source, target)) return Outcome.COPY_FAILED
            // The sidecar is tiny metadata: copy best-effort alongside the video.
            val sidecar = SegmentSidecarIO.sidecarFileFor(source)
            if (sidecar.exists()) {
                runCatching { copyWithSync(sidecar, File(targetDir, sidecar.name)) }
            }
            return Outcome.COPIED
        } catch (t: Throwable) {
            return Outcome.COPY_FAILED
        } finally {
            RecorderLibrary.releaseUploadPin(source)
        }
    }

    private fun copyWithSync(source: File, target: File): Boolean {
        val part = File(target.parentFile, target.name + UsbExportPolicy.PART_SUFFIX)
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output, bufferSize = 1 shl 20)
                    output.fd.sync()
                }
            }
            target.delete()
            part.renameTo(target)
        } catch (t: Throwable) {
            runCatching { part.delete() }
            false
        }
    }

    private fun finish(
        copied: Int,
        skipped: Int,
        failed: Int,
        bytesCopied: Long,
        firstError: String?,
        volume: UsbStorage.UsbVolume,
    ): Result {
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_USB_EXPORT",
            payload = mapOf(
                "copied" to copied.toString(),
                "skipped" to skipped.toString(),
                "failed" to failed.toString(),
                "bytesCopied" to bytesCopied.toString(),
                "volume" to volume.description,
                "firstError" to (firstError ?: "-"),
            ),
        )
        return Result(copied, skipped, failed, bytesCopied, firstError)
    }
}
