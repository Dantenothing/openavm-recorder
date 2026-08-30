package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangeTest {

    @Test
    fun noRangeMeansFull() {
        assertEquals(HttpRange.Full(100), HttpRangeParser.parse(null, 100))
        assertEquals(HttpRange.Full(100), HttpRangeParser.parse("", 100))
    }

    @Test
    fun openEndedRange() {
        assertEquals(HttpRange.Partial(10, 99), HttpRangeParser.parse("bytes=10-", 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRangeParser.parse("bytes=0-", 100))
    }

    @Test
    fun boundedRangeClampsToSize() {
        assertEquals(HttpRange.Partial(5, 9), HttpRangeParser.parse("bytes=5-9", 100))
        assertEquals(HttpRange.Partial(95, 99), HttpRangeParser.parse("bytes=95-1000", 100))
    }

    @Test
    fun unsatisfiableRange() {
        assertEquals(HttpRange.Unsatisfiable(100), HttpRangeParser.parse("bytes=100-", 100))
        assertEquals(HttpRange.Unsatisfiable(100), HttpRangeParser.parse("bytes=999-", 100))
    }

    @Test
    fun malformedRangeFallsBackToFull() {
        assertEquals(HttpRange.Full(100), HttpRangeParser.parse("bytes=abc", 100))
        assertEquals(HttpRange.Full(100), HttpRangeParser.parse("items=1-2", 100))
        assertEquals(HttpRange.Full(100), HttpRangeParser.parse("bytes=-50", 100))
        assertEquals(HttpRange.Unsatisfiable(100), HttpRangeParser.parse("bytes=10-5", 100))
    }

    @Test
    fun caseInsensitiveBytesToken() {
        assertTrue(HttpRangeParser.parse("Bytes=3-", 100) is HttpRange.Partial)
    }
}
