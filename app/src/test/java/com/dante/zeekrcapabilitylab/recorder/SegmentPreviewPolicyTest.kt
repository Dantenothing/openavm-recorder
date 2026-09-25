package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.SegmentPreviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPreviewPolicyTest {

    @Test fun hiddenRetainedMirrorRemainsConfiguredAcrossRollover() {
        // Home -> another page/dock while the minute changes. A later request
        // may only target surfaces configured in that SAME capture session.
        assertTrue(SegmentPreviewPolicy.includeInNewSession(
            previewConfigured = true, previewDesired = false, retainedPreview = true))
        assertFalse(SegmentPreviewPolicy.includeInNewSession(
            previewConfigured = false, previewDesired = false, retainedPreview = true))
    }

    @Test fun reattachDuringRolloverCanBeRememberedButTerminalCleanupCannotEnablePreview() {
        assertTrue(SegmentPreviewPolicy.canRememberEnable(false, false, false, false))
        assertFalse(SegmentPreviewPolicy.canRememberEnable(true, false, false, false))
        assertFalse(SegmentPreviewPolicy.canRememberEnable(false, true, false, false))
        assertFalse(SegmentPreviewPolicy.canRememberEnable(false, false, true, false))
        assertFalse(SegmentPreviewPolicy.canRememberEnable(false, false, false, true))
    }

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
}
