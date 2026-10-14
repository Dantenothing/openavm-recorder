package com.dante.zeekrcapabilitylab.diagnostic

import android.os.Build
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelHealth
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannels
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelJournal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** A stopped-process fixture, using the actual saved channel journal and report collector. */
class RetainedChannelEvidenceAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }

    @Test fun emptyRuntimeStillExportsPreviouslySavedCabinCounters() {
        withJournal { _, write ->
            val id = "00000000-0000-0000-0000-000000000021"
            val fixture = listOf(RecordingChannelHealth(id, "FRONT_CABIN", status = "UNAVAILABLE",
                capturedFrames = 5, encodedFrames = 0, writtenFrames = 0,
                errorCode = "AUXILIARY_NO_WRITTEN_FRAMES", storageKind = "USB_MEDIASTORE"))
            write(json.encodeToString(fixture).toByteArray())
            val report = Json.parseToJsonElement(RecorderSupportReport.collect(context)).jsonObject
            assertTrue(report["runtimeChannels"]!!.jsonArray.isEmpty())
            val saved = report["retainedChannels"] as? JsonObject
            assertNotNull("Full report must read the saved channel journal after process restart", saved)
            assertEquals("AVAILABLE", saved!!["readStatus"]!!.jsonPrimitive.content)
            val channel = saved["channels"]!!.jsonArray.single().jsonObject
            assertEquals("FRONT_CABIN", channel["channelId"]!!.jsonPrimitive.content)
            assertEquals(5L, channel["capturedFrames"]!!.jsonPrimitive.long)
            assertEquals(0L, channel["writtenFrames"]!!.jsonPrimitive.long)
            assertEquals(ProcessExitReport.sessionCorrelation(id), channel["sessionCorrelation"]!!.jsonPrimitive.content)
            assertFalse(report.toString().contains(id))
            assertFalse(CameraRecordingService.isRunning())
        }
    }

    @Test fun missingInvalidOrOversizeJournalDoesNotDiscardTheOtherReportSections() = withJournal { file, write ->
        file.delete()
        assertEquals("NOT_RECORDED", RecordingChannelJournal.read(context).readStatus)
        listOf("not-json".toByteArray(), ByteArray(RecordingChannelJournal.MAX_BYTES + 1) { 'x'.code.toByte() }).forEach { bytes ->
            write(bytes)
            val report = Json.parseToJsonElement(RecorderSupportReport.collect(context)).jsonObject
            assertEquals("READ_FAILED", report["retainedChannels"]!!.jsonObject["readStatus"]!!.jsonPrimitive.content)
            assertTrue(report.containsKey("recorder")); assertTrue(report.containsKey("away"))
            assertFalse(CameraRecordingService.isRunning())
        }
    }

    private fun withJournal(test: (AtomicFile, (ByteArray) -> Unit) -> Unit) {
        assumeTrue("Explicit disposable emulator only", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("evidenceJournalTest") == "true")
        assertFalse(CameraRecordingService.isRunning())
        assertTrue("Do not replace evidence during recording", RecordingChannels.state.value.isEmpty())
        val journal = AtomicFile(File(context.filesDir, "recordings/channel-health.json"))
        val backup = runCatching { journal.readFully() }.getOrNull()
        fun write(bytes: ByteArray) {
            journal.baseFile.parentFile!!.mkdirs()
            val stream = journal.startWrite()
            try { stream.write(bytes); journal.finishWrite(stream) }
            catch (error: Throwable) { journal.failWrite(stream); throw error }
        }
        try {
            test(journal, ::write)
        } finally {
            if (backup == null) journal.delete() else write(backup)
        }
    }
}
