package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ContinuousFailureEvidenceTest {
    private fun capture(id: String = "run-a", at: Long = 1_000,
                        numbers: (Throwable) -> ContinuousFailureNumbers = { ContinuousFailureNumbers() }) =
        ContinuousFailureCapture(id, { at }, { 10_000 }, numbers)

    @Test fun arbitraryEmptyOrNonEnumMessagesKeepTypeStageAndTimeButNeverRawText() {
        listOf(null, "", "Failed to write content://private/a?token=secret", "0xfffffff4",
            "java.lang.IllegalStateException", "TOKEN_SUPER_SECRET", "错误：/storage/private.mp4").forEach { message ->
            val evidence = capture().capture(IllegalStateException(message), ContinuousFailureStage.FILE_WRITER)
            val facts = evidence.facts()
            assertEquals("java.lang.IllegalStateException", facts["exceptionType"])
            assertEquals("FILE_WRITER", facts["failureStage"])
            assertEquals("1000", facts["faultElapsedMs"])
            assertEquals("run-a", facts["recordingSessionId"])
            assertEquals("UNCLASSIFIED_THROWABLE", facts["errorCode"])
            listOf("secret", "private", "content:", "TOKEN_SUPER_SECRET", "0xfffffff4").forEach {
                assertFalse(facts.toString().contains(it))
            }
        }
    }

    @Test fun knownGuardCodeKeepsOnlyTheRegisteredCode() {
        val result = capture().capture(IllegalStateException("PRODUCT_WRITER_QUEUE_FULL: content://secret"),
            ContinuousFailureStage.CODEC_DRAIN).facts()
        assertEquals("PRODUCT_WRITER_QUEUE_FULL", result["errorCode"])
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun absentThrowableAndSampleRemainExplicitlyUnknownNotFabricatedZeroes() {
        val facts = capture().capture(null, ContinuousFailureStage.SESSION_CALLBACK).facts()
        assertEquals("UNCLASSIFIED_SIGNAL", facts["errorCode"])
        assertEquals("UNKNOWN", facts["exceptionType"])
        assertEquals("UNKNOWN", facts["faultQueueBytes"])
        assertEquals("UNKNOWN", facts["snapshotAgeMs"])
        assertEquals("UNAVAILABLE", facts["snapshotKind"])
    }

    @Test fun cachedSnapshotKeepsItsActualAgeAndSurvivesLaterZeroQueueCleanup() {
        val recorder = capture()
        val prior = ContinuousFailureSample(640, 6_000_000, 24, 500, 800_000, 3, 18)
        val first = recorder.capture(IllegalStateException("native failed"), ContinuousFailureStage.CODEC_DRAIN, prior)
        val later = recorder.capture(IllegalStateException("PRODUCT_MEDIA_RELEASE_UNCONFIRMED"),
            ContinuousFailureStage.FILE_CLEANUP, prior.copy(sampledAtElapsedMs=999, queueBytes=0, queueItems=0))
        assertSame(first, later)
        assertEquals("360", first.facts()["snapshotAgeMs"])
        assertEquals("6000000", first.facts()["faultQueueBytes"])
        assertEquals("CACHED_COUNTERS_BEFORE_FAULT", first.facts()["snapshotKind"])
    }

    @Test fun futureSnapshotIsNotRepresentedAsFailureTimeEvidence() {
        val result = capture().capture(Exception(), ContinuousFailureStage.INPUT,
            ContinuousFailureSample(1_001,0,0,100,200,2,0)).facts()
        assertEquals("UNAVAILABLE", result["snapshotKind"])
        assertEquals("UNKNOWN", result["faultEncodedFrames"])
    }

    @Test fun typedNumericFieldsSurviveEvenWhenTheExceptionHasNoMessage() {
        val root = IllegalStateException(null as String?)
        val facts = capture(numbers = {
            assertSame(root, it)
            ContinuousFailureNumbers(codecErrorCode=-1100, codecRecoverable=false, codecTransient=true,
                sourceType="android.media.MediaCodec\$CodecException", causeDepth=1)
        }).capture(root, ContinuousFailureStage.CODEC_DRAIN).facts()
        assertEquals("-1100", facts["codecErrorCode"])
        assertEquals("false", facts["codecRecoverable"])
        assertEquals("true", facts["codecTransient"])
        assertEquals("1", facts["numericCauseDepth"])
        assertEquals("UNKNOWN", facts["errno"])
    }

    @Test fun exceptionInspectionFailureCannotReplaceTheOriginalTypeOrThrowIntoCleanup() {
        val facts = capture(numbers = { error("diagnostic getter unavailable") })
            .capture(IllegalArgumentException("private details"), ContinuousFailureStage.INPUT).facts()
        assertEquals("java.lang.IllegalArgumentException", facts["exceptionType"])
        assertEquals("UNKNOWN", facts["codecErrorCode"])
    }

    @Test fun laterErrorsDoNotEvenResampleDiagnosticNumbers() {
        var inspections = 0
        val recorder = capture(numbers = { inspections++; ContinuousFailureNumbers() })
        recorder.capture(IllegalStateException(), ContinuousFailureStage.FILE_WRITER)
        repeat(50) { recorder.capture(Exception(), ContinuousFailureStage.FILE_CLEANUP) }
        assertEquals(1, inspections)
        assertEquals(ContinuousFailureStage.FILE_WRITER, recorder.value!!.stage)
    }

    @Test fun capturePublishesEvidenceEvenWhenTheControlCallbackIsNoLongerAccepted() {
        val journal = mutableListOf<ContinuousFailureEvidence>()
        val recorder = ContinuousFailureCapture("old-run", { 100 }, { 1_000 }, onCaptured = journal::add)
        val failure = recorder.capture(Exception("private path"), ContinuousFailureStage.OUTPUT_PUBLISH)
        // The session may already be stopped, so no controller error callback runs at all.
        recorder.capture(Exception("cleanup"), ContinuousFailureStage.FILE_CLEANUP)
        assertEquals(listOf(failure), journal)
        assertEquals("old-run", journal.single().sessionId)
    }

    @Test fun failedJournalSubmissionCannotBlockCleanupOrEraseCapturedEvidence() {
        val recorder = ContinuousFailureCapture("run", { 100 }, { 1_000 }, onCaptured = { error("journal failure") })
        val failure = recorder.capture(IllegalStateException(), ContinuousFailureStage.FILE_WRITER)
        assertSame(failure, recorder.value)
        assertEquals("java.lang.IllegalStateException", failure.exceptionType)
    }

    @Test fun lateOldOwnerRetainsOldRunIdentityAndCannotPolluteNewRun() {
        val old = capture("old"); val current = capture("new")
        val late = old.capture(Exception(), ContinuousFailureStage.FILE_FINALIZE)
        assertNull(current.value)
        assertEquals("old", late.sessionId)
        assertEquals("new", current.capture(Exception(), ContinuousFailureStage.INPUT).sessionId)
    }

    @Test fun earlierCatchRemainsFirstEvenWhenItsNumericExtractionFinishesLater() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val initial = IllegalStateException("first")
        val recorder = capture(numbers = {
            if (it === initial) { entered.countDown(); check(release.await(2,TimeUnit.SECONDS)) }
            ContinuousFailureNumbers()
        })
        val worker = Thread { recorder.capture(initial,ContinuousFailureStage.CODEC_DRAIN) }
        worker.start()
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            recorder.capture(Exception("cleanup"),ContinuousFailureStage.FILE_CLEANUP)
        } finally { release.countDown(); worker.join(2_000) }
        assertFalse(worker.isAlive)
        assertEquals(ContinuousFailureStage.CODEC_DRAIN,recorder.value!!.stage)
        assertEquals(1L,recorder.value!!.captureOrder)
    }
}
