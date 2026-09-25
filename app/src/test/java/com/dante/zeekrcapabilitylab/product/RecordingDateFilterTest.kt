package com.dante.zeekrcapabilitylab.product

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordingDateFilterTest {
    private val zone = ZoneId.of("Australia/Adelaide")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun day(value: String) = LocalDate.parse(value).toEpochDay()

    @Test fun localMidnightUsesVehicleTimezoneNotUtc() {
        assertTrue(RecordingDateFilter.matches(at("2026-09-21T14:30:00Z"), day("2026-09-22"), zone))
        assertFalse(RecordingDateFilter.matches(at("2026-09-21T14:29:59.999Z"), day("2026-09-22"), zone))
        assertFalse(RecordingDateFilter.matches(at("2026-09-22T14:30:00Z"), day("2026-09-22"), zone))
    }
    @Test fun springDayHasTwentyThreeHoursAndDoesNotIncludeNextDay() {
        assertTrue(RecordingDateFilter.matches(at("2026-10-04T13:29:59.999Z"), day("2026-10-04"), zone))
        assertFalse(RecordingDateFilter.matches(at("2026-10-04T13:30:00Z"), day("2026-10-04"), zone))
    }
    @Test fun repeatedAutumnHourBelongsToSameLocalDate() {
        listOf("2026-04-04T16:00:00Z", "2026-04-04T17:00:00Z", "2026-04-05T14:29:59Z").forEach {
            assertTrue(RecordingDateFilter.matches(at(it), day("2026-04-05"), zone))
        }
        assertFalse(RecordingDateFilter.matches(at("2026-04-05T14:30:00Z"), day("2026-04-05"), zone))
    }
    @Test fun allDatesKeepsUnknownTimesButSpecificDateDoesNotInventOne() {
        assertTrue(RecordingDateFilter.matches(0, null, zone))
        assertFalse(RecordingDateFilter.matches(0, day("2026-09-22"), zone))
        assertFalse(RecordingDateFilter.matches(-1, day("2026-09-22"), zone))
    }
    @Test fun yesterdayIsCalendarDayAcrossYearBoundary() {
        val today = RecordingDateFilter.today(at("2026-12-31T13:30:00Z"), zone)
        assertEquals(LocalDate.of(2027, 1, 1), today)
        assertEquals(LocalDate.of(2026, 12, 31), today.minusDays(1))
    }
}
