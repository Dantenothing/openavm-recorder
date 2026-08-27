package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.ui.product.GalleryTransferAction
import com.dante.zeekrcapabilitylab.ui.product.GalleryTransferActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTransferActionPolicyTest {
    @Test
    fun transferMenuIsNeverADevelopmentPlaceholder() {
        assertTrue(GalleryTransferActionPolicy.menuEnabled)
        assertEquals(GalleryTransferAction.ENQUEUE, GalleryTransferActionPolicy.action(connected = true))
        assertEquals(GalleryTransferAction.OPEN_PHONE, GalleryTransferActionPolicy.action(connected = false))
    }
}
