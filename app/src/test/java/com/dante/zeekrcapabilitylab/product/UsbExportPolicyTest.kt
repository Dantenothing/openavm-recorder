package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExportPolicyTest {

    @Test
    fun missingTargetsAreAlwaysCopied() {
        assertTrue(UsbExportPolicy.shouldCopy(targetExists = false, sourceBytes = 10L, targetBytes = 0L))
    }

    @Test
    fun sameSizeTargetsAreSkippedAsAlreadyExported() {
        assertFalse(UsbExportPolicy.shouldCopy(targetExists = true, sourceBytes = 10L, targetBytes = 10L))
    }

    @Test
    fun tornCopiesWithDifferentSizesAreRedone() {
        assertTrue(UsbExportPolicy.shouldCopy(targetExists = true, sourceBytes = 10L, targetBytes = 7L))
        assertTrue(UsbExportPolicy.shouldCopy(targetExists = true, sourceBytes = 10L, targetBytes = 12L))
    }

    @Test
    fun spaceCheckKeepsTheSafetyMargin() {
        val margin = UsbExportPolicy.FREE_SPACE_MARGIN_BYTES
        assertTrue(UsbExportPolicy.hasSpace(freeBytes = 100L + margin, nextCopyBytes = 100L))
        assertFalse(UsbExportPolicy.hasSpace(freeBytes = 99L + margin, nextCopyBytes = 100L))
        assertTrue(UsbExportPolicy.hasSpace(freeBytes = 150L, nextCopyBytes = 100L, marginBytes = 50L))
        assertFalse(UsbExportPolicy.hasSpace(freeBytes = 149L, nextCopyBytes = 100L, marginBytes = 50L))
    }
}
