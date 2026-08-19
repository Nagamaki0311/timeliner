package com.nagamaki0311.timeliner.process

import com.nagamaki0311.timeliner.model.RawTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCleanerTest {

    private fun rawTrack(points: List<Triple<Double, Double, Long>>): RawTrack = RawTrack(
        latitudes = points.map { it.first }.toDoubleArray(),
        longitudes = points.map { it.second }.toDoubleArray(),
        timestampsMillis = points.map { it.third }.toLongArray(),
        segments = emptyList()
    )

    private fun series(points: List<Triple<Double, Double, Long>>): PointSeries = PointSeries(
        latitudes = points.map { it.first }.toDoubleArray(),
        longitudes = points.map { it.second }.toDoubleArray(),
        timestampsMillis = points.map { it.third }.toLongArray()
    )

    // ---- normalize ----

    @Test
    fun normalize_sortsPointsByTimestampAscending() {
        val track = rawTrack(
            listOf(
                Triple(35.2, 139.0, 3000L),
                Triple(35.1, 139.0, 1000L),
                Triple(35.3, 139.0, 2000L)
            )
        )

        val result = TrackCleaner.normalize(track)

        assertArrayEquals(longArrayOf(1000L, 2000L, 3000L), result.timestampsMillis)
        assertEquals(35.1, result.latitudes[0], 1e-9)
    }

    @Test
    fun normalize_dedupesSameTimestampPointsKeepingFirstOccurrence() {
        val track = rawTrack(
            listOf(
                Triple(35.1, 139.0, 1000L),
                Triple(35.9, 139.0, 1000L),
                Triple(35.2, 139.0, 2000L)
            )
        )

        val result = TrackCleaner.normalize(track)

        assertEquals(2, result.size)
        assertEquals(35.1, result.latitudes[0], 1e-9)
    }

    @Test
    fun normalize_discardsOutOfRangeAndOriginPoints() {
        val track = rawTrack(
            listOf(
                Triple(35.0, 139.0, 1000L),
                Triple(91.0, 139.0, 2000L), // 緯度範囲外
                Triple(35.0, 181.0, 3000L), // 経度範囲外
                Triple(0.0, 0.0, 4000L), // 原点
                Triple(35.1, 139.1, 5000L)
            )
        )

        val result = TrackCleaner.normalize(track)

        assertEquals(2, result.size)
        assertEquals(1000L, result.timestampsMillis[0])
        assertEquals(5000L, result.timestampsMillis[1])
    }

    // ---- removeSpeedSpikes ----

    @Test
    fun removeSpeedSpikes_removesSinglePointThatFliesOutAndReturns() {
        val points = series(
            listOf(
                Triple(35.0000, 139.0000, 0L),
                Triple(35.0050, 139.0000, 60_000L), // ~555m/60s(約33km/h)、正常
                Triple(36.0000, 140.0000, 61_000L), // 1秒で遠方へ飛ぶスパイク
                Triple(35.0100, 139.0000, 120_000L), // p1から見て正常な位置に戻る
                Triple(35.0150, 139.0000, 180_000L)
            )
        )

        val result = TrackCleaner.removeSpeedSpikes(points, maxSpeedKmh = 300.0)

        assertEquals(4, result.size)
        assertArrayEquals(longArrayOf(0L, 60_000L, 120_000L, 180_000L), result.timestampsMillis)
    }

    @Test
    fun removeSpeedSpikes_keepsSustainedHighSpeedMovementLikeAirplane() {
        // 経度5度(赤道付近で約555km)を30分ごとに移動 = 約1110km/hの一定した高速移動。
        // 前後・スキップいずれの速度も閾値を超えて整合するため、スパイクとして除去してはいけない。
        val points = series(
            listOf(
                Triple(0.0, 0.0, 0L),
                Triple(0.0, 5.0, 1_800_000L),
                Triple(0.0, 10.0, 3_600_000L),
                Triple(0.0, 15.0, 5_400_000L),
                Triple(0.0, 20.0, 7_200_000L)
            )
        )

        val result = TrackCleaner.removeSpeedSpikes(points, maxSpeedKmh = 300.0)

        assertEquals(5, result.size)
    }

    // ---- suppressStationaryJitter ----

    @Test
    fun suppressStationaryJitter_dropsPointsWithinDistanceAndTimeThresholds() {
        val points = series(
            listOf(
                Triple(35.0, 139.0, 0L),
                Triple(35.00003, 139.0, 10_000L), // 約3m、10秒後 -> ジッタ
                Triple(35.00004, 139.00002, 20_000L), // 依然として直前採用点(p0)から近距離・短時間 -> ジッタ
                Triple(35.01, 139.0, 30_000L) // 直前採用点から約1.1km -> 実移動
            )
        )

        val result = TrackCleaner.suppressStationaryJitter(points, distanceMeters = 15.0, timeMillis = 60_000L)

        assertEquals(2, result.size)
        assertArrayEquals(longArrayOf(0L, 30_000L), result.timestampsMillis)
    }

    @Test
    fun suppressStationaryJitter_keepsCloseButFarApartInTimePoint() {
        val points = series(
            listOf(
                Triple(35.0, 139.0, 0L),
                Triple(35.00001, 139.0, 70_000L) // 距離は近いが70秒経過(閾値60秒超) -> 捨てない
            )
        )

        val result = TrackCleaner.suppressStationaryJitter(points, distanceMeters = 15.0, timeMillis = 60_000L)

        assertEquals(2, result.size)
    }

    @Test
    fun suppressStationaryJitter_keepsFarButQuickPoint() {
        val points = series(
            listOf(
                Triple(35.0, 139.0, 0L),
                Triple(35.01, 139.0, 5_000L) // 約1.1km離れており5秒後でも距離基準で実移動として残す
            )
        )

        val result = TrackCleaner.suppressStationaryJitter(points, distanceMeters = 15.0, timeMillis = 60_000L)

        assertEquals(2, result.size)
    }

    @Test
    fun suppressStationaryJitter_thresholdIsConsistentAtHighLatitude() {
        // 東西方向に「実距離がほぼ同じ(約11m)」だけ動いた点を、赤道付近と北緯60度で用意する。
        // 北緯60度では同じ実距離でも経度差は約2倍(1/cos(60°))必要になる。Haversine距離であれば、
        // 経度差が違っても実距離が同じなら停留ジッタとして抑制されるかどうかの判定が緯度に依らず一定になる
        // （メルカトル投影距離のままだと高緯度側の経度差が過大評価され、判定基準がずれてしまう）。
        val lowLatitudePoints = series(
            listOf(
                Triple(0.0, 139.0, 0L),
                Triple(0.0, 139.0001, 10_000L) // 実距離 約11.1m
            )
        )
        val highLatitudePoints = series(
            listOf(
                Triple(60.0, 139.0, 0L),
                Triple(60.0, 139.0002, 10_000L) // 経度差は2倍だが実距離は約11.1mとほぼ同じ
            )
        )

        val lowLatitudeResult =
            TrackCleaner.suppressStationaryJitter(lowLatitudePoints, distanceMeters = 15.0, timeMillis = 60_000L)
        val highLatitudeResult =
            TrackCleaner.suppressStationaryJitter(highLatitudePoints, distanceMeters = 15.0, timeMillis = 60_000L)

        assertEquals(1, lowLatitudeResult.size)
        assertEquals(1, highLatitudeResult.size)
    }

    // ---- computeSegmentStartIndices ----

    @Test
    fun computeSegmentStartIndices_splitsAtLargeTimeGap() {
        val sixHoursMillis = 6L * 60 * 60 * 1000
        val points = series(
            listOf(
                Triple(35.0, 139.0, 0L),
                Triple(35.1, 139.0, 1000L),
                Triple(35.2, 139.0, 1000L + sixHoursMillis + 1L), // 6時間超の欠損
                Triple(35.3, 139.0, 1000L + sixHoursMillis + 2000L)
            )
        )

        val result = TrackCleaner.computeSegmentStartIndices(points, gapMillis = sixHoursMillis)

        assertArrayEquals(intArrayOf(0, 2), result)
    }

    @Test
    fun computeSegmentStartIndices_noSplitWhenGapsAreSmall() {
        val points = series(
            listOf(
                Triple(35.0, 139.0, 0L),
                Triple(35.1, 139.0, 1000L),
                Triple(35.2, 139.0, 2000L)
            )
        )

        val result = TrackCleaner.computeSegmentStartIndices(points, gapMillis = 6L * 60 * 60 * 1000)

        assertArrayEquals(intArrayOf(0), result)
    }

    @Test
    fun computeSegmentStartIndices_emptySeries_returnsEmptyArray() {
        val result = TrackCleaner.computeSegmentStartIndices(series(emptyList()), gapMillis = 1000L)

        assertEquals(0, result.size)
    }

    // ---- clean(): パイプライン全体の統合確認 ----

    @Test
    fun clean_fullPipeline_normalizesDespikesDejittersAndSegments() {
        val sixHoursMillis = 6L * 60 * 60 * 1000
        val track = rawTrack(
            listOf(
                // 意図的に時系列を乱して投入（このモジュール単体でも昇順を保証することの確認）。
                Triple(35.3, 139.0, 2000L + sixHoursMillis + 1000L),
                Triple(0.0, 0.0, 500L), // 原点、破棄される
                Triple(35.0, 139.0, 0L),
                Triple(35.0000, 139.0, 1000L) // 直前採用点から近距離・短時間 -> ジッタとして破棄
            )
        )

        val result = TrackCleaner.clean(track)

        assertEquals(2, result.pointCount)
        assertEquals(0L, result.timestampsMillis[0])
        assertEquals(2000L + sixHoursMillis + 1000L, result.timestampsMillis[1])
        assertArrayEquals(intArrayOf(0, 1), result.segmentStartIndices)
        assertEquals(2, result.segmentCount)
        assertTrue(result.segmentRange(0) == 0 until 1)
        assertTrue(result.segmentRange(1) == 1 until 2)
    }
}
