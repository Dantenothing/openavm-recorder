package com.dante.zeekrcapabilitylab.product

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Same local start date as the library's date headings; no video reads or storage changes. */
object RecordingDateFilter {
    fun matches(startedAtEpochMs: Long, selectedEpochDay: Long?, zone: ZoneId): Boolean =
        selectedEpochDay == null || startedAtEpochMs > 0 &&
            Instant.ofEpochMilli(startedAtEpochMs).atZone(zone).toLocalDate().toEpochDay() == selectedEpochDay

    fun today(nowEpochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
}
