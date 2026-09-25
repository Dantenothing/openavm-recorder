package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProbeRetainedPolicyTest {
    private val namespace="p1-413d1817-7327-4d81-b7ed-fdfc1e341a5b-full"
    private val target=UsbExportTarget("usb-vol","usb-uuid","USB",collectionUri="content://media/usb-vol/file")
    private fun entry(): UsbPendingRecordingOutput {
        val bundle=UsbExportPolicy.bundleId(namespace,1,1000)
        val name=UsbExportPolicy.videoName(UsbExportPolicy.prefix(1000,bundle),1)
        val uri="content://media/usb-vol/file/123"
        return UsbPendingRecordingOutput("91fce14e-72c3-4c68-8795-9159c66a1b8a",namespace,1,"usb-uuid","usb-vol",uri,name,
            "recorder.owner",1001,bundle, listOf(UsbPendingRecordingAsset(UsbExportAssetKind.VIDEO,uri,name,"video/mp4","recorder.owner")))
    }
    private fun reason(e: UsbPendingRecordingOutput=entry(),ns:String=namespace,token:Boolean=true,receipt:Boolean=true) =
        ProbeRetainedPolicy.identityReason(ns,e,target,token,receipt)
    @Test fun exactValidatedDiagnosticCanBeRetiredAndFailedEvidenceIsRetained() {
        assertNull(reason())
        assertEquals("DELETE_VALIDATED_DIAGNOSTIC",ProbeRetainedPolicy.disposition(reason(),true))
        assertEquals("RETAIN_UNVERIFIED_OR_FAILED_EVIDENCE",ProbeRetainedPolicy.disposition(reason(),false))
    }
    @Test fun ordinarySessionCannotBeDeletedEvenWithAnOpenAvmFilename() {
        assertEquals("DIAGNOSTIC_NAMESPACE_MISMATCH",reason(entry().copy(recordingSessionId="normal-session")))
        assertEquals("DIAGNOSTIC_NAMESPACE_MISMATCH",reason(ns="normal-session"))
    }
    @Test fun exactUsbVolumeItemNameAndCreationReceiptsAreRequired() {
        assertEquals("DIAGNOSTIC_OTHER_VOLUME",reason(entry().copy(storageUuid="another-drive")))
        assertEquals("DIAGNOSTIC_URI_MISMATCH",reason(entry().copy(itemUri="content://media/usb-vol/file")))
        assertEquals("DIAGNOSTIC_URI_MISMATCH",reason(entry().copy(itemUri="content://media/usb-vol/file/123/other")))
        assertEquals("DIAGNOSTIC_URI_MISMATCH",reason(entry().copy(itemUri="content://media/usb-vol/file/123?x=1")))
        assertEquals("DIAGNOSTIC_NAME_MISMATCH",reason(entry().copy(requestedName="ordinary.mp4")))
        assertEquals("DIAGNOSTIC_CREATION_PROOF_MISSING",reason(token=false))
        assertEquals("DIAGNOSTIC_CREATION_PROOF_MISSING",reason(receipt=false))
    }
    @Test fun copiedNamespaceWithAnOrdinaryBundleAndExtraAssetsIsRejected() {
        assertEquals("DIAGNOSTIC_BUNDLE_MISMATCH",reason(entry().copy(bundleId="different")))
        assertEquals("DIAGNOSTIC_ASSETS_MISMATCH",reason(entry().copy(assets=emptyList())))
        assertEquals("DIAGNOSTIC_ASSETS_MISMATCH",reason(entry().copy(assets=entry().assets+entry().assets)))
    }
    @Test fun existingEightFileCapIsReservedInsteadOfRaised() {
        assertTrue(ProbeRetainedPolicy.hasRoom(4)); assertFalse(ProbeRetainedPolicy.hasRoom(5))
        assertFalse(ProbeRetainedPolicy.hasRoom(8)); assertFalse(ProbeRetainedPolicy.hasRoom(-1))
    }
    @Test fun partialOrDifferentRunCannotAuthorizeRetirement() {
        val run=ProbeRetainedPolicy.split(namespace)!!.first
        val proof=obj("run" to run,"suite" to "full","sequenceStatus" to "PASS","nativeCleanup" to "CONFIRMED","files" to 4,"frames" to 600)
        assertTrue(ProbeRetainedPolicy.sequenceValidated(namespace,proof,null))
        for((key,value) in listOf("frames" to JsonPrimitive(497),"suite" to JsonPrimitive("health"),
            "run" to JsonPrimitive("other"),"nativeCleanup" to JsonPrimitive("UNCONFIRMED"),"sequenceStatus" to JsonPrimitive("FAIL"))) {
            assertFalse(ProbeRetainedPolicy.sequenceValidated(namespace,JsonObject(proof+(key to value)),null))
        }
        assertFalse(ProbeRetainedPolicy.sequenceValidated(namespace,null,null))
    }
    @Test fun legacyReportRequiresCompletedDecodeRatherThanClosedFileCount() {
        val run=ProbeRetainedPolicy.split(namespace)!!.first
        fun report(sequence: JsonObject)=obj("runId" to run,"cleanup" to "CONFIRMED","tests" to listOf(obj("id" to "p1_basic",
            "data" to obj("scope" to "P1_BASIC_SYNTHETIC_DIRECT_GL","fullSequence" to sequence))))
        val passed=obj("status" to "PASS","files" to 4,"frames" to 600,"issues" to emptyList<String>())
        assertTrue(ProbeRetainedPolicy.sequenceValidated(namespace,null,report(passed)))
        assertFalse(ProbeRetainedPolicy.sequenceValidated(namespace,null,report(obj("closedFiles" to 4,"encodedFrames" to 497))))
    }
    @Test fun realCameraRetirementRequiresItsOwnDecodedProofAndExactAdmittedCount() {
        val run="413d1817-7327-4d81-b7ed-fdfc1e341a5b";val ns="p2-$run-camera"
        val proof=obj("run" to run,"suite" to "camera","kind" to "OPENAVM_CAMERA_PROOF","seconds" to 240,
            "nativeCleanup" to "CONFIRMED","sequenceStatus" to "PASS","files" to 4,"frames" to 7_190,"admittedFrames" to 7_190)
        assertTrue(ProbeRetainedPolicy.sequenceValidated(ns,proof,null))
        for((k,v) in listOf("kind" to JsonPrimitive("SYNTHETIC"),"admittedFrames" to JsonPrimitive(7200),
            "sequenceStatus" to JsonPrimitive("FAIL"),"nativeCleanup" to JsonPrimitive("UNCONFIRMED"),"seconds" to JsonPrimitive(3600)))
            assertFalse(ProbeRetainedPolicy.sequenceValidated(ns,JsonObject(proof+(k to v)),null))
        assertNull(ProbeRetainedPolicy.split("p2-$run-full"))
        assertNull(ProbeRetainedPolicy.split("p1-$run-camera"))
    }
}
