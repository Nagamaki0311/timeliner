package com.nagamaki0311.timeliner.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MercatorTest {

    @Test
    fun longitudeToX_zero_isZero() {
        assertEquals(0.0, Mercator.longitudeToX(0.0), 1e-9)
    }

    @Test
    fun latitudeToY_zero_isZero() {
        assertEquals(0.0, Mercator.latitudeToY(0.0), 1e-9)
    }

    @Test
    fun distanceMeters_samePoint_isZero() {
        assertEquals(0.0, Mercator.distanceMeters(35.0, 139.0, 35.0, 139.0), 1e-9)
    }

    @Test
    fun distanceMeters_isSymmetric() {
        val forward = Mercator.distanceMeters(35.0, 139.0, 36.0, 140.0)
        val backward = Mercator.distanceMeters(36.0, 140.0, 35.0, 139.0)
        assertEquals(forward, backward, 1e-9)
    }

    @Test
    fun distanceMeters_oneDegreeLongitudeAtEquator_matchesKnownRealWorldDistance() {
        // 赤道上での経度1度あたりの実距離は約111.32km（緯度0ではメルカトルの縮尺歪みがない）。
        val distance = Mercator.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111319.49, distance, 5.0)
    }

    @Test
    fun latitudeToY_nearPole_doesNotOverflowAndStaysFinite() {
        val y = Mercator.latitudeToY(90.0)
        assertTrue(y.isFinite())
    }

    @Test
    fun latitudeToY_isMonotonicallyIncreasingWithLatitude() {
        val ySouth = Mercator.latitudeToY(-30.0)
        val yEquator = Mercator.latitudeToY(0.0)
        val yNorth = Mercator.latitudeToY(30.0)
        assertTrue(ySouth < yEquator)
        assertTrue(yEquator < yNorth)
    }
}
