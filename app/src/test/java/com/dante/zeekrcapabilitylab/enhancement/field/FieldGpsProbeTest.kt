package com.dante.zeekrcapabilitylab.enhancement.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the pure gate only; these tests never access an Android location provider. */
class FieldGpsProbeTest {
    private fun sample() = FieldGpsSample(
        provider = "gps", elapsedMs = 10_000L, epochMs = 1_790_000_000_000L,
        latitude = -34.9, longitude = 138.6, accuracyM = 5.0,
        speedMps = 10.0, speedAccuracyMps = 0.5, mock = false, cached = false,
    )

    private fun quality(sample: FieldGpsSample = sample(), nowMs: Long = 10_000L) =
        FieldGpsQualityGate.evaluate(sample, nowMs)

    private fun assertRejected(reason: String, sample: FieldGpsSample, nowMs: Long = 10_000L) {
        assertEquals(FieldGpsQuality(false, false, reason), quality(sample, nowMs))
    }

    @Test fun allEligibleProvidersCanSupplyALiveFix() {
        for (provider in listOf("gps", "network", "fused")) {
            assertEquals(FieldGpsQuality(true, true, "VALID"), quality(sample().copy(provider = provider)))
        }
        for (provider in listOf("", "passive", "unknown")) {
            assertRejected("INVALID_PROVIDER", sample().copy(provider = provider))
        }
    }

    @Test fun freshnessUsesMonotonicTimeWithAnInclusiveFiveSecondBoundary() {
        assertTrue(quality(nowMs = 15_000L).positionValid)
        assertRejected("STALE", sample(), nowMs = 15_001L)
        assertRejected("FUTURE", sample(), nowMs = 9_999L)
        assertTrue(quality(sample().copy(epochMs = 1L)).positionValid)
    }

    @Test fun malformedClocksAndExtremeAgesCannotPass() {
        assertRejected("INVALID_TIME", sample().copy(elapsedMs = -1L))
        assertRejected("INVALID_TIME", sample().copy(epochMs = 0L))
        assertRejected("INVALID_TIME", sample().copy(epochMs = -1L))
        assertRejected("INVALID_TIME", sample(), nowMs = -1L)
        assertRejected("STALE", sample().copy(elapsedMs = 0L), nowMs = Long.MAX_VALUE)
        assertRejected("FUTURE", sample().copy(elapsedMs = Long.MAX_VALUE))
        assertTrue(quality(sample().copy(elapsedMs = 0L), nowMs = 0L).positionValid)
    }

    @Test fun cachedAndMockFixesNeverProveLiveReception() {
        assertRejected("CACHED", sample().copy(cached = true))
        assertRejected("STALE", sample().copy(cached = true), nowMs = 15_001L)
        assertRejected("MOCK", sample().copy(mock = true))
        assertRejected("MOCK", sample().copy(mock = true, speedMps = 0.0))
    }

    @Test fun invalidCoordinatesRejectBothPositionAndSpeed() {
        for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -90.001, 90.001)) {
            assertRejected("INVALID_COORDINATES", sample().copy(latitude = latitude))
        }
        for (longitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -180.001, 180.001)) {
            assertRejected("INVALID_COORDINATES", sample().copy(longitude = longitude))
        }
        for (latitude in listOf(-90.0, 0.0, 90.0)) {
            for (longitude in listOf(-180.0, 0.0, 180.0)) {
                assertTrue(quality(sample().copy(latitude = latitude, longitude = longitude)).positionValid)
            }
        }
    }

    @Test fun accuracyMustExistAndBeFiniteWithinInclusiveBounds() {
        assertRejected("NO_ACCURACY", sample().copy(accuracyM = null))
        for (accuracy in listOf(-0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertRejected("INVALID_ACCURACY", sample().copy(accuracyM = accuracy))
        }
        assertRejected("POOR_ACCURACY", sample().copy(accuracyM = 50.001))
        assertTrue(quality(sample().copy(accuracyM = 0.0)).positionValid)
        assertTrue(quality(sample().copy(accuracyM = 50.0)).positionValid)
    }

    @Test fun missingSpeedStaysUnknownAndReportedZeroIsValid() {
        assertEquals(FieldGpsQuality(true, false, "NO_SPEED"), quality(sample().copy(speedMps = null)))
        assertEquals(FieldGpsQuality(true, true, "VALID"), quality(sample().copy(speedMps = 0.0)))
        assertTrue(quality(sample().copy(speedMps = 100.0)).speedValid)
    }

    @Test fun invalidSpeedDoesNotDiscardAValidPosition() {
        for (speed in listOf(-0.001, 100.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(FieldGpsQuality(true, false, "INVALID_SPEED"), quality(sample().copy(speedMps = speed)))
        }
    }

    @Test fun speedAccuracyRejectsOnlySpeedAndAllowsTheThreeMeterBoundary() {
        for (accuracy in listOf(null, 0.0, 3.0)) {
            assertTrue(quality(sample().copy(speedAccuracyMps = accuracy)).speedValid)
        }
        assertEquals(FieldGpsQuality(true, false, "POOR_SPEED_ACCURACY"), quality(sample().copy(speedAccuracyMps = 3.001)))
        for (accuracy in listOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(FieldGpsQuality(true, false, "INVALID_SPEED_ACCURACY"), quality(sample().copy(speedAccuracyMps = accuracy)))
        }
        assertFalse(quality(sample().copy(speedMps = null, speedAccuracyMps = 0.0)).speedValid)
    }
}
