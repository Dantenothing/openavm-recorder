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
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = true, previewDesired = false),
        )
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = false, previewDesired = true),
        )
        assertFalse(
            SegmentPreviewPolicy.includeInNewSession(previewConfigured = false, previewDesired = false),
        )
    }

    @Test
    fun previewSessionFailureRetriesOnlyWhenPreviewWasIncluded() {
        assertTrue(SegmentPreviewPolicy.shouldRetryRecorderOnly(previewIncluded = true))
        assertFalse(SegmentPreviewPolicy.shouldRetryRecorderOnly(previewIncluded = false))
    }
}
