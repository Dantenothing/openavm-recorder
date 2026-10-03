package com.dante.zeekrcapabilitylab.enhancement.probe

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import org.junit.Assert.*
import org.junit.Test

class MultiCameraProbeTest {
    private fun reject(enabled: Boolean = true, visible: Boolean = true, parked: Boolean = true,
        permission: Boolean = true, ids: List<String> = listOf("2", "1", "0"),
        declaration: ProbeDeclaration = ProbeDeclaration.NOT_LISTED, optIn: Boolean = false) =
        MultiCameraProbePolicy.rejection(enabled, visible, parked, permission, ids, declaration, optIn)

    @Test fun emptyDeclarationNeedsExplicitExperimentConsent() {
        assertEquals("UNLISTED_OPT_IN_REQUIRED", reject())
        assertNull(reject(optIn = true))
    }
    @Test fun consentCannotEnableNormalBuildOrBackgroundCamera() {
        assertEquals("PROBE_BUILD_REQUIRED", reject(enabled = false, optIn = true))
        assertEquals("PAGE_HIDDEN", reject(visible = false, optIn = true))
        assertEquals("PARK_FIRST", reject(parked = false, optIn = true))
        assertEquals("CAMERA_PERMISSION_REQUIRED", reject(permission = false, optIn = true))
    }
    @Test fun permissionAndQueryErrorsCannotBeBypassed() {
        assertEquals("API_UNAVAILABLE", reject(declaration = ProbeDeclaration.API_UNAVAILABLE, optIn = true))
        assertEquals("DECLARATION_QUERY_FAILED", reject(declaration = ProbeDeclaration.QUERY_FAILED, optIn = true))
    }
    @Test fun invalidAndDuplicateSourcesAreNeverOpened() {
        listOf(listOf("2"), listOf("2", "2"), listOf("2", ""), listOf("2", "0", "1", "3"))
            .forEach { assertEquals("INVALID_SELECTION", reject(ids = it, optIn = true)) }
    }
    @Test fun pairDeclarationDoesNotCertifyTriple() {
        val sets = setOf(setOf("2", "1"), setOf("2", "0"))
        assertEquals(ProbeDeclaration.DECLARED, MultiCameraProbePolicy.declaration(listOf("2", "1"), sets))
        assertEquals(ProbeDeclaration.NOT_LISTED, MultiCameraProbePolicy.declaration(listOf("2", "1", "0"), sets))
        assertNull(reject(declaration = ProbeDeclaration.DECLARED))
    }
    @Test fun surroundIsNeverSilentlyDowngradedToOrdinaryRaster() {
        val small = listOf(ProfileSize(640, 480), ProfileSize(1280, 720))
        assertNull(MultiCameraProbePolicy.size(true, small))
        assertEquals(ProfileSize(1280, 5140), MultiCameraProbePolicy.size(true, small + ProfileSize(1280, 5140)))
    }
    @Test fun cabinUsesDeclaredSmallSurfaceAndNeverRequestsComposite() {
        assertEquals(ProfileSize(640, 480), MultiCameraProbePolicy.size(false,
            listOf(ProfileSize(1280, 5140), ProfileSize(640, 480), ProfileSize(1920, 1080))))
        assertNull(MultiCameraProbePolicy.size(false, listOf(ProfileSize(1280, 5140), ProfileSize(-1, 480))))
    }
    @Test fun sameOrBackwardsTextureTimestampDoesNotCountAsNewFrame() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1"), 1_000)
        evidence.frame("2", 4, 1_100)
        evidence.frame("2", 4, 1_200)
        evidence.frame("2", 3, 1_300)
        evidence.frame("missing", 5, 1_400)
        assertEquals(1L, evidence.snapshot(1_500).first().frames)
        assertEquals(400L, evidence.snapshot(1_500).first().lastFrameAgeMs)
    }
    @Test fun sequentialImagesDoNotProveSimultaneousOperation() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1"), 0)
        repeat(3) { evidence.frame("2", it + 1L, it * 100L) }
        repeat(3) { evidence.frame("1", it + 1L, 5_000 + it * 100L) }
        assertNull(evidence.tick(5_500))
        assertNull(evidence.simultaneousSince)
        assertEquals("FIRST_FRAMES_TIMEOUT", evidence.tick(15_000))
    }
    @Test fun missingThirdStreamCannotPass() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1", "0"), 0)
        repeat(16) { i ->
            listOf("2", "1").forEach { evidence.frame(it, i + 1L, i * 1_000L) }
            evidence.tick(i * 1_000L)
        }
        assertEquals("FIRST_FRAMES_TIMEOUT", evidence.reason)
    }
    @Test fun allStreamsMustStayFreshForFullObservation() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1", "0"), 0)
        for (i in 0..204) {
            listOf("2", "1", "0").forEach { evidence.frame(it, i + 1L, i * 100L) }
            evidence.tick(i * 100L)
        }
        assertEquals("PREVIEW_OBSERVED", evidence.reason)
        assertEquals(200L, evidence.simultaneousSince)
        assertEquals(10.0, evidence.snapshot(20_400).first().averageFps!!, 0.001)
    }
    @Test fun oneStreamStallingEndsWholeExperiment() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1"), 0)
        repeat(3) { i -> listOf("2", "1").forEach { evidence.frame(it, i + 1L, i * 100L) } }
        evidence.tick(200)
        evidence.frame("2", 4, 1_000); evidence.tick(1_000)
        evidence.frame("2", 5, 1_800)
        assertEquals("STREAM_STALLED", evidence.tick(1_800))
    }
    @Test fun controlStallCannotBeCountedAsContinuousObservation() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1"), 0)
        repeat(3) { i -> listOf("2", "1").forEach { evidence.frame(it, i + 1L, i * 100L) } }
        evidence.tick(200)
        listOf("2", "1").forEach { evidence.frame(it, 4, 25_000) }
        assertEquals("OBSERVATION_INTERRUPTED", evidence.tick(25_000))
    }
    @Test fun stoppedRunIgnoresLateFramesAndCannotBecomePassed() {
        val evidence = MultiCameraFrameEvidence(listOf("2", "1"), 0)
        evidence.stop("PAGE_HIDDEN")
        repeat(500) { i -> listOf("2", "1").forEach { evidence.frame(it, i + 1L, i * 100L) } }
        assertEquals("PAGE_HIDDEN", evidence.tick(50_000))
        assertEquals(0L, evidence.snapshot(50_000).first().frames)
    }
    @Test fun activeTextureSurvivesViewDestructionUntilOwnerReleases() {
        val lease = ProbeTextureOwnership()
        assertTrue(lease.claim())
        assertFalse(lease.claim())
        assertFalse(lease.viewDestroyed())
        assertTrue(lease.release())
        assertFalse(lease.release())
        assertFalse(lease.claim())
    }
    @Test fun attachedTextureCanBeReusedAfterConfirmedCameraClose() {
        val lease = ProbeTextureOwnership()
        assertTrue(lease.claim()); assertFalse(lease.release())
        assertTrue(lease.claim()); assertFalse(lease.release())
        assertTrue(lease.viewDestroyed()); assertFalse(lease.claim())
    }
}
