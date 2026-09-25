package com.dante.zeekrcapabilitylab.preflight

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PreflightContractTest {
    private fun report(tests:List<JsonObject> = listOf(PreflightReport.test("query","PASS",evidence="DECLARED")))=obj(
        "schemaVersion" to 1,"exampleOnly" to false,"format" to "OPENAVM_PREFLIGHT","runId" to "test-run",
        "productionSwitchAllowed" to false,"tests" to tests)

    @Test fun metadataFalseIsSuccessfulQueryNotRuntimeFailure() {
        PreflightReport.validate(report(listOf(PreflightReport.test("codec-query","PASS",evidence="DECLARED",data=obj("supported" to false)))))
    }
    @Test fun fallbackCannotPassRequestedRoute() {
        val test=JsonObject(PreflightReport.test("codec","PASS",evidence="ENCODED",actualRoute="MEDIA_RECORDER")+ ("requestedRoute" to JsonPrimitive("CONTINUOUS_CODEC")))
        assertTrue(runCatching { PreflightReport.validate(report(listOf(test))) }.isFailure)
    }
    @Test fun unconfirmedCleanupCannotPass() {
        assertTrue(runCatching { PreflightReport.validate(report(listOf(PreflightReport.test("gl","PASS",evidence="CONFIGURED",cleanup="UNCONFIRMED")))) }.isFailure)
    }
    @Test fun noEvidenceCannotPass() {
        assertTrue(runCatching { PreflightReport.validate(report(listOf(PreflightReport.test("missing","PASS")))) }.isFailure)
    }
    @Test fun shortExportIsWholeJsonAndHashMatchesFullBytes() {
        val full=JsonObject(report()+mapOf("samples" to j(List(2000){obj("value" to "中文".repeat(100))}),"sensorTimestampNs" to JsonPrimitive("9223372036854775807"))).toString().toByteArray()
        val text=PreflightReport.short(full); val parsed=Json.parseToJsonElement(text).jsonObject
        assertTrue(text.toByteArray().size<=PreflightPlan.SHORT_LIMIT)
        assertEquals(sha(full),parsed["fullReportSha256"]!!.jsonPrimitive.content)
        assertEquals(text.toByteArray().size,parsed["exportBytes"]!!.jsonPrimitive.int)
        assertEquals("9223372036854775807",parsed["sensorTimestampNs"]!!.jsonPrimitive.content)
        assertTrue(parsed["omittedSections"]!!.jsonArray.contains(JsonPrimitive("samples")))
    }
    @Test fun oversizedDetailsBecomeValidSummaryInsteadOfTruncation() {
        val full=report(listOf(PreflightReport.test("camera","FAIL",data=obj("large" to "x".repeat(160_000))))).toString().toByteArray()
        val parsed=Json.parseToJsonElement(PreflightReport.short(full)).jsonObject
        assertEquals("FAIL",parsed["tests"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(parsed["omittedSections"]!!.jsonArray.contains(JsonPrimitive("tests.data")))
    }
    @Test fun unavailableFloatsAreNull() { assertEquals(JsonNull,obj("value" to Double.NaN)["value"]); assertEquals(JsonNull,j(Double.POSITIVE_INFINITY)) }
    @Test fun sharedInputDetailsKeepFailuresAndCountsWhenPerFrameArraysExceedCopyBudget() {
        val full=JsonObject(report(listOf(PreflightReport.test("p1_input","WARN",evidence="SYNTHETIC_RUNTIME",data=obj(
            "fullInput" to obj("status" to "PASS","receivedAndPublished" to 600,
                "readers" to listOf(obj("sourceTimestampsNs" to List(10000){"999999999999999999"},"frames" to 600))),
            "fullFileCleanup" to listOf(obj("reason" to "OWNERSHIP_MISMATCH"))))))+
            ("p1Summary" to obj("reason" to "SYNTHETIC_FILES_RETAINED"))).toString().toByteArray()
        val short=Json.parseToJsonElement(PreflightReport.short(full)).jsonObject
        val data=short.getValue("tests").jsonArray.single().jsonObject.getValue("data").jsonObject
        assertEquals("PASS",data.getValue("fullInput").jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(data.containsKey("fullFileCleanup"))
        assertEquals("SYNTHETIC_FILES_RETAINED",short.getValue("p1Summary").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(short.getValue("omittedSections").jsonArray.any { it.jsonPrimitive.content.endsWith("sourceTimestampsNs") })
        assertFalse(short.getValue("omittedSections").jsonArray.contains(JsonPrimitive("tests.data")))
        assertEquals(sha(full),short.getValue("fullReportSha256").jsonPrimitive.content)
    }
    @Test fun memoryOnlyReportDoesNotClaimHashOfASavedFile() {
        val full=report().toString().toByteArray()
        val short=Json.parseToJsonElement(PreflightReport.short(full,false)).jsonObject
        assertEquals(JsonNull,short["fullReportSha256"])
        assertEquals(sha(full),short["memoryReportSha256"]!!.jsonPrimitive.content)
        assertEquals("MEMORY_ONLY_SAVE_UNAVAILABLE",short["fullReportPersistence"]!!.jsonPrimitive.content)
    }
    @Test fun firstFaultAndFinalSnapshotsSurviveTheLastResortShortExport() {
        val snapshots=obj("target" to obj("status" to "FAIL",
            "failureSnapshot" to obj("reason" to "USB_SYNC_CLOSE_TIMEOUT","state" to obj("closedFiles" to 2)),
            "finalSnapshot" to obj("closedFiles" to 4)),"high" to obj("status" to "NOT_RUN"))
        val full=JsonObject(report(listOf(PreflightReport.test("p1_input","FAIL",data=obj("large" to "x".repeat(160_000)))))+
            ("p1Summary" to obj("loadGroups" to snapshots))).toString().toByteArray()
        val parsed=Json.parseToJsonElement(PreflightReport.short(full)).jsonObject
        assertEquals(snapshots,parsed.getValue("p1Summary").jsonObject.getValue("loadGroups"))
    }
    @Test fun unconfirmedCleanupKeepsItsFaultWhenInterruptedDetailsExceedBudget() {
        val groups=obj("target" to obj("status" to "FAIL","failureSnapshot" to obj("reason" to "USB_SYNC_CLOSE_TIMEOUT"),
            "finalSnapshot" to obj("writerStage" to "USB_SYNC_CLOSE")),"high" to obj("status" to "NOT_RUN"))
        val interrupted=obj("status" to "INCOMPLETE","reason" to "CLEANUP_UNCONFIRMED","loadGroups" to groups,
            "healthFailureSnapshot" to obj("reason" to "SOME_FAULT"),"extra" to "x".repeat(160_000))
        val full=JsonObject(report(listOf(PreflightReport.test("p1_input","INCOMPLETE",cleanup="UNCONFIRMED")))+
            ("p1InterruptedDetail" to interrupted)).toString().toByteArray()
        val parsed=Json.parseToJsonElement(PreflightReport.short(full)).jsonObject
        assertEquals(groups,parsed["p1Summary"]?.jsonObject?.get("loadGroups"))
        assertEquals("CLEANUP_UNCONFIRMED",parsed["p1Summary"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
    }
    @Test fun checkpointFailureDoesNotGrantNativeOperation() {
        val gate=PreflightGate(); var opened=false
        assertTrue(runCatching { gate.begin("camera") { error("disk full") }; opened=true }.isFailure)
        assertFalse(opened); assertNull(gate.active); assertEquals("CONFIRMED",gate.cleanup)
    }
    @Test fun frozenCachedUiAgesCannotLookFreshOnTheIndependentWatchdog() {
        val clock=PreflightProgressClock()
        assertEquals(0L to 0L,clock.observe(1000,80,70))
        assertEquals(9000L to 9000L,clock.observe(10000,80,70))
        assertEquals(0L to 10000L,clock.observe(11000,90,70))
        assertEquals(1000L to 0L,clock.observe(12000,90,90))
    }
    @Test fun cleanupTimeoutCannotGrantSecondOpen() {
        val gate=PreflightGate(); val id=gate.begin("camera")
        assertFalse(gate.finish(id,false)); assertTrue(runCatching{gate.begin("camera2")}.isFailure)
    }
    @Test fun lateAcknowledgementCanSettleSameOwnerButNeverNewOwner() {
        val gate=PreflightGate(); val first=gate.begin("old"); gate.finish(first,false)
        assertTrue(gate.finish(first,true)); val second=gate.begin("new")
        assertFalse(gate.finish(first,true)); assertEquals("new",gate.active); assertEquals(second,gate.generation)
    }
    @Test fun cancellationPreventsNextStageEvenAfterCleanup() {
        val gate=PreflightGate(); val id=gate.begin("usb"); gate.cancel(); assertFalse(gate.accepts(id))
        gate.finish(id,true); assertTrue(runCatching{gate.begin("camera")}.isFailure)
    }
    @Test fun truncatedJournalFailsClosed() { assertEquals("PREVIOUS_JOURNAL_UNREADABLE",PreflightPlan.previousRunBlocks(true,null,5)) }
    @Test fun malformedBootFieldFailsClosedWithoutCrashingStartup() {
        assertNotNull(PreflightPlan.previousRunBlocks(true,obj("cleanup" to "PENDING","bootCount" to obj("bad" to true)),5))
    }
    @Test fun processRestartDoesNotPretendHalReset() {
        val pending=obj("cleanup" to "PENDING","bootCount" to 5)
        assertNotNull(PreflightPlan.previousRunBlocks(true,pending,5)); assertNull(PreflightPlan.previousRunBlocks(true,pending,6))
        assertNotNull(PreflightPlan.previousRunBlocks(true,pending,-1))
    }
    @Test fun sharedInputAndUnknownModesCannotBecomeCleanJustBecauseProcessRestarted() {
        assertEquals("UNCONFIRMED",PreflightPlan.interruptedCleanup("P1_INPUT"))
        assertEquals("UNCONFIRMED",PreflightPlan.interruptedCleanup("P1_BASIC"))
        assertEquals("UNCONFIRMED",PreflightPlan.interruptedCleanup("FULL_SAFE"))
        assertEquals("UNCONFIRMED",PreflightPlan.interruptedCleanup(null))
        assertEquals("UNCONFIRMED",PreflightPlan.interruptedCleanup("FUTURE_MODE"))
        assertEquals("CONFIRMED",PreflightPlan.interruptedCleanup("CAPABILITIES_ONLY"))
    }
    @Test fun confirmedOrAbsentJournalAllowsNewRun() {
        assertNull(PreflightPlan.previousRunBlocks(false,null,5)); assertNull(PreflightPlan.previousRunBlocks(true,obj("cleanup" to "CONFIRMED"),5))
    }
    @Test fun usbDisappearanceOrUnknownFreeSpaceCannotAdmit() {
        assertEquals("FREE_SPACE_UNKNOWN",PreflightPlan.budgetReason(0,0,100,null))
        assertEquals("FREE_SPACE_UNKNOWN",PreflightPlan.budgetReason(0,0,100,-1))
    }
    @Test fun reserveIncludesInFlightFinalization() {
        assertEquals("USB_FREE_SPACE_LOW",PreflightPlan.budgetReason(0,0,500,PreflightPlan.RESERVE+999))
        assertNull(PreflightPlan.budgetReason(0,0,500,PreflightPlan.RESERVE+1000))
    }
    @Test fun writeAndRetainedBudgetsAreIndependent() {
        assertEquals("WRITE_BUDGET_EXHAUSTED",PreflightPlan.budgetReason(PreflightPlan.MAX_WRITE,0,1,Long.MAX_VALUE))
        assertEquals("RETENTION_BUDGET_EXHAUSTED",PreflightPlan.budgetReason(0,PreflightPlan.MAX_RETAINED,1,Long.MAX_VALUE))
    }
    @Test fun retentionBudgetDoesNotAuthorizeUserFileDeletion() {
        assertNotNull(PreflightPlan.budgetReason(0,PreflightPlan.MAX_RETAINED,200,Long.MAX_VALUE))
    }
    @Test fun shortRunOrFallbackCannotPassLongBaseline() {
        assertEquals("INCOMPLETE",PreflightPlan.baselineStatus("PLAN_DURATION_REACHED",10_000,100,25,false,"MEDIA_RECORDER"))
        assertEquals("INCOMPLETE",PreflightPlan.baselineStatus("PLAN_DURATION_REACHED",PreflightPlan.BASELINE_MS,100,25,false,"CONTINUOUS_CODEC"))
    }
    @Test fun previewProgressAloneCannotPassWithoutFileEvidence() {
        assertEquals("INCOMPLETE",PreflightPlan.baselineStatus("PLAN_DURATION_REACHED",PreflightPlan.BASELINE_MS,100,0,false,"MEDIA_RECORDER"))
        assertEquals("INCOMPLETE",PreflightPlan.baselineStatus("PREVIEW_FRESHNESS_STALL",PreflightPlan.BASELINE_MS,100,25,false,"MEDIA_RECORDER"))
    }
    @Test fun failedEvidenceOrUserCancellationIsNotPass() {
        assertEquals("INCOMPLETE",PreflightPlan.baselineStatus("PLAN_DURATION_REACHED",PreflightPlan.BASELINE_MS,100,25,true,"MEDIA_RECORDER"))
        assertEquals("CANCELLED",PreflightPlan.baselineStatus("USER_CANCELLED",PreflightPlan.BASELINE_MS,100,25,false,"MEDIA_RECORDER"))
    }
    @Test fun fullObservationCanPassOnlyThisBaselineCase() {
        assertEquals("PASS",PreflightPlan.baselineStatus("PLAN_DURATION_REACHED",PreflightPlan.BASELINE_MS,100,25,false,"MEDIA_RECORDER"))
        assertEquals(6,PreflightPlan.deferred.size)
    }
}
