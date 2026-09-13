package com.dante.zeekrcapabilitylab.sentry.canary

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class CanaryBundleClip(val fileName: String, val status: String, val sha256: String?, val bytes: Long)
@Serializable
data class CanaryEvidenceBundle(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val archive: CanaryEvidenceArchive,
    val current: CombinedCanaryReport,
    val assessments: Map<String, CanaryAssessment>,
    val clips: List<CanaryBundleClip>,
    val archiveReadError: String? = null,
)
data class CanaryBundleResult(val includedClips: Int, val omittedClips: Int, val videoBytes: Long)

/** Streams only explicitly triggered, already committed clips. Does not create an internal ZIP. */
object CanaryEvidenceBundleWriter {
    const val MAX_VIDEO_BYTES = 128L * 1024 * 1024
    const val MAX_CLIP_BYTES = 801L * 1024 * 1024
    const val MAX_CLIPS = 12
    private val validName = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.mp4")
    private val validHash = Regex("[0-9a-fA-F]{64}")

    fun write(output: OutputStream, directory: File, archive: CanaryEvidenceArchive, current: CombinedCanaryReport,
              exportedAtEpochMs: Long, archiveReadError: String? = null): CanaryBundleResult {
        val observations = archive.records.mapNotNull { it.ram } + current.ramAndClip
        val candidates = observations.flatMap { it.clips + listOfNotNull(it.clip) }
            .filter { it.fileName != null }.groupBy { it.fileName!! }.entries.toList().asReversed()
        val scratch = ByteArray(64 * 1024)
        val clips = mutableListOf<CanaryBundleClip>()
        val root = directory.canonicalFile
        var videoBytes = 0L
        var included = 0
        ZipOutputStream(output).use { zip ->
            zip.setLevel(1)
            for ((name, records) in candidates) {
                val clip = records.last()
                val file = File(root, name)
                val status = when {
                    !validName.matches(name) -> "INVALID_NAME"
                    records.map { it.sha256?.lowercase() to it.byteCount }.distinct().size != 1 -> "CONFLICTING_METADATA"
                    clip.sha256?.matches(validHash) != true || clip.byteCount !in 1..MAX_CLIP_BYTES -> "UNVERIFIED_METADATA"
                    !file.isFile || file.canonicalFile.parentFile != root || file.canonicalFile.name != name -> "MISSING_OR_UNOWNED_FILE"
                    file.length() != clip.byteCount -> "SIZE_MISMATCH"
                    included >= MAX_CLIPS || videoBytes + file.length() > MAX_VIDEO_BYTES -> "BUNDLE_LIMIT"
                    digest(file, scratch) != clip.sha256.lowercase() -> "HASH_MISMATCH"
                    else -> "INCLUDED"
                }
                if (status == "INCLUDED") {
                    zip.putNextEntry(ZipEntry("clips/$name"))
                    val hash = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    file.inputStream().use { input ->
                        while (true) {
                            val count = input.read(scratch)
                            if (count < 0) break
                            copied += count
                            check(copied <= clip.byteCount) { "CLIP_CHANGED_DURING_EXPORT" }
                            hash.update(scratch, 0, count)
                            zip.write(scratch, 0, count)
                        }
                    }
                    check(copied == clip.byteCount && hex(hash.digest()) == clip.sha256!!.lowercase()) { "CLIP_CHANGED_DURING_EXPORT" }
                    zip.closeEntry()
                    videoBytes += copied
                    included++
                }
                clips += CanaryBundleClip(name, status, clip.sha256, clip.byteCount)
            }
            val assessments = archive.records.associate { it.key to CanaryEvidenceEvaluator.record(it) }
            val bundle = CanaryEvidenceBundle(exportedAtEpochMs = exportedAtEpochMs, archive = archive, current = current,
                assessments = assessments, clips = clips, archiveReadError = archiveReadError)
            val report = CanaryEvidenceJson.format.encodeToString(bundle).toByteArray(Charsets.UTF_8)
            check(report.size <= 4 * 1024 * 1024) { "BUNDLE_METADATA_LIMIT" }
            writeEntry(zip, "report.json", report)
            val summary = buildString {
                appendLine("OpenAVM 哨兵测试结果汇总")
                appendLine("自动检查只依据记录的数据；自动哨兵、时钟校准、完整播放和中断场景的硬件验收仍待确认。")
                appendLine("历史记录 ${archive.records.size} 条，已按容量淘汰 ${archive.evictedRecords} 条；短片包含 $included 段。")
                appendLine("每轮最多保留 12 条成片结果和 4 条失败结果，运行内淘汰数见 omittedClipResults；结果包最多 12 段 / 128 MiB 视频。")
                archiveReadError?.let { appendLine("归档读取失败：$it。本包仍包含当前可用报告。") }
                for (record in archive.records) {
                    appendLine("\n${record.key} · ${record.build} · ${if (record.finishedAtEpochMs == null) "没有结束报告" else "已记录结束"}")
                    appendLine(CanaryEvidenceEvaluator.text(assessments.getValue(record.key)))
                }
                appendLine("\n当前 RAM 观察")
                appendLine(CanaryEvidenceEvaluator.text(CanaryEvidenceEvaluator.ram(current.ramAndClip)))
                appendLine("\n当前 USB 观察")
                appendLine(CanaryEvidenceEvaluator.text(CanaryEvidenceEvaluator.usb(current.usb)))
                clips.filter { it.status != "INCLUDED" }.forEach { appendLine("短片未打包：${it.fileName} · ${it.status}") }
            }
            writeEntry(zip, "summary.txt", summary.toByteArray(Charsets.UTF_8))
        }
        return CanaryBundleResult(included, clips.size - included, videoBytes)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }
    private fun digest(file: File, scratch: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(scratch)
                if (count < 0) break
                read += count
                check(read <= MAX_CLIP_BYTES) { "CLIP_CHANGED_DURING_EXPORT" }
                hash.update(scratch, 0, count)
            }
        }
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
