package com.nagamaki0311.timeliner.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoBoundsTest {

    @Test
    fun compute_singlePoint_minEqualsMax() {
        val bounds = GeoBounds.compute(doubleArrayOf(35.0), doubleArrayOf(139.0))

        assertEquals(35.0, bounds.minLatitude, 1e-9)
        assertEquals(35.0, bounds.maxLatitude, 1e-9)
        assertEquals(139.0, bounds.minLongitude, 1e-9)
        assertEquals(139.0, bounds.maxLongitude, 1e-9)
    }

    @Test
    fun compute_multiplePoints_findsMinMax() {
        val latitudes = doubleArrayOf(35.0, 34.0, 36.5, 35.2)
        val longitudes = doubleArrayOf(139.0, 138.5, 140.0, 139.9)

        val bounds = GeoBounds.compute(latitudes, longitudes)

        assertEquals(34.0, bounds.minLatitude, 1e-9)
        assertEquals(36.5, bounds.maxLatitude, 1e-9)
        assertEquals(138.5, bounds.minLongitude, 1e-9)
        assertEquals(140.0, bounds.maxLongitude, 1e-9)
    }

    @Test
    fun compute_negativeCoordinates_findsMinMax() {
        val latitudes = doubleArrayOf(-10.0, -20.0, 5.0)
        val longitudes = doubleArrayOf(-100.0, 50.0, -5.0)

        val bounds = GeoBounds.compute(latitudes, longitudes)

        assertEquals(-20.0, bounds.minLatitude, 1e-9)
        assertEquals(5.0, bounds.maxLatitude, 1e-9)
        assertEquals(-100.0, bounds.minLongitude, 1e-9)
        assertEquals(50.0, bounds.maxLongitude, 1e-9)
    }

    @Test
    fun compute_emptyArrays_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoBounds.compute(DoubleArray(0), DoubleArray(0))
        }
    }

    @Test
    fun compute_mismatchedArrayLengths_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoBounds.compute(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0))
        }
    }

    // ---- 範囲指定版（T-023） ----

    @Test
    fun compute_withRange_onlyConsidersPointsWithinRange() {
        val latitudes = doubleArrayOf(35.0, 34.0, 36.5, 35.2)
        val longitudes = doubleArrayOf(139.0, 138.5, 140.0, 139.9)

        // index 1..3(排他)のみ、つまりindex1のみを対象にする
        val bounds = GeoBounds.compute(latitudes, longitudes, fromIndex = 1, toIndex = 2)

        assertEquals(34.0, bounds.minLatitude, 1e-9)
        assertEquals(34.0, bounds.maxLatitude, 1e-9)
        assertEquals(138.5, bounds.minLongitude, 1e-9)
        assertEquals(138.5, bounds.maxLongitude, 1e-9)
    }

    @Test
    fun compute_withFullRange_matchesNoRangeOverload() {
        val latitudes = doubleArrayOf(35.0, 34.0, 36.5, 35.2)
        val longitudes = doubleArrayOf(139.0, 138.5, 140.0, 139.9)

        val full = GeoBounds.compute(latitudes, longitudes)
        val ranged = GeoBounds.compute(latitudes, longitudes, fromIndex = 0, toIndex = latitudes.size)

        assertEquals(full, ranged)
    }

    @Test
    fun compute_withEmptyRange_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoBounds.compute(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0), fromIndex = 1, toIndex = 1)
        }
    }
}
