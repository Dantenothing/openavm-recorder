package com.dante.zeekrcapabilitylab.usbexport

import org.junit.Assert.*
import org.junit.Test

class UsbManualDeleteBatchTest {
    private fun request(id: Int, volume: String = "USB") = OpenAvmUsbDeleteRequest("record-$id", volume,
        listOf(OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.SEGMENT_BUNDLE, "bundle-$id")))
    private class Fixture(requests: List<OpenAvmUsbDeleteRequest>) {
        val files = requests.groupBy { it.storageUuid.lowercase() }.mapValues { (_, rows) -> rows.flatMap { it.units }.toMutableSet() }
        var locks = 0; var scans = 0; var removals = 0; var held = false
        var guard: (OpenAvmUsbDeleteRequest) -> Unit = {}
        var leaveFile = false; var failVerify = false
        val runner = UsbManualDeleteBatch<String>(
            withTarget = { _, action -> check(!held); locks++; held = true; try { action() } finally { held = false } },
            inspect = { uuid, _ -> check(held); scans++; uuid.lowercase() },
            remove = { uuid, row ->
                check(held); guard(row)
                check(row.units.all { it in files.getValue(uuid) }) { "UNIT_NOT_FRESH" }
                removals++
                if (!leaveFile) files.getValue(uuid).removeAll(row.units.toSet())
                100L
            },
            remaining = { uuid, _ -> check(held); scans++; check(!failVerify) { "TARGET_NOT_MOUNTED" }; files.getValue(uuid).toSet() },
        )
    }
    @Test fun twelveRecordingsOnOneUsbUseOneLockAndTwoCatalogPasses() {
        val requests = (1..12).map { request(it) }; val f = Fixture(requests)
        val result = f.runner.delete(requests)
        assertEquals(12, result.deletedRecordings); assertEquals(12, result.deletedUnits)
        assertEquals(1200L, result.deletedBytes); assertTrue(result.errors.isEmpty())
        assertEquals(1, f.locks); assertEquals(2, f.scans)
    }
    @Test fun rechecksEachRequestSoRecordingOrLeasesStartingMidBatchRemainProtected() {
        val rows = (1..3).map { request(it) }; val f = Fixture(rows)
        f.guard = { if (it.recordingKey != "record-1") error("RECORDING_ACTIVE") }
        val result = f.runner.delete(rows)
        assertEquals(1, result.deletedRecordings); assertEquals(2, result.blockedRecordings)
        assertEquals(1, f.removals); assertEquals(2, f.files.getValue("usb").size)
        assertTrue(result.errors.all { it.endsWith("RECORDING_ACTIVE") })
    }
    @Test fun aSuccessfulDeleteCallDoesNotPassWhenTheCatalogStillContainsTheUnit() {
        val rows = listOf(request(1)); val f = Fixture(rows); f.leaveFile = true
        val result = f.runner.delete(rows)
        assertEquals(0, result.deletedRecordings)
        assertTrue(result.errors.single().endsWith("POST_DELETE_CATALOG_VERIFY_FAILED"))
    }
    @Test fun disconnectDuringFinalVerificationCannotClaimSuccessfulDeletion() {
        val rows = listOf(request(1), request(2)); val f = Fixture(rows); f.failVerify = true
        val result = f.runner.delete(rows)
        assertEquals(0, result.deletedRecordings); assertEquals(2, result.blockedRecordings)
        assertTrue(result.errors.all { it.endsWith("TARGET_NOT_MOUNTED") })
    }
    @Test fun overlappingRequestsCannotCountTheSameUnitTwice() {
        val first = request(1); val second = first.copy(recordingKey = "other-row")
        val f = Fixture(listOf(first, second)); val result = f.runner.delete(listOf(first, second))
        assertEquals(1, f.removals); assertEquals(1, result.deletedUnits); assertEquals(1, result.blockedRecordings)
    }
    @Test fun eachPhysicalVolumeHasSeparateEvidenceAndEveryNewBatchRescans() {
        val rows = listOf(request(1, "USB"), request(2, "usb"), request(3, "OTHER")); val f = Fixture(rows)
        assertEquals(3, f.runner.delete(rows).deletedRecordings)
        assertEquals(2, f.locks); assertEquals(4, f.scans)
        assertEquals(0, f.runner.delete(rows).deletedRecordings)
        assertEquals(6, f.scans) // only prechecks: all units are absent on the second call
    }
}
