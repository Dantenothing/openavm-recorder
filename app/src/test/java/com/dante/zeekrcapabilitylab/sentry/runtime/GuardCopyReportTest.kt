package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.canary.CanaryTextReportPart
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GuardCopyReportTest {
    private val json = GuardEventStore.json

    @Test fun fullCopyPreservesChineseEscapesNullsAndAllDiagnosticFields() {
        val original = buildJsonObject {
            put("format", "OPENAVM_SENTRY_INTEGRATED_REPORT")
            put("exportBuild", "beta8.1")
            put("current", buildJsonObject {
                put("build", "beta7")
                put("running", false)
                put("error", JsonNull)
                put("frames", 0)
                put("message", "环视🙂\n\"camera\"\\USB\t")
            })
            put("recorderHistory", buildJsonArray {
                repeat(120) { index -> add(buildJsonObject {
                    put("sequence", index)
                    put("payload", "普通／延时🙂\n\"\\")
                }) }
            })
        }
        val result = GuardDiagnostics.copyReport(json.encodeToString(JsonObject.serializer(), original))
        assertNotNull(result.fullText)
        assertEquals(original, json.parseToJsonElement(result.fullText!!))
        assertFalse(result.fullText.contains('\n')) // Newlines in values remain escaped.
        assertEquals(result.fullText, reassemble(result))
    }

    @Test fun longLegacyReportCanBeCopiedOnceWithoutRemovingItsHistory() {
        val original = report("x".repeat(93_000))
        // Legacy reports may contain large amounts of indentation outside strings.
        val legacy = original.toString().replace(",\"payload\"", ",\n" + " ".repeat(80_000) + "\"payload\"")
        assertEquals(11, GuardDiagnostics.parts(legacy).size)
        val result = GuardDiagnostics.copyReport(legacy)
        assertNotNull(result.fullText)
        assertEquals(original, json.parseToJsonElement(result.fullText!!))
        assertEquals(result.fullText, reassemble(result))
        assertTrue(result.parts.size < 11)
    }

    @Test fun oversizedClipboardReportRetainsEveryFieldAcrossBoundedParts() {
        val original = report("x".repeat(200_000))
        val result = GuardDiagnostics.copyReport(original.toString())
        assertNull(result.fullText)
        assertTrue(result.parts.size > 1)
        assertTrue(result.parts.all { it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 })
        assertEquals(original, json.parseToJsonElement(reassemble(result)))
    }

    @Test fun fullCopyLimitUsesUtf8BytesForChineseText() {
        val result = GuardDiagnostics.copyReport(report("车".repeat(50_000)).toString())
        assertNull(result.fullText)
        assertEquals(report("车".repeat(50_000)), json.parseToJsonElement(reassemble(result)))
    }

    @Test fun unrelatedJsonCannotBeShownAsDiagnosticEvidence() {
        try {
            GuardDiagnostics.copyReport("""{"format":"OTHER"}""")
            fail("An unrelated report was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun reportLargerThanTheFileLimitIsRejected() {
        try {
            GuardDiagnostics.copyReport(report("x".repeat(4 * 1024 * 1024)).toString())
            fail("An oversized report was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    private fun report(payload: String) = buildJsonObject {
        put("format", "OPENAVM_SENTRY_INTEGRATED_REPORT")
        put("payload", payload)
    }

    private fun reassemble(result: GuardCopyReport): String {
        val parts = result.parts.map { json.decodeFromString<CanaryTextReportPart>(it) }
        assertEquals(parts.indices.map { it + 1 }, parts.map { it.partNumber })
        assertTrue(parts.all { it.partCount == parts.size })
        val text = parts.joinToString("") { it.jsonText }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue(parts.all { it.reportSha256 == sha && it.reportUtf8Bytes == bytes.size })
        return text
    }
}
