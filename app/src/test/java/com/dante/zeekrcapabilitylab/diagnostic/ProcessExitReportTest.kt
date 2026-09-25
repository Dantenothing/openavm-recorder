package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.data.CrashRecord
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class ProcessExitReportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsPreviousProcessProductLogsAndIgnoresLabFilesAndTornLines() {
        val directory = temporary.newFolder()
        fun line(name: String, time: Long) = Json.encodeToString(ProbeEvent.serializer(),
            ProbeEvent(time, "app", time, time, "APP", name, "INFO")) + "\n"
        java.io.File(directory, "app-100.jsonl").writeText(line("RECORDER_NATIVE_FILE_APPROACHING", 100))
        java.io.File(directory, "app.jsonl").writeText("{torn\n" + line("PROCESS_STARTED", 200))
        java.io.File(directory, "lab.jsonl").writeText(line("UNCAUGHT_EXCEPTION", 300))
        val history = ProcessExitDiagnostics.recentHistory(directory)
        assertEquals(setOf("RECORDER_NATIVE_FILE_APPROACHING", "PROCESS_STARTED"), history.events.map { it.eventName }.toSet())
        assertEquals(0, history.errors)
    }

    @Test fun historyTailReadIsBoundedAndCanRecoverAfterItsFirstPartialLine() {
        val directory = temporary.newFolder()
        val event = Json.encodeToString(ProbeEvent.serializer(), ProbeEvent(1, "app", 123, 12, "APP", "PREVIOUS_CRASH", "ERROR"))
        java.io.File(directory, "app.jsonl").writeText("x".repeat(5000) + "\n" + event + "\n")
        val history = ProcessExitDiagnostics.recentHistory(directory, 512)
        assertEquals(512, history.bytes)
        assertEquals("PREVIOUS_CRASH", history.events.single().eventName)
    }

    @Test fun exitEvidenceSurvivesOversizeOldFaultWithoutBreakingSingleCopyLimit() {
        val snapshot = buildJsonObject {
            put("atEpochMs", 123); put("capturedVersion", "old")
            put("recentEvents", "x".repeat(30 * 1024))
        }
        val run = buildJsonObject { put("recordingBackend", "CONTINUOUS_MEDIA_RECORDER") }
        val exits = buildJsonObject { put("historicalExits", buildJsonArray { add(buildJsonObject { put("reason", "NATIVE_CRASH") }) }) }
        val text = ShortRecorderReport.report("test", 1, 32, snapshot, snapshot, run, exits)
        assertTrue(text.toByteArray().size <= ShortRecorderReport.MAX_BYTES)
        val report = Json.parseToJsonElement(text).jsonObject
        assertTrue(report["omittedForSize"]!!.jsonArray.any { it.jsonPrimitive.content == "lastFault.details" })
        assertEquals(exits, report["processExit"])
        assertEquals(run, report["lastRun"])
        assertEquals("old", report["lastFault"]!!.jsonObject["capturedVersion"]!!.jsonPrimitive.content)
    }

    @Test fun beta12FailedNativeHandoffIsDisabledInThisBuild() {
        assertFalse(BuildConfig.NATIVE_FILE_ROTATION_ENABLED)
    }

    @Test fun crashAndHandoffEvidenceSurvivesAProcessRestartWithoutExportingPathsOrMessages() {
        val names = listOf("RECORDER_SEGMENT_START", "RECORDER_NATIVE_FILE_APPROACHING", "RECORDER_NATIVE_FILE_QUEUED",
            "UNCAUGHT_EXCEPTION", "PROCESS_STARTED", "PREVIOUS_CRASH")
        val events = names.mapIndexed { index, name -> ProbeEvent(index.toLong(), "app", 100L + index, 50L + index,
            "APP", name, "ERROR", payload = mapOf("file" to "/storage/private.mp4", "segment" to "2"),
            errorType = "java.lang.IllegalStateException", errorMessage = "bad file https://host/?token=secret") }
        val text = ProcessExitReport.events(events).toString()
        names.forEach { assertTrue(it, text.contains(it)) }
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertFalse(text.contains("/storage")); assertFalse(text.contains("secret")); assertFalse(text.contains("https"))
    }

    @Test fun javaCrashRetainsFramesAndCauseClassesButNotExceptionMessageOrArbitraryThreadText() {
        val crash = CrashRecord(123, "worker https://host/?token=secret", "java.lang.RuntimeException",
            "private /storage/video.mp4", "java.lang.RuntimeException: private /storage/video.mp4\n" +
                "\tat com.example.Recorder.rotate(Recorder.kt:42)\nCaused by: java.lang.IllegalStateException: secret\n" +
                "\tat a.b.c(SourceFile:12)\n")
        val result = ProcessExitReport.crash(crash)
        assertEquals(123L, result["atEpochMs"]!!.jsonPrimitive.long)
        assertTrue(result.toString().contains("com.example.Recorder.rotate"))
        assertTrue(result.toString().contains("java.lang.IllegalStateException"))
        assertFalse(result.toString().contains("private")); assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("https"))
    }

    @Test fun relevantHistoryIsBoundedAndSortedWithoutOrdinaryFrameNoise() {
        val events = (50 downTo 1).map { ProbeEvent(it.toLong(), "app", it.toLong(), it.toLong(), "APP",
            if (it % 2 == 0) "RECORDER_NATIVE_FILE_APPROACHING" else "RECORDER_FRAME", "INFO") }
        val result = ProcessExitReport.events(events)
        assertEquals(24, result.size)
        assertEquals(4, result.first().jsonObject["at"]!!.jsonPrimitive.int)
        assertEquals(50, result.last().jsonObject["at"]!!.jsonPrimitive.int)
    }

    @Test fun nativeTraceFindsOnlyTheCrashingThreadAndSkipsPrivateMemoryAndFdFields() {
        val frame = field(4, "android::MPEG4Writer::reset()".toByteArray()) +
            field(6, "/apex/com.android.media/lib64/libstagefright.so".toByteArray()) + integer(1, 1234)
        val correctThread = integer(1, 7) + field(4, frame)
        val wrongThread = integer(1, 8) + field(4, field(4, "not_the_crashing_thread".toByteArray()))
        val trace = integer(6, 7) + field(10, integer(1, 6) + field(2, "SIGABRT".toByteArray())) +
            field(16, integer(1, 8) + field(2, wrongThread)) + field(19, field(2, "/storage/secret.mp4".toByteArray())) +
            field(16, integer(1, 7) + field(2, correctThread))
        val result = NativeCrashTrace.decode(trace)
        assertEquals("AVAILABLE", result["status"]!!.jsonPrimitive.content)
        assertEquals("SIGABRT", result["signal"]!!.jsonPrimitive.content)
        assertTrue(result.toString().contains("libstagefright.so"))
        assertTrue(result.toString().contains("android::MPEG4Writer::reset()"))
        assertFalse(result.toString().contains("/apex")); assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("not_the_crashing_thread"))
    }

    @Test fun malformedAndOversizedNativeTracesFailClosed() {
        assertEquals("INVALID_PROTO", NativeCrashTrace.decode(byteArrayOf(0x82.toByte()))["status"]!!.jsonPrimitive.content)
        assertEquals("INVALID_PROTO", NativeCrashTrace.decode(byteArrayOf(0x82.toByte(), 1, 127, 1))["status"]!!.jsonPrimitive.content)
        assertEquals("TOO_LARGE", NativeCrashTrace.decode(ByteArray(NativeCrashTrace.MAX_BYTES + 1))["status"]!!.jsonPrimitive.content)
    }

    @Test fun missingCrashingThreadNeverSubstitutesAnotherThread() {
        val trace = integer(6, 7) + field(16, integer(1, 8) + field(2, integer(1, 8)))
        assertEquals("CRASH_THREAD_NOT_FOUND", NativeCrashTrace.decode(trace)["status"]!!.jsonPrimitive.content)
    }

    private fun integer(number: Int, value: Long) = varint(number.toLong() shl 3) + varint(value)
    private fun field(number: Int, bytes: ByteArray) = varint((number.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun varint(number: Long): ByteArray {
        val out = ByteArrayOutputStream(); var value = number
        while (value >= 128) { out.write((value.toInt() and 127) or 128); value = value ushr 7 }
        out.write(value.toInt()); return out.toByteArray()
    }
}
