package com.dante.zeekrcapabilitylab.sentry.canary

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class CanaryTextReport(
    val schemaVersion: Int = 1,
    val format: String = "OPENAVM_SENTRY_TEXT_REPORT",
    val exportedAtEpochMs: Long,
    val archive: CanaryEvidenceArchive,
    val current: CombinedCanaryReport,
    val assessments: Map<String, CanaryAssessment>,
    val archiveReadError: String? = null,
    val videoIncluded: Boolean = false,
)

/** Each part is valid JSON. Decode jsonText before joining; do not join the envelopes themselves. */
@Serializable
data class CanaryTextReportPart(
    val schemaVersion: Int = 1,
    val format: String = "OPENAVM_SENTRY_JSON_PART",
    val reportSha256: String,
    val reportUtf8Bytes: Int,
    val partNumber: Int,
    val partCount: Int,
    val reassembly: String = "Sort by partNumber, concatenate decoded jsonText, verify reportSha256, then parse JSON.",
    val jsonText: String,
)

data class CanaryPreparedTextReport(val json: String, val parts: List<String>)

/** Pure metadata formatting: no video reads, ZIP, file picker, camera or clipboard access. */
object CanaryTextReportWriter {
    const val MAX_COPY_BYTES = 128 * 1024
    const val MAX_REPORT_BYTES = 4 * 1024 * 1024

    fun prepare(archive: CanaryEvidenceArchive, current: CombinedCanaryReport, exportedAtEpochMs: Long,
                archiveReadError: String? = null): CanaryPreparedTextReport {
        val assessments = archive.records.associate { it.key to CanaryEvidenceEvaluator.record(it) } + mapOf(
            "CURRENT_RAM" to CanaryEvidenceEvaluator.ram(current.ramAndClip),
            "CURRENT_USB" to CanaryEvidenceEvaluator.usb(current.usb),
        )
        val report = CanaryTextReport(exportedAtEpochMs = exportedAtEpochMs, archive = archive, current = current,
            assessments = assessments, archiveReadError = archiveReadError)
        return fromJson(CanaryEvidenceJson.copyFormat.encodeToString(report))
    }

    /** Reopening the saved snapshot produces exactly the same parts after switching to email. */
    fun fromJson(json: String): CanaryPreparedTextReport {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REPORT_BYTES) { "TEXT_REPORT_TOO_LARGE" }
        val report = CanaryEvidenceJson.format.decodeFromString<CanaryTextReport>(json)
        require(report.schemaVersion == 1 && report.format == "OPENAVM_SENTRY_TEXT_REPORT") { "UNKNOWN_TEXT_REPORT" }
        if (bytes.size <= MAX_COPY_BYTES) return CanaryPreparedTextReport(json, listOf(json))

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun encode(segment: String, number: Int, count: Int) = CanaryEvidenceJson.copyFormat.encodeToString(
            CanaryTextReportPart(reportSha256 = hash, reportUtf8Bytes = bytes.size,
                partNumber = number, partCount = count, jsonText = segment))
        // Reserve the largest possible numeric headers; the payload budget includes JSON escaping.
        val budget = MAX_COPY_BYTES - encode("", Int.MAX_VALUE, Int.MAX_VALUE).toByteArray(Charsets.UTF_8).size
        val segments = mutableListOf<String>()
        var start = 0
        var index = 0
        var used = 0
        while (index < json.length) {
            val char = json[index]
            val pair = char.isHighSurrogate() && index + 1 < json.length && json[index + 1].isLowSurrogate()
            val cost = when {
                char in "\b\t\n\r\u000C\"\\" -> 2
                char < ' ' -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                pair -> 4
                char.isSurrogate() -> 6
                else -> 3
            }
            if (used + cost > budget) {
                check(index > start) { "COPY_PART_BUDGET_TOO_SMALL" }
                segments += json.substring(start, index)
                start = index
                used = 0
            }
            used += cost
            index += if (pair) 2 else 1
        }
        segments += json.substring(start)
        val parts = segments.mapIndexed { part, segment ->
            encode(segment, part + 1, segments.size).also {
                check(it.toByteArray(Charsets.UTF_8).size <= MAX_COPY_BYTES) { "COPY_PART_TOO_LARGE" }
            }
        }
        return CanaryPreparedTextReport(json, parts)
    }
}
