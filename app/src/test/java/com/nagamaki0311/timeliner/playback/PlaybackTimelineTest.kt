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

    // ---- 関心度モデルの頭打ち・密度反映（T-019） ----

    @Test
    fun buildAuto_stationarySaturation_longStayGetsFarLessThanLinearShare() {
        // 短い滞在(30分)・長い滞在(8時間、30分のちょうど16倍)それぞれの後に、比較の基準となる
        // 大きな移動区間(約50km)を共通で挟む。基準区間の関心度はほぼ一定であるため、滞在時間の頭打ちが
        // 無ければ滞在区間の再生時間比率は滞在時間にほぼ比例し、長い滞在の比率は短い滞在の16倍近くまで
        // 増えるはずである。頭打ちがあれば大幅に抑えられるはずであることを確認する。
        fun stayPlaybackFraction(stayMillis: Long): Double {
            val timestamps = longArrayOf(0L, stayMillis, stayMillis + 1_000L)
            val latitudes = doubleArrayOf(35.0, 35.0, 35.45) // 約50km北
            val longitudes = doubleArrayOf(139.0, 139.0, 139.0)
            val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 60_000L)
            val stayPlaybackMillis = timeline.playbackMillisAtDataTime(stayMillis)
            return stayPlaybackMillis.toDouble() / timeline.totalPlaybackMillis().toDouble()
        }

        val shortStayMillis = 30L * 60L * 1000L // 30分
        val longStayMillis = 8L * 60L * 60L * 1000L // 8時間 = 30分の16倍

        val shortFraction = stayPlaybackFraction(shortStayMillis)
        val longFraction = stayPlaybackFraction(longStayMillis)
        val ratio = longFraction / shortFraction

        assertTrue(
            "頭打ちがあっても、滞在時間が長い方が再生時間比率は大きいはず: " +
                "shortFraction=$shortFraction, longFraction=$longFraction",
            longFraction > shortFraction
        )
        assertTrue(
            "滞在時間の頭打ちにより、8時間滞在の再生時間比率は30分滞在の単純16倍比例よりずっと小さいはず: " +
                "shortFraction=$shortFraction, longFraction=$longFraction, ratio=$ratio",
            ratio < 4.0
        )
    }

    @Test
    fun buildAuto_movementSaturation_singleLongSegmentGetsLessShareThanSplitEquivalent() {
        // 総距離500km・総所要時間500秒の移動を、1区間でまとめて行う場合と、25区間(20kmずつ)に
        // 分割して行う場合を比較する。比較の基準として、移動の後に共通の滞在区間(1時間)を挟む。
        // 頭打ちが無ければ移動区間全体の関心度への寄与は分割の有無によらず同じ(距離の合計・
        // 所要時間の合計は同一)はずだが、頭打ちにより1区間へ集約した場合の方が
        // 移動区間の再生時間シェアが小さくなる(1区間が過剰占有しない)ことを確認する。
        val totalDistanceDegreesLat = 4.5 // 緯度1度 ≈ 111km、4.5度 ≈ 約500km
        val totalMovementDurationMillis = 500_000L // 移動全体で500秒
        val referenceStayMillis = 3_600_000L // 共通の基準区間: 1時間滞在

        fun movementPlaybackFraction(segmentCount: Int): Double {
            val movementTimestamps = LongArray(segmentCount + 1) { i ->
                totalMovementDurationMillis * i / segmentCount
            }
            val movementLatitudes = DoubleArray(segmentCount + 1) { i ->
                35.0 + totalDistanceDegreesLat * i / segmentCount
            }
            val movementLongitudes = DoubleArray(segmentCount + 1) { 139.0 }

            val timestamps = movementTimestamps + (movementTimestamps.last() + referenceStayMillis)
            val latitudes = movementLatitudes + movementLatitudes.last()
            val longitudes = movementLongitudes + movementLongitudes.last()

            val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 60_000L)
            val movementEndPlaybackMillis = timeline.playbackMillisAtDataTime(movementTimestamps.last())
            return movementEndPlaybackMillis.toDouble() / timeline.totalPlaybackMillis().toDouble()
        }

        val singleSegmentFraction = movementPlaybackFraction(segmentCount = 1)
        val splitSegmentFraction = movementPlaybackFraction(segmentCount = 25)

        assertTrue(
            "移動距離の頭打ちにより、500kmを1区間にまとめた場合の再生時間シェアは、" +
                "25区間に分割した場合より明確に小さいはず: " +
                "singleSegmentFraction=$singleSegmentFraction, splitSegmentFraction=$splitSegmentFraction",
            splitSegmentFraction - singleSegmentFraction > 0.1
        )
    }

    @Test
    fun buildAuto_eventDensity_densityTermAloneIncreasesSectionShare() {
        // D-025決定1（Reviewer提案(c)案）: 同じ点列（実時間300秒・総距離3km・区間数100の密な区間、
        // 後ろに共通の滞在区間1時間を挟む）に対し、densityWeightMillis=0（密度項なし）と既定値
        // （密度項あり）の2条件でfractionを比較する。saturate関数由来の凹関数性（区間分割によって
        // 合計が増える効果）は同じ点列内で条件間に共通のためキャンセルされ、密度項単体の寄与のみが
        // 差として残る（旧テストは疎密で異なる点列を比較していたためsaturate由来の効果に支配され、
        // 密度項を無効化しても同じ結論になってしまっていた）。
        val pointCount = 101 // 区間数100の密な区間（区間数が多いほど密度項の相対寄与が大きくなる）
        val totalDurationMillis = 300_000L // 5分
        val totalDistanceDegreesLat = 0.027 // 緯度1度 ≈ 111km、0.027度 ≈ 約3km
        val referenceStayMillis = 3_600_000L

        val sectionTimestamps = LongArray(pointCount) { i -> totalDurationMillis * i / (pointCount - 1) }
        val sectionLatitudes = DoubleArray(pointCount) { i -> 35.0 + totalDistanceDegreesLat * i / (pointCount - 1) }
        val sectionLongitudes = DoubleArray(pointCount) { 139.0 }
        val timestamps = sectionTimestamps + (sectionTimestamps.last() + referenceStayMillis)
        val latitudes = sectionLatitudes + sectionLatitudes.last()
        val longitudes = sectionLongitudes + sectionLongitudes.last()

        fun sectionPlaybackFraction(densityWeightMillis: Double?): Double {
            val timeline = if (densityWeightMillis == null) {
                // 明示的に指定せず既定値（DEFAULT_DENSITY_WEIGHT_MILLIS）を使う
                PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 60_000L)
            } else {
                PlaybackTimeline.buildAuto(
                    timestamps, latitudes, longitudes,
                    targetDurationMillis = 60_000L,
                    densityWeightMillis = densityWeightMillis
                )
            }
            val sectionEndPlaybackMillis = timeline.playbackMillisAtDataTime(sectionTimestamps.last())
            return sectionEndPlaybackMillis.toDouble() / timeline.totalPlaybackMillis().toDouble()
        }

        val fractionWithoutDensity = sectionPlaybackFraction(densityWeightMillis = 0.0)
        val fractionWithDensity = sectionPlaybackFraction(densityWeightMillis = null)

        assertTrue(
            "同じ点列でも、密度項を有効にした方が区間の再生時間シェアは明確に大きいはず: " +
                "fractionWithoutDensity=$fractionWithoutDensity, fractionWithDensity=$fractionWithDensity",
            fractionWithDensity - fractionWithoutDensity > 0.0005
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
