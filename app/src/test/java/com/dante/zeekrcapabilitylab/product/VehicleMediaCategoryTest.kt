package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleMediaCategoryTest {
    @Test fun sentryExportsNeverAppearAsNormalOrFactorySentry() {
        assertEquals(VehicleMediaCategory.OPENAVM_SENTRY, VehicleUsbMediaLibrary.categoryOf("run-id", "SENTRY"))
        assertEquals(VehicleMediaCategory.OPENAVM_SENTRY, VehicleUsbMediaLibrary.categoryOf("event:run-id", "SENTRY"))
        assertEquals(VehicleMediaCategory.EVENTS, VehicleUsbMediaLibrary.categoryOf("event:normal", "NORMAL"))
        assertEquals(VehicleMediaCategory.TIME_LAPSE, VehicleUsbMediaLibrary.categoryOf("time-lapse-id", "TIME_LAPSE"))
        assertEquals(VehicleMediaCategory.NORMAL, VehicleUsbMediaLibrary.categoryOf("normal-id", "NORMAL"))
    }
}
