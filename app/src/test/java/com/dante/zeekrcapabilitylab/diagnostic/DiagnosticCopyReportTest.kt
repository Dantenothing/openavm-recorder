package com.dante.zeekrcapabilitylab.diagnostic

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCopyReportTest {
    @Test fun smallReportIsOneCompleteClipboardDocument() {
        val source = """{ "format": "OPENAVM_RECORDER_REPORT", "status": "停止" }"""
        val result = DiagnosticCopyReport.from(source)
        assertTrue(result.canCopyWhole)
        assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(result.text))
    }
    @Test fun multipartRoundTripPreservesUnicodeAndHash() {
        val source = buildJsonObject {
            put("format", DiagnosticCopyReport.FORMAT)
            put("logs", "📹中文ไทยالعربية\n\"\\".repeat(12_000))
        }.toString()
        val result = DiagnosticCopyReport.from(source)
        assertFalse(result.canCopyWhole)
        val parts = result.parts.map { Json.parseToJsonElement(it).jsonObject }
        val joined = parts.joinToString("") { it.getValue("jsonText").jsonPrimitive.content }
        assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(joined))
        val hash = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray()).joinToString("") { "%02x".format(it) }
        parts.forEachIndexed { i, part ->
            assertEquals(i + 1, part.getValue("partNumber").jsonPrimitive.int)
            assertEquals(parts.size, part.getValue("partCount").jsonPrimitive.int)
            assertEquals(hash, part.getValue("reportSha256").jsonPrimitive.content)
            assertTrue(result.parts[i].toByteArray().size < 128 * 1024)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnrelatedDocument() {
        DiagnosticCopyReport.from("""{"format":"OTHER"}""")
    }
}
