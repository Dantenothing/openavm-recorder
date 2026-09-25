package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.*
import org.junit.Test

class RecordingCapacityTest {
    @Test fun missingOrOverflowedFileSizesCannotInventFreeQuota() {
        assertEquals(20L, RecordingCapacityMath.knownUsage(listOf(8, 12)))
        assertEquals(0L, RecordingCapacityMath.knownUsage(emptyList()))
        assertNull(RecordingCapacityMath.knownUsage(listOf(8, null)))
        assertNull(RecordingCapacityMath.knownUsage(listOf(Long.MAX_VALUE, 1)))
    }
    @Test fun reservesAndQuotaBothLimitAvailableSpace() {
        assertEquals(30L, RecordingCapacityMath.available(100, 20, 50, 20))
        assertEquals(5L, RecordingCapacityMath.available(25, 20, 50, 20))
        assertEquals(0L, RecordingCapacityMath.available(19, 20, 50, 20))
        assertEquals(0L, RecordingCapacityMath.available(100, 20, 50, 51))
    }
    @Test fun unavailableMeasurementsCannotBePresentedAsZeroOrInternalFallback() {
        assertNull(RecordingCapacityMath.available(null, 20, 50, 20))
        assertNull(RecordingCapacityMath.available(100, 20, 50, null))
        assertNull(RecordingCapacityMath.minutes(100, null, 1))
    }
    @Test fun estimateUsesBitsAndSecondsAndAvoidsLongOverflow() {
        assertEquals(10L, RecordingCapacityMath.minutes(330_000_001, 4_000_000, 1))
        assertEquals(30L, RecordingCapacityMath.minutes(330_000_001, 4_000_000, 3))
        assertEquals(0L, RecordingCapacityMath.minutes(0, 4_000_000, 1))
        assertTrue(RecordingCapacityMath.minutes(Long.MAX_VALUE, 1, 60)!! > 0)
    }
}
