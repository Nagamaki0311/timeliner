package com.nagamaki0311.timeliner.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class TimestampParsingTest {

    @Test
    fun parseTimestampMillis_isoWithPositiveOffset() {
        val expected = Instant.parse("2026-08-19T01:00:00Z").toEpochMilli()
        assertEquals(expected, parseTimestampMillis("2026-08-19T10:00:00+09:00"))
    }

    @Test
    fun parseTimestampMillis_isoWithZuluSuffix() {
        val expected = Instant.parse("2026-08-19T01:00:00Z").toEpochMilli()
        assertEquals(expected, parseTimestampMillis("2026-08-19T01:00:00Z"))
    }

    @Test
    fun parseTimestampMillis_isoWithNegativeOffsetAndFraction() {
        val expected = Instant.parse("2026-08-19T05:30:00.500Z").toEpochMilli()
        assertEquals(expected, parseTimestampMillis("2026-08-19T00:30:00.500-05:00"))
    }

    @Test
    fun parseTimestampMillis_epochMsString() {
        assertEquals(1755500400000L, parseTimestampMillis("1755500400000"))
    }

    @Test
    fun parseTimestampMillis_epochMsStringWithSurroundingWhitespace() {
        assertEquals(1755500400000L, parseTimestampMillis("  1755500400000  "))
    }

    @Test
    fun parseTimestampMillis_invalidFormatThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTimestampMillis("not-a-timestamp")
        }
    }

    @Test
    fun parseTimestampMillis_blankThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTimestampMillis("   ")
        }
    }
}
