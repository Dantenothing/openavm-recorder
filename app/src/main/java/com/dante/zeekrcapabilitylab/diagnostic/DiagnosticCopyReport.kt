package com.dante.zeekrcapabilitylab.diagnostic

import java.security.MessageDigest
import kotlinx.serialization.json.*

data class DiagnosticCopyReport(val text: String, val parts: List<String>) {
    val canCopyWhole: Boolean get() = text.toByteArray(Charsets.UTF_8).size <= 120 * 1024

    companion object {
        const val FORMAT = "OPENAVM_RECORDER_REPORT"
        const val MAX_BYTES = 4L * 1024 * 1024
        fun from(text: String): DiagnosticCopyReport {
            val report = Json.parseToJsonElement(text).jsonObject
            require(report["format"]?.jsonPrimitive?.content == FORMAT)
            val compact = report.toString()
            val bytes = compact.toByteArray(Charsets.UTF_8)
            require(bytes.size.toLong() <= MAX_BYTES)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < compact.length) {
                var end = minOf(start + 16_000, compact.length)
                if (end < compact.length && compact[end - 1].isHighSurrogate()) end--
                chunks += compact.substring(start, end)
                start = end
            }
            val parts = chunks.mapIndexed { index, chunk -> buildJsonObject {
                put("schemaVersion", 1); put("format", "OPENAVM_RECORDER_JSON_PART")
                put("reportSha256", hash); put("reportUtf8Bytes", bytes.size)
                put("partNumber", index + 1); put("partCount", chunks.size); put("jsonText", chunk)
            }.toString() }
            return DiagnosticCopyReport(compact, parts)
        }
    }
}
