package com.nagamaki0311.timeliner.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {

    // ---- 手動モード ----

    @Test
    fun buildManual_speedMultiplier60_scalesElapsedDataTimeDownByThatFactor() {
        // 60秒間隔の3点。x60（1分がデータ1秒相当）なら再生時刻は1000msずつ進むはず。
        val timestamps = longArrayOf(0L, 60_000L, 120_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 60.0)

        assertEquals(2000L, timeline.totalPlaybackMillis())
        assertEquals(0L, timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(60_000L, timeline.dataTimeAtPlaybackMillis(1000L))
        assertEquals(120_000L, timeline.dataTimeAtPlaybackMillis(2000L))
    }

    @Test
    fun buildManual_playbackMillisAtDataTime_isInverseOfDataTimeAtPlaybackMillis() {
        val timestamps = longArrayOf(1_000_000L, 1_060_000L, 1_180_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 60.0)

        assertEquals(0L, timeline.playbackMillisAtDataTime(1_000_000L))
        assertEquals(1000L, timeline.playbackMillisAtDataTime(1_060_000L))
        assertEquals(3000L, timeline.playbackMillisAtDataTime(1_180_000L))
    }

    // ---- 自動モード ----

    @Test
    fun buildAuto_totalPlaybackMillis_isNormalizedToTargetDuration() {
        // 長時間の滞在(dt大・距離0)の後、短時間で大きく移動(dt小・距離大)する3点。
        val timestamps = longArrayOf(0L, 3_600_000L, 3_601_000L)
        val latitudes = doubleArrayOf(35.0, 35.0, 35.1)
        val longitudes = doubleArrayOf(139.0, 139.0, 139.1)

        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        assertEquals(30_000L, timeline.totalPlaybackMillis())
        assertEquals(0L, timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(timestamps.last(), timeline.dataTimeAtPlaybackMillis(30_000L))
    }

    @Test
    fun buildAuto_stationaryPeriodIsCompressedRelativeToMovementPeriod() {
        // A→B: 1時間滞在（距離ほぼ0）。B→C: 1秒で1km移動。関心度への寄与はB→Cの方が
        // (距離の重み分)大きいため、データ経過時間に対する再生経過時間の比率はB→Cの方が大きいはず
        // （移動区間の方が「間延びしにくい」＝早送りされにくいことの確認）。
        val timestamps = longArrayOf(0L, 3_600_000L, 3_601_000L)
        val latitudes = doubleArrayOf(35.0, 35.0, 35.009) // 約1km北
        val longitudes = doubleArrayOf(139.0, 139.0, 139.0)

        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        val playbackAtB = timeline.playbackMillisAtDataTime(3_600_000L)
        val playbackAtC = timeline.playbackMillisAtDataTime(3_601_000L)
        val stationaryPlaybackMillis = playbackAtB // A(0)からの再生時間
        val movementPlaybackMillis = playbackAtC - playbackAtB

        val stationaryRatio = stationaryPlaybackMillis.toDouble() / 3_600_000.0
        val movementRatio = movementPlaybackMillis.toDouble() / 1_000.0
        assertTrue(
            "移動区間は滞在区間よりデータ経過時間あたりの再生時間比率が大きいはず: " +
                "stationaryRatio=$stationaryRatio, movementRatio=$movementRatio",
            movementRatio > stationaryRatio
        )
    }

    // ---- 単調性 ----

    @Test
    fun dataTimeAtPlaybackMillis_isMonotonicNonDecreasing_asPlaybackMillisIncreases() {
        val timestamps = longArrayOf(0L, 10_000L, 15_000L, 15_500L, 40_000L, 41_000L)
        val latitudes = doubleArrayOf(35.0, 35.0, 35.01, 35.011, 35.02, 35.025)
        val longitudes = doubleArrayOf(139.0, 139.001, 139.002, 139.0025, 139.01, 139.012)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        var previousDataTime = Long.MIN_VALUE
        val total = timeline.totalPlaybackMillis()
        var playbackMillis = 0L
        while (playbackMillis <= total) {
            val dataTime = timeline.dataTimeAtPlaybackMillis(playbackMillis)
            assertTrue(
                "再生時刻が進んでもデータ時刻が後退してはいけない: playback=$playbackMillis, data=$dataTime, previous=$previousDataTime",
                dataTime >= previousDataTime
            )
            previousDataTime = dataTime
            playbackMillis += 137L // 端数のあるステップで細かく検証する
        }
    }

    @Test
    fun dataTimeAtPlaybackMillis_outOfRangeValues_areClamped() {
        val timestamps = longArrayOf(1000L, 2000L, 3000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        assertEquals(timestamps.first(), timeline.dataTimeAtPlaybackMillis(-100L))
        assertEquals(timestamps.last(), timeline.dataTimeAtPlaybackMillis(timeline.totalPlaybackMillis() + 100L))
    }

    // ---- 端点 ----

    @Test
    fun endpoints_matchFirstAndLastDataTimestamps() {
        val timestamps = longArrayOf(5_000L, 20_000L, 65_000L, 66_000L)
        val latitudes = doubleArrayOf(35.0, 35.001, 35.002, 35.1)
        val longitudes = doubleArrayOf(139.0, 139.001, 139.002, 139.1)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 10_000L)

        assertEquals(timestamps.first(), timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(timestamps.last(), timeline.dataTimeAtPlaybackMillis(timeline.totalPlaybackMillis()))
    }

    // ---- 境界値: 少数点 ----

    @Test
    fun buildAuto_singlePoint_hasZeroDurationAndReturnsThatPointsTimestamp() {
        val timestamps = longArrayOf(42_000L)
        val latitudes = doubleArrayOf(35.0)
        val longitudes = doubleArrayOf(139.0)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        assertEquals(0L, timeline.totalPlaybackMillis())
        assertEquals(42_000L, timeline.dataTimeAtPlaybackMillis(0L))
    }

    @Test
    fun buildManual_singlePoint_hasZeroDurationAndReturnsThatPointsTimestamp() {
        val timestamps = longArrayOf(42_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 60.0)

        assertEquals(0L, timeline.totalPlaybackMillis())
        assertEquals(42_000L, timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(42_000L, timeline.playbackMillisAtDataTime(42_000L).let { timeline.dataTimeAtPlaybackMillis(it) })
    }

    @Test
    fun buildAuto_twoIdenticalPoints_degenerateInterestStillNormalizesToTargetDuration() {
        // 同一時刻・同一地点が2点続く（関心度の積分が0になる退化ケース）でも、
        // 総再生時間は目標値に正規化され、端点は正しく一致すること。
        val timestamps = longArrayOf(1000L, 1000L)
        val latitudes = doubleArrayOf(35.0, 35.0)
        val longitudes = doubleArrayOf(139.0, 139.0)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        assertEquals(30_000L, timeline.totalPlaybackMillis())
        assertEquals(1000L, timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(1000L, timeline.dataTimeAtPlaybackMillis(30_000L))
    }

    @Test
    fun buildAuto_twoPoints_endpointsMatchAndTotalIsTarget() {
        val timestamps = longArrayOf(0L, 5_000L)
        val latitudes = doubleArrayOf(35.0, 35.01)
        val longitudes = doubleArrayOf(139.0, 139.01)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 10_000L)

        assertEquals(10_000L, timeline.totalPlaybackMillis())
        assertEquals(0L, timeline.dataTimeAtPlaybackMillis(0L))
        assertEquals(5_000L, timeline.dataTimeAtPlaybackMillis(10_000L))
    }

    // ---- 不正入力 ----

    @Test(expected = IllegalArgumentException::class)
    fun buildManual_nonPositiveSpeedMultiplier_isRejected() {
        PlaybackTimeline.buildManual(longArrayOf(0L, 1000L), speedMultiplier = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAuto_nonPositiveTargetDuration_isRejected() {
        PlaybackTimeline.buildAuto(
            longArrayOf(0L, 1000L),
            doubleArrayOf(35.0, 35.0),
            doubleArrayOf(139.0, 139.0),
            targetDurationMillis = 0L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAuto_emptyInput_isRejected() {
        PlaybackTimeline.buildAuto(LongArray(0), DoubleArray(0), DoubleArray(0), targetDurationMillis = 10_000L)
    }
}
