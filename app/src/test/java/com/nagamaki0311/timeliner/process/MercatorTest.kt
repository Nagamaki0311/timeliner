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

    @Test
    fun distanceMeters_matchesHaversineOnlyAtEquator_confirmingLatitudeZeroIsTheCorrectMetersPerPixelReference() {
        // longitudeToX/latitudeToYが返す投影座標のスケールは緯度によらず一定（Webメルカトルの定義上、
        // 緯度0での実距離スケールと一致する）。RouteOverlayViewがMapLibreMap.projection
        // .getMetersPerPixelAtLatitude(0.0)を使うのは、この投影座標のスケールと一致させるため
        // （D-007決定1）。ここでは緯度0での投影距離/実距離(Haversine)の比が1に近いことを確認する
        // （厳密に1.0にならないのは、Web メルカトル(EPSG:3857)が赤道半径6378137mを使うのに対し、
        // Haversineは地球平均半径6371000mを使うため、0.1%強の定数由来の差が残るのは想定通り）。
        val equatorHaversine = Mercator.haversineDistanceMeters(0.0, 139.0, 0.0, 140.0)
        val equatorProjected = Mercator.distanceMeters(0.0, 139.0, 0.0, 140.0)
        assertEquals(1.0, equatorProjected / equatorHaversine, 0.002)

        // 東京(緯度約35.68度)では投影距離と実距離が明確に乖離する（このケースでは緯度0の値の
        // 約1.23倍、D-007の背景に記載の乖離率と一致）。緯度0固定を使う根拠の対比として確認する。
        val tokyoHaversine = Mercator.haversineDistanceMeters(35.681236, 139.0, 35.681236, 140.0)
        val tokyoProjected = Mercator.distanceMeters(35.681236, 139.0, 35.681236, 140.0)
        assertEquals(1.23, tokyoProjected / tokyoHaversine, 0.01)
    }

    @Test
    fun metersPerPixelAtZoom_zoomZero_matchesWorldCircumferenceOverOneTile() {
        // ズーム0は世界全体(赤道全周)がタイル1枚(WEB_MERCATOR_TILE_SIZE_PX)に収まる定義のため、
        // 1ピクセルあたりの距離は「赤道全周 / タイルサイズ」に一致するはず。
        val expected = (2.0 * Math.PI * 6378137.0) / Mercator.WEB_MERCATOR_TILE_SIZE_PX
        assertEquals(expected, Mercator.metersPerPixelAtZoom(0.0), 1e-6)
    }

    @Test
    fun metersPerPixelAtZoom_eachZoomLevelHalvesTheValue() {
        // ズームが1上がるとタイルが2倍細かくなり、1ピクセルあたりの距離は半分になる（Webメルカトルの定義）。
        val zoom5 = Mercator.metersPerPixelAtZoom(5.0)
        val zoom6 = Mercator.metersPerPixelAtZoom(6.0)
        assertEquals(zoom5 / 2.0, zoom6, 1e-6)
    }

    @Test
    fun metersPerPixelAtZoom_isPositiveAndDecreasingWithZoom() {
        val low = Mercator.metersPerPixelAtZoom(2.0)
        val high = Mercator.metersPerPixelAtZoom(15.0)
        assertTrue(low > 0.0)
        assertTrue(high > 0.0)
        assertTrue(high < low)
    }
}
