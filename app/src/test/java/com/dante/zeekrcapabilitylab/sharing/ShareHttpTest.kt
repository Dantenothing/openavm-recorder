package com.dante.zeekrcapabilitylab.sharing

import org.junit.Assert.*
import org.junit.Test

class ShareHttpTest {
    @Test fun largeRangesAndSuffixNeverTruncateToInt() {
        val size = 6L * 1024 * 1024 * 1024
        assertEquals(ByteRange.Partial(size - 100, size - 1), ByteRanges.resolve("bytes=-100", size))
        assertEquals(ByteRange.Partial(4_294_967_296, size - 1), ByteRanges.resolve("bytes=4294967296-", size))
        assertEquals(ByteRange.Partial(0, size - 1), ByteRanges.resolve("bytes=-999999999999999999999", size))
        assertEquals(ByteRange.Unsatisfiable(size), ByteRanges.resolve("bytes=999999999999999999999-", size))
    }
    @Test fun validatorsAndUnsupportedRangesUseFullRepresentation() {
        assertEquals(ByteRange.Full(123), ByteRanges.resolve("bytes=4-", 123, "\"old\"", "\"new\""))
        assertEquals(ByteRange.Full(123), ByteRanges.resolve("bytes=0-1,4-5", 123))
        assertEquals(ByteRange.Partial(4, 122), ByteRanges.resolve("bytes=4-", 123, "\"new\"", "\"new\""))
        assertEquals(ByteRange.Full(123), ByteRanges.resolve("bytes=4-", 123, "W/\"new\"", "\"new\""))
        assertEquals(ByteRange.Full(0), ByteRanges.resolve("bytes=-1", 0))
        assertEquals(ByteRange.Unsatisfiable(123), ByteRanges.resolve("bytes=-0", 123))
        assertEquals(ByteRange.Unsatisfiable(123), ByteRanges.resolve("bytes=5-2", 123))
    }
    @Test fun readsHeadWithoutAcceptingRequestBodiesOrAmbiguousHeaders() {
        fun read(text: String) = ShareHttpRequest.read(text.byteInputStream())
        assertTrue(read("HEAD /s/test HTTP/1.1\r\nHost: 127.0.0.1:1234\r\nRange: bytes=-3\r\n\r\n").headOnly)
        val rejected = listOf(
            "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\nx",
            "GET / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n",
            "GET / HTTP/1.1\r\nHost : a\r\n\r\n",
            "GET / HTTP/1.1\nHost: a\n\n",
            "GET http://example.test/ HTTP/1.1\r\nHost: a\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a\r\n\r\n",
        )
        rejected.forEach { assertThrows(ShareHttpError::class.java) { read(it) } }
    }
    @Test fun selectionBudgetUsesBytesNotConnectionCountAndCannotOverflow() {
        val limits = ShareLimits(maxFiles = 3, maxBytes = 100)
        assertTrue(limits.accepts(listOf(50, 50)))
        assertFalse(limits.accepts(listOf(60, 60)))
        assertFalse(limits.accepts(listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertFalse(limits.accepts(listOf(1, 1, 1, 1)))
        assertFalse(limits.accepts(emptyList()))
    }
}
