package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.*
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest as DriveUploadCreateRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DriveDataUploadTest {
    @get:Rule val temp = TemporaryFolder()
    private val session = "00000000-0000-4000-8000-000000000001"
    private val otherSession = "00000000-0000-4000-8000-000000000002"
    private val video = "synthetic-video-for-receiver-tests".toByteArray()
    private val videoSha = hash(video)
    private fun data(id: String = session) = DriveDataCodec.encode(DriveDataBundle(
        recordingSessionId = id, segmentStartedElapsedMs = 1_000, segmentEndedElapsedMs = 5_000,
        gps = listOf(DriveGpsSample(1_250, 1_790_000_000_000, -34.9, 138.6, 4f, "gps", 0f)),
        vehicle = listOf(DriveVehicleSample(1_250, 1_790_000_000_000, mapOf("speed" to DriveVehicleValue(1_250, number = 0.0)))),
    ))
    private fun sidecar(id: String = session) = """{"recordingSessionId":"$id","recordingMode":"NORMAL","startedAtElapsedRealtimeMs":1000,"stoppedAtElapsedRealtimeMs":5000}"""
    private fun request(task: String = "task-1", car: String = "car-1", id: String = session) = DriveUploadCreateRequest(
        clientTransferId = task, fileName = "drive.mp4", sizeBytes = video.size.toLong(), sha256 = videoSha,
        carId = car, sidecarJson = sidecar(id), driveData = DriveDataCodec.descriptor(data(id)),
    )
    @Before fun init() { ReliableUploadStore.initForTests(temp.root) }
    private fun prepare(request: DriveUploadCreateRequest = request()): String {
        val id = ReliableUploadStore.create(request, request.carId)!!.uploadId
        assertTrue(ReliableUploadStore.storeChunk(id, request.carId, 0, video))
        return id
    }
    private fun complete(id: String, car: String = "car-1", auth: (() -> Unit) -> Boolean = { it(); true }) =
        ReliableUploadStore.complete(id, car, videoSha, "drive.mp4", auth)
    private fun received(name: String = "drive.mp4") = File(temp.root, "received/" + name)

    @Test fun requestedAttachmentIsRequiredBeforeCommitAndSurvivesRestart() {
        val id = prepare()
        assertFalse(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertEquals(409, (complete(id) as ReliableCommitResult.Rejected).code)
        assertFalse(received().exists())
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        ReliableUploadStore.initForTests(temp.root)
        assertTrue(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertEquals(DriveDataCodec.decode(data()), DriveDataFiles.load(received()))
        assertTrue(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertFalse(ReliableUploadStore.cancel(id, "car-1"))
        assertTrue(DriveDataFiles.forVideo(received())!!.isFile)
        assertFalse(ReceivedFiles.visibleFiles(File(temp.root, "received")).any { it.parentFile.name == DriveDataFiles.DIRECTORY })
    }

    @Test fun stageRequiresCurrentOwnerMatchingHashAndDescriptor() {
        val id = prepare()
        assertFalse(ReliableUploadStore.storeDriveData(id, "other-car", data()))
        assertFalse(ReliableUploadStore.storeDriveData(id, "car-1", data()) { false })
        assertFalse(ReliableUploadStore.storeDriveData(id, "car-1", data(otherSession)))
        assertFalse(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertFalse(File(temp.root, "uploads/" + id + "/drive-data.partial").exists())
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        assertTrue(ReliableUploadStore.cancel(id, "car-1"))
        assertFalse(File(temp.root, "uploads/" + id).exists())
        assertFalse(received().exists())
    }

    @Test fun sameTransferCannotChangeAttachmentOrSidecarSnapshot() {
        val original = request()
        val id = ReliableUploadStore.create(original, "car-1")!!.uploadId
        assertEquals(id, ReliableUploadStore.create(original, "car-1")!!.uploadId)
        assertNull(ReliableUploadStore.create(original.copy(driveData = null), "car-1"))
        assertNull(ReliableUploadStore.create(original.copy(sidecarJson = sidecar() + " "), "car-1"))
        assertNull(ReliableUploadStore.create(original.copy(driveData = DriveDataCodec.descriptor(data(otherSession))), "car-1"))
        assertNull(ReliableUploadStore.create(original, "car-1") { false })
    }

    @Test fun mismatchingSidecarCannotAuthorizeAnOtherwiseValidPayload() {
        val request = request().copy(sidecarJson = sidecar(otherSession))
        val id = prepare(request)
        assertFalse(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        assertEquals(409, (complete(id) as ReliableCommitResult.Rejected).code)
        assertFalse(received().exists())
    }

    @Test fun revokedBeforeCommitLeavesNoVisibleVideoAndAnewGrantCanResume() {
        val id = prepare()
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        var fence = 0
        val result = complete(id) { action -> if (++fence == 1) { action(); true } else false }
        assertEquals(401, (result as ReliableCommitResult.Rejected).code)
        assertFalse(received().exists())
        assertFalse(DriveDataFiles.forVideo(received())!!.exists())
        assertTrue(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertNotNull(DriveDataFiles.load(received()))
    }

    @Test fun interruptedCompletionMetadataKeepsCommittedVideoAndAttachmentRecoverable() {
        val id = prepare()
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        var fence = 0
        val result = complete(id) { action -> if (++fence <= 2) { action(); true } else false }
        assertEquals(401, (result as ReliableCommitResult.Rejected).code)
        assertTrue(received().isFile)
        assertNotNull(DriveDataFiles.load(received()))
        ReliableUploadStore.initForTests(temp.root)
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertEquals("COMPLETED", ReliableUploadStore.status(id, "car-1")!!.status)
        assertEquals(401, (complete(id, auth = { false }) as ReliableCommitResult.Rejected).code)
    }

    @Test fun hiddenStorageFailureCannotCommitVideoAndCanBeRetried() {
        val id = prepare()
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        val blocker = File(temp.root, "received/.drive-data").apply { writeText("cannot create directory") }
        assertEquals(500, (complete(id) as ReliableCommitResult.Rejected).code)
        assertFalse(received().exists())
        assertFalse(received("drive.json").exists())
        blocker.delete()
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertNotNull(DriveDataFiles.load(received()))
    }

    @Test fun metadataLostAfterVideoCommitCanBeRestoredFromVerifiedStaging() {
        val id = prepare()
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        var fence = 0
        complete(id) { action -> if (++fence <= 2) { action(); true } else false }
        assertTrue(received().isFile)
        DriveDataFiles.forVideo(received())!!.delete()
        received("drive.json").delete()
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertArrayEquals(video, received().readBytes())
        assertNotNull(DriveDataFiles.load(received()))
    }

    @Test fun collidingNamesFromDifferentCarsRemainIsolated() {
        val first = prepare(request("first", "car-1", session))
        val second = prepare(request("second", "car-2", otherSession))
        assertTrue(ReliableUploadStore.storeDriveData(first, "car-1", data()))
        assertTrue(ReliableUploadStore.storeDriveData(second, "car-2", data(otherSession)))
        assertTrue(complete(first) is ReliableCommitResult.Success)
        val secondResult = complete(second, "car-2") as ReliableCommitResult.Success
        assertNotEquals("drive.mp4", secondResult.response.fileName)
        assertEquals(session, DriveDataFiles.load(received())!!.recordingSessionId)
        assertEquals(otherSession, DriveDataFiles.load(received(secondResult.response.fileName))!!.recordingSessionId)
        assertNull(ReliableUploadStore.status(second, "car-1"))
    }

    @Test fun reservedButUncommittedTargetCannotBeTakenByAnotherUpload() {
        val first = prepare()
        val second = prepare(request("second", "car-2", otherSession))
        assertTrue(ReliableUploadStore.storeDriveData(first, "car-1", data()))
        assertTrue(ReliableUploadStore.storeDriveData(second, "car-2", data(otherSession)))
        var fence = 0
        complete(first) { action -> if (++fence == 1) { action(); true } else false }
        val secondResult = complete(second, "car-2") as ReliableCommitResult.Success
        assertNotEquals("drive.mp4", secondResult.response.fileName)
        assertTrue(complete(first) is ReliableCommitResult.Success)
        assertEquals(session, DriveDataFiles.load(received())!!.recordingSessionId)
    }

    @Test fun alteredCommittedSidecarOrAttachmentIsNotReportedAsUsable() {
        val id = prepare()
        assertTrue(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        assertTrue(complete(id) is ReliableCommitResult.Success)
        received("drive.json").writeText(sidecar(otherSession))
        assertNull(DriveDataFiles.load(received()))
        assertFalse(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertEquals(409, (complete(id) as ReliableCommitResult.Rejected).code)
    }

    @Test fun noDescriptorKeepsExistingVideoOnlyWorkflow() {
        val id = prepare(request().copy(driveData = null))
        assertFalse(ReliableUploadStore.storeDriveData(id, "car-1", data()))
        assertTrue(complete(id) is ReliableCommitResult.Success)
        assertFalse(ReliableUploadStore.status(id, "car-1")!!.driveDataReceived)
        assertNull(DriveDataFiles.load(received()))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
