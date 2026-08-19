package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.SegmentPreviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPreviewPolicyTest {

    @Test
    fun previewIsIncludedOnlyWhenConfiguredAndDesired() {
        assertTrue(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = true, previewDesired = true),
        )
    }

    @Test
    fun aConfiguredButUndesiredPreviewIsExcluded() {
        // The UI reported its TextureView gone (tab switch); the retained
        // Surface must not be built into the next segment's session.
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = true, previewDesired = false),
        )
    }

    @Test
    fun aMissingPreviewSurfaceIsExcludedRegardlessOfDesire() {
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = false, previewDesired = true),
        )
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = false, previewDesired = false),
        )
    }
}
