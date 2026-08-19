package com.nagamaki0311.timeliner.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinateParsingTest {

    @Test
    fun parseCoordinateString_degreeSignFormat_android() {
        val (lat, lon) = parseCoordinateString("35.6812°, 139.7671°")
        assertEquals(35.6812, lat, 0.0)
        assertEquals(139.7671, lon, 0.0)
    }

    @Test
    fun parseCoordinateString_geoUriFormat_ios() {
        val (lat, lon) = parseCoordinateString("geo:35.6812,139.7671")
        assertEquals(35.6812, lat, 0.0)
        assertEquals(139.7671, lon, 0.0)
    }

    @Test
    fun parseCoordinateString_geoUriPrefixIsCaseInsensitive() {
        val (lat, lon) = parseCoordinateString("GEO:35.6812,139.7671")
        assertEquals(35.6812, lat, 0.0)
        assertEquals(139.7671, lon, 0.0)
    }

    @Test
    fun parseCoordinateString_negativeCoordinates() {
        val (lat, lon) = parseCoordinateString("geo:-33.8688,-70.6693")
        assertEquals(-33.8688, lat, 0.0)
        assertEquals(-70.6693, lon, 0.0)
    }

    @Test
    fun parseCoordinateString_invalidFormatThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCoordinateString("35.6812")
        }
    }

    @Test
    fun parseE7_positiveValue() {
        // 139.7671度 -> 1397671000 (E7)
        assertEquals(139.7671, parseE7(1_397_671_000L), 1e-9)
    }

    @Test
    fun parseE7_negativeValue() {
        // -33.8688度 -> -338688000 (E7)
        assertEquals(-33.8688, parseE7(-338_688_000L), 1e-9)
    }

    @Test
    fun parseE7_unsignedOverflowIsCorrectedToNegative() {
        // 本来 -33.8688度(-338688000) だが符号なしオーバーフローで 2^32 - 338688000 として記録されたケース
        val overflowed = 4_294_967_296L - 338_688_000L
        assertEquals(-33.8688, parseE7(overflowed), 1e-9)
    }

    @Test
    fun parseE7_maxValidLongitudeIsNotCorrected() {
        // 180度ちょうど(1800000000)は補正の閾値以下なのでそのまま
        assertEquals(180.0, parseE7(1_800_000_000L), 1e-9)
    }
}
