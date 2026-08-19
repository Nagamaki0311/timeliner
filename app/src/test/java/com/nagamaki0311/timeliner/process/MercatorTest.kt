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

    @Test
    fun haversineDistanceMeters_samePoint_isZero() {
        assertEquals(0.0, Mercator.haversineDistanceMeters(35.0, 139.0, 35.0, 139.0), 1e-9)
    }

    @Test
    fun haversineDistanceMeters_isSymmetric() {
        val forward = Mercator.haversineDistanceMeters(35.0, 139.0, 36.0, 140.0)
        val backward = Mercator.haversineDistanceMeters(36.0, 140.0, 35.0, 139.0)
        assertEquals(forward, backward, 1e-9)
    }

    @Test
    fun haversineDistanceMeters_oneDegreeLongitudeAtEquator_matchesKnownRealWorldDistance() {
        // 赤道上、地球平均半径(6371km)の球面近似での経度1度あたりの距離は約111.19km。
        val distance = Mercator.haversineDistanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111194.93, distance, 5.0)
    }

    @Test
    fun haversineDistanceMeters_tokyoStationToShinjukuStation_matchesKnownRealWorldDistance() {
        // 東京駅↔新宿駅（実距離は約6083m）。赤道以外での精度確認。
        val distance = Mercator.haversineDistanceMeters(35.681236, 139.767125, 35.689607, 139.700571)
        assertEquals(6083.0, distance, 50.0)
    }

    @Test
    fun haversineDistanceMeters_isSmallerThanProjectedDistance_awayFromEquator() {
        // 赤道以外ではメルカトル投影距離(distanceMeters)は実距離より過大に出る。
        // 東京駅↔新宿駅は緯度約35.68度のため、Haversine（実距離）の方が小さいはず。
        val haversine = Mercator.haversineDistanceMeters(35.681236, 139.767125, 35.689607, 139.700571)
        val projected = Mercator.distanceMeters(35.681236, 139.767125, 35.689607, 139.700571)
        assertTrue(haversine < projected)
    }
}
