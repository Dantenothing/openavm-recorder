package com.dante.zeekrcapabilitylab.enhancement.field

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VehicleSignalTests {
    private fun spec(key: String) = VehicleSignalCatalog.signals.single { it.key == key }
    private fun decode(key: String, raw: Number?) = VehicleSignalDecode.decode(spec(key), raw)

    @Test fun catalogIsFixedUniqueAndBilingual() {
        val signals = VehicleSignalCatalog.signals
        assertEquals(61, signals.size)
        assertEquals(signals.size, signals.map { it.key }.toSet().size)
        assertEquals(signals.size, signals.map { Triple(it.kind, it.id, it.zone) }.toSet().size)
        assertTrue(signals.all { it.nameEn.isNotBlank() && it.nameZh.any { char -> char.code > 127 } })
        assertEquals(VehicleReadKind.entries.toSet(), signals.map { it.kind }.toSet())
        assertTrue(signals.none { it.confidence == "confirmed" })
    }

    @Test fun rolesAndCargoZonesDoNotAssumeDrivingSide() {
        assertEquals(0x1, spec("door_driver").zone)
        assertEquals(0x4, spec("door_passenger").zone)
        assertEquals(0x10000000, spec("door_frunk").zone)
        assertEquals(0x20000000, spec("door_trunk").zone)
        assertTrue(spec("door_driver").nameEn.contains("Driver"))
    }

    @Test fun allowlistRejectsChangedIdsZonesKindsAndUnknownKeys() {
        val door = spec("door_driver")
        assertTrue(VehicleSignalCatalog.allows(door))
        listOf(door.copy(id = door.id + 1), door.copy(zone = 0),
            door.copy(kind = VehicleReadKind.FUNCTION), door.copy(key = "custom"))
            .forEach { assertFalse(VehicleSignalCatalog.allows(it)) }
        assertEquals("NOT_ALLOWLISTED", VehicleSignalDecode.decode(door.copy(id = 123), 0).status)
    }

    @Test fun knownSpeedIsConvertedOnlyOnceFromMetresPerSecond() {
        val speed = decode("speed", 10.0f)
        assertEquals(10.0, speed.raw!!, 0.0)
        assertEquals(36.0, speed.value!!, 0.000001)
        assertEquals("km/h", spec("speed").unit)
        assertTrue(speed.valid)
        assertEquals(0.0, decode("speed", 0).value!!, 0.0)
        assertEquals("OUT_OF_RANGE", decode("speed", -10.0).status)
    }

    @Test fun exactGearEventsDecodeWithoutGuessingLowByte() {
        mapOf(0x00200230 to "P", 0x00200240 to "R", 0x00200210 to "N", 0x00200220 to "D")
            .forEach { (raw, text) -> assertEquals(text, decode("gear", raw).text) }
        for (raw in listOf(0, 1, 0x30, 0x11200230)) {
            assertFalse(decode("gear", raw).valid)
            assertEquals(raw.toDouble(), decode("gear", raw).raw!!, 0.0)
        }
    }

    @Test fun enumSentinelsAreNeverInterpretedAsVehicleStates() {
        for (key in listOf("gear", "indicator", "belt_driver")) {
            for (raw in listOf(255, 254, 253, -1, -65535)) {
                val reading = decode(key, raw)
                assertFalse("$key / $raw", reading.valid)
                assertNull(reading.value)
                assertNull(reading.text)
                assertEquals("SENTINEL", reading.status)
                assertTrue(reading.sourceAvailable)
                assertEquals(raw.toDouble(), reading.raw!!, 0.0)
            }
        }
    }

    @Test fun sensorValueSentinelsKeepRawEvidenceWithoutBecomingValues() {
        for (key in listOf("speed", "steering", "temp_outside", "range")) {
            for (raw in listOf(255.0, -65535.0)) {
                val reading = decode(key, raw)
                assertFalse("$key / $raw", reading.valid)
                assertNull(reading.value)
                assertEquals("SENTINEL", reading.status)
                assertTrue(reading.sourceAvailable)
                assertEquals(raw, reading.raw!!, 0.0)
            }
        }
    }

    @Test fun numericSensorValuesDoNotUseIntegerEnumSentinels() {
        for (key in listOf("temp_outside", "temp_inside", "battery_temp", "steering")) {
            val reading = decode(key, -1.0)
            assertTrue("$key may legitimately report minus one", reading.valid)
            assertEquals(-1.0, reading.value!!, 0.0)
        }
        for (key in listOf("range", "odometer")) {
            for (raw in listOf(253, 254.0)) {
                val reading = decode(key, raw)
                assertTrue("$key / $raw", reading.valid)
                assertEquals(raw.toDouble(), reading.value!!, 0.0)
            }
        }
        assertEquals("PROVISIONAL_RAW", decode("steering", -1.0).status)
    }

    @Test fun removingIntegerSentinelsDoesNotAdmitNegativeSpeedOrDistance() {
        for (key in listOf("speed", "range", "odometer")) {
            val reading = decode(key, -1.0)
            assertFalse(reading.valid)
            assertNull(reading.value)
            assertEquals("OUT_OF_RANGE", reading.status)
            assertEquals(-1.0, reading.raw!!, 0.0)
        }
    }

    @Test fun nonFiniteAndTinyPlaceholdersAreJsonSafe() {
        for (raw in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val reading = decode("speed", raw)
            assertNull(reading.raw)
            assertNull(reading.value)
            assertFalse(reading.valid)
            assertEquals("NON_FINITE", reading.status)
        }
        for (raw in listOf(Float.MIN_VALUE, -Float.MIN_VALUE, 1e-8)) {
            assertEquals("TINY_SENTINEL", decode("steering", raw).status)
        }
        assertTrue(decode("steering", 0).valid)
        assertTrue(decode("steering", 0.000001).valid)
        assertEquals("NON_FINITE_CONVERSION", decode("speed", Double.MAX_VALUE).status)
    }

    @Test fun booleansAreKnownZeroOrOneOnly() {
        assertEquals("off", decode("aeb", 0).text)
        assertEquals("on", decode("aeb", 1).text)
        assertEquals("open", decode("door_trunk", 1).text)
        assertEquals("fastened", decode("belt_driver", 1).text)
        for (key in listOf("aeb", "door_trunk", "belt_driver", "stock_popup")) {
            assertEquals("UNKNOWN_ENUM", decode(key, 2).status)
            assertEquals("NON_INTEGER_ENUM", decode(key, 1.5).status)
        }
        assertEquals("UNKNOWN_ENUM", decode("door_driver", 0x21020101).status)
    }

    @Test fun seatEventsAcceptDocumentedFullTypesWithoutGuessingLowBytes() {
        assertEquals("occupied", decode("seat_driver", 0x00203302).text)
        assertEquals("unoccupied", decode("seat_passenger", 0x00203401).text)
        assertEquals("occupied", decode("seat_passenger", 0x00203402).text)
        assertFalse(decode("seat_driver", 2).valid)
        assertFalse(decode("seat_driver", 0x00203402).valid)
        for (raw in listOf(1, 2, 0x00203300, 0x00203303, 0x11203301, 0x00203502)) {
            assertEquals("UNKNOWN_ENUM", decode("seat_passenger", raw).status)
        }
    }

    @Test fun passengerSeatAcceptsSharedOccupancyEnumObservedInFieldReport() {
        // The passenger getter returned the shared 0x00203301 enum in field1.
        // This verifies compatibility, not the occupant's physical presence during that test.
        val empty = decode("seat_passenger", 0x00203301)
        assertTrue(empty.valid)
        assertEquals("unoccupied", empty.text)
        assertEquals(0x00203301.toDouble(), empty.raw!!, 0.0)
        assertEquals("occupied", decode("seat_passenger", 0x00203302).text)
        assertEquals("reference_observed", spec("seat_passenger").confidence)
    }

    @Test fun provisionalPedalAndSteeringScalesStayRaw() {
        for (key in listOf("steering", "brake_depth", "throttle_depth")) {
            val reading = decode(key, 7.3)
            assertEquals(7.3, reading.value!!, 0.0)
            assertEquals("PROVISIONAL_RAW", reading.status)
            assertNull(spec(key).unit)
            assertEquals("provisional", spec(key).confidence)
        }
        assertEquals(-8.9, decode("steering", -8.9).value!!, 0.0)
    }

    @Test fun enumLevelsAndFactoryPopupDoNotTreatEveryNonzeroAsTrue() {
        assertEquals("medium", decode("fcw", 0x200E0202).text)
        assertFalse(decode("fcw", 5).valid)
        assertEquals("shown", decode("stock_360", 1).text)
        assertEquals("hidden", decode("stock_360", 2).text)
        assertFalse(decode("stock_360", 0).valid)
        assertEquals("driving", decode("ignition", 0x00200107).text)
        assertFalse(decode("ignition", 7).valid)
    }

    @Test fun basicPhysicalBoundsAndRawOnlyShadeAreExplicit() {
        assertEquals("OUT_OF_RANGE", decode("battery", 101.0).status)
        assertEquals("OUT_OF_RANGE", decode("odometer", -2.0).status)
        assertEquals(-10.0, decode("temp_outside", -10.0).value!!, 0.0)
        assertEquals("RAW_ONLY", decode("sunroof_shade", 83).status)
        assertEquals(83.0, decode("sunroof_shade", 83).value!!, 0.0)
    }

    @Test fun noValueAndNoSourceAreSeparateStates() {
        val returnedNull = decode("speed", null)
        val missing = VehicleSignalDecode.unavailable(spec("speed"), "SDK_UNAVAILABLE")
        assertFalse(returnedNull.valid)
        assertTrue(returnedNull.sourceAvailable)
        assertEquals("NO_VALUE", returnedNull.status)
        assertFalse(missing.valid)
        assertFalse(missing.sourceAvailable)
        assertEquals("SDK_UNAVAILABLE", missing.status)
    }

    @Test fun sdkAdmissionRejectsSecondCallerWithoutWaitingForBlockedSdk() {
        val admission = VehicleSdkAdmission()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEnteredSdk = AtomicBoolean(false)
        val firstResult = AtomicReference<String?>()
        val secondResult = AtomicReference<String?>()
        val first = Thread {
            firstResult.set(admission.tryCall({ "SDK_BUSY" }) {
                firstEntered.countDown()
                releaseFirst.await()
                "FIRST_DONE"
            })
        }
        val second = Thread {
            try {
                secondResult.set(admission.tryCall({ "SDK_BUSY" }) {
                    secondEnteredSdk.set(true)
                    "SECOND_ENTERED"
                })
            } finally {
                secondFinished.countDown()
            }
        }
        try {
            first.start()
            assertTrue("First caller entered the simulated SDK", firstEntered.await(2, TimeUnit.SECONDS))
            second.start()
            assertTrue("Busy caller returns while first is still blocked", secondFinished.await(2, TimeUnit.SECONDS))
            assertEquals("SDK_BUSY", secondResult.get())
            assertFalse(secondEnteredSdk.get())
            assertEquals(1L, releaseFirst.count)
        } finally {
            releaseFirst.countDown()
            first.join(2_000)
            second.join(2_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals("FIRST_DONE", firstResult.get())
        assertEquals("NEXT_DONE", admission.tryCall({ "SDK_BUSY" }) { "NEXT_DONE" })
    }

    @Test fun sdkAdmissionReleasesAfterAnExceptionAndRejectsReentrantCall() {
        val admission = VehicleSdkAdmission()
        val failure = IllegalStateException("simulated SDK failure")
        try {
            admission.tryCall({ "SDK_BUSY" }) { throw failure }
            fail("Expected the original failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals("SDK_BUSY", admission.tryCall({ "UNEXPECTED_BUSY" }) {
            admission.tryCall({ "SDK_BUSY" }) { "UNEXPECTED_REENTRY" }
        })
        assertEquals("NEXT_DONE", admission.tryCall({ "SDK_BUSY" }) { "NEXT_DONE" })
    }

    @Test fun exactGettersRouteToIdsAndZonesWithoutSideEffects() {
        val functions = FunctionFake()
        val sensors = SensorFake()
        val access = VehicleSignalAccess(CarFake(functions, sensors))
        assertTrue(access.status.startsWith("GETTERS_READY;"))
        access.read(spec("aeb"))
        access.read(spec("door_trunk"))
        access.read(spec("gear"))
        access.read(spec("speed"))
        assertEquals(listOf(0x20070E00 to null, 0x21020100 to 0x20000000), functions.reads)
        assertEquals(listOf("event" to 0x00200200, "value" to 0x00100100), sensors.reads)
        assertEquals(0, functions.writes)
    }

    @Test fun forgedSpecIsRejectedBeforeAnyGetterCall() {
        val functions = FunctionFake()
        val access = VehicleSignalAccess(CarFake(functions, SensorFake()))
        val rejected = access.read(spec("aeb").copy(id = 9))
        assertEquals("NOT_ALLOWLISTED", rejected.status)
        assertFalse(rejected.sourceAvailable)
        assertTrue(functions.reads.isEmpty())
    }

    @Test fun privateImplementationsAreReachedThroughPublicInterfaces() {
        val access = VehicleSignalAccess(CarFake(HiddenFunctions(), null))
        assertEquals("on", access.read(spec("aeb")).text)
        assertEquals("closed", access.read(spec("door_driver")).text)
        assertFalse(access.read(spec("gear")).sourceAvailable)
    }

    @Test fun missingManagersAndMissingExactOverloadsHaveDifferentStatuses() {
        val missingManager = VehicleSignalAccess(CarFake(null, null))
        assertEquals("MANAGER_RETURNED_NULL", missingManager.read(spec("speed")).status)
        assertTrue(missingManager.status.startsWith("NO_READABLE_GETTERS;"))
        val partial = VehicleSignalAccess(CarFake(OneGetter(), null))
        assertTrue(partial.read(spec("aeb")).sourceAvailable)
        assertEquals("GETTER_UNAVAILABLE", partial.read(spec("door_driver")).status)
        val absent = VehicleSignalAccess(Any())
        assertEquals("MANAGER_GETTER_UNAVAILABLE", absent.read(spec("gear")).status)
    }

    @Test fun successfulInvalidAndNonNumericResponsesAreNotMissingSources() {
        val functions = FunctionFake().apply { next = 255 }
        val access = VehicleSignalAccess(CarFake(functions, null))
        assertTrue(access.read(spec("aeb")).sourceAvailable)
        assertFalse(access.read(spec("aeb")).valid)
        functions.next = "never include this SDK payload"
        val nonNumeric = access.read(spec("aeb"))
        assertEquals("NON_NUMERIC_VALUE", nonNumeric.status)
        assertTrue(nonNumeric.sourceAvailable)
        assertNull(nonNumeric.raw)
        assertNull(nonNumeric.text)
        functions.next = null
        assertEquals("NO_VALUE", access.read(spec("aeb")).status)
    }

    @Test fun exceptionReportsContainOnlyClassesWithoutPayloads() {
        val access = VehicleSignalAccess(CarFake(ThrowingFunctions(), null))
        val reading = access.read(spec("aeb"))
        assertEquals("READ_FAILED:IllegalStateException", reading.status)
        assertFalse(reading.sourceAvailable)
        assertNull(reading.raw)
        val manager = VehicleSignalAccess(ThrowingCar())
        assertEquals("MANAGER_FAILED:SecurityException", manager.read(spec("aeb")).status)
        assertFalse(manager.status.contains("PRIVATE"))
        assertEquals("IllegalArgumentException", VehicleSignalAccess.errorClass(
            InvocationTargetException(IllegalArgumentException("PRIVATE MESSAGE"))))
    }

    class CarFake(private val functions: Any?, private val sensors: Any?) {
        fun getICarFunction(): Any? = functions
        fun getSensorManager(): Any? = sensors
    }

    interface FunctionContract {
        fun getFunctionValue(id: Int): Any?
        fun getFunctionValue(id: Int, zone: Int): Any?
    }

    private class HiddenFunctions : FunctionContract {
        override fun getFunctionValue(id: Int): Any = 1
        override fun getFunctionValue(id: Int, zone: Int): Any = 0
    }

    class FunctionFake : FunctionContract {
        val reads = mutableListOf<Pair<Int, Int?>>()
        var next: Any? = 1
        var writes = 0
        override fun getFunctionValue(id: Int): Any? { reads += id to null; return next }
        override fun getFunctionValue(id: Int, zone: Int): Any? { reads += id to zone; return next }
        @Suppress("UNUSED_PARAMETER") fun setFunctionValue(id: Int, value: Int) { writes++ }
    }

    class SensorFake {
        val reads = mutableListOf<Pair<String, Int>>()
        fun getSensorEvent(id: Int): Int { reads += "event" to id; return 0x00200230 }
        fun getSensorLatestValue(id: Int): Float { reads += "value" to id; return 10f }
    }

    class OneGetter {
        @Suppress("UNUSED_PARAMETER") fun getFunctionValue(id: Int): Int = 1
        // Wrong parameter shape must not be adapted or guessed.
        @Suppress("UNUSED_PARAMETER") fun getFunctionValue(id: Int, zone: String): Int = 1
    }

    class ThrowingFunctions {
        @Suppress("UNUSED_PARAMETER") fun getFunctionValue(id: Int): Int = error("PRIVATE SDK PAYLOAD")
    }

    class ThrowingCar {
        fun getICarFunction(): Any = throw SecurityException("PRIVATE ACCOUNT MESSAGE")
        fun getSensorManager(): Any? = null
    }
}
