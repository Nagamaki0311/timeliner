package com.nagamaki0311.timeliner.camera

import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDirectorTest {

    // ---- 極端なケース ----

    @Test
    fun computeKeyframes_emptyRoute_returnsEmptyList() {
        // timeline自体は（PlaybackTimelineの制約上）1点以上必要なためダミーで構築するが、
        // computeKeyframesへ渡すルート点列（timestamps/lat/lon）自体は空という状況を再現する。
        val dummyTimeline = PlaybackTimeline.buildManual(longArrayOf(0L), speedMultiplier = 1.0)

        val keyframes = CameraDirector.computeKeyframes(
            LongArray(0), DoubleArray(0), DoubleArray(0), dummyTimeline
        )

        assertTrue(keyframes.isEmpty())
    }

    @Test
    fun computeKeyframes_singlePoint_returnsOneKeyframeAtStartWithMaxZoom() {
        val timestamps = longArrayOf(1_000L)
        val latitudes = doubleArrayOf(35.0)
        val longitudes = doubleArrayOf(139.0)
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        val keyframes = CameraDirector.computeKeyframes(timestamps, latitudes, longitudes, timeline)

        assertEquals(1, keyframes.size)
        val only = keyframes[0]
        assertEquals(0L, only.playbackMillis)
        assertEquals(35.0, only.centerLatitude, 1e-9)
        assertEquals(139.0, only.centerLongitude, 1e-9)
        assertEquals(CameraZoom.MAX_ZOOM, only.zoom, 1e-9)
    }

    @Test
    fun computeKeyframes_allPointsAtSameLocation_doesNotCrashAndStaysAtMaxZoom() {
        val timestamps = longArrayOf(0L, 10_000L, 20_000L, 30_000L, 40_000L)
        val latitudes = DoubleArray(5) { 35.0 }
        val longitudes = DoubleArray(5) { 139.0 }
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 30_000L)

        val keyframes = CameraDirector.computeKeyframes(timestamps, latitudes, longitudes, timeline)

        assertTrue(keyframes.isNotEmpty())
        keyframes.forEach { keyframe ->
            assertEquals(35.0, keyframe.centerLatitude, 1e-9)
            assertEquals(139.0, keyframe.centerLongitude, 1e-9)
            assertEquals(CameraZoom.MAX_ZOOM, keyframe.zoom, 1e-9)
        }
    }

    @Test
    fun computeKeyframes_veryShortTargetDuration_doesNotCrash() {
        val timestamps = longArrayOf(0L, 1_000L, 2_000L)
        val latitudes = doubleArrayOf(35.0, 35.001, 35.002)
        val longitudes = doubleArrayOf(139.0, 139.001, 139.002)
        // 1ミリ秒という極端に短い目標再生時間。
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 1L)

        val keyframes = CameraDirector.computeKeyframes(timestamps, latitudes, longitudes, timeline)

        assertTrue(keyframes.isNotEmpty())
        keyframes.forEach { keyframe ->
            assertTrue(keyframe.playbackMillis in 0L..1L)
        }
    }

    // ---- 引数検証 ----

    @Test
    fun computeKeyframes_mismatchedArrayLengths_throws() {
        val timestamps = longArrayOf(0L, 1_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        assertThrows(IllegalArgumentException::class.java) {
            CameraDirector.computeKeyframes(timestamps, doubleArrayOf(35.0), doubleArrayOf(139.0, 139.1), timeline)
        }
    }

    @Test
    fun computeKeyframes_nonPositiveKeyframeInterval_throws() {
        val timestamps = longArrayOf(0L, 1_000L)
        val latitudes = doubleArrayOf(35.0, 35.1)
        val longitudes = doubleArrayOf(139.0, 139.1)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        assertThrows(IllegalArgumentException::class.java) {
            CameraDirector.computeKeyframes(
                timestamps, latitudes, longitudes, timeline, keyframeIntervalMillis = 0L
            )
        }
    }

    @Test
    fun computeKeyframes_nonPositiveViewport_throws() {
        val timestamps = longArrayOf(0L, 1_000L)
        val latitudes = doubleArrayOf(35.0, 35.1)
        val longitudes = doubleArrayOf(139.0, 139.1)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        assertThrows(IllegalArgumentException::class.java) {
            CameraDirector.computeKeyframes(
                timestamps, latitudes, longitudes, timeline, viewportWidthPx = 0
            )
        }
    }

    // ---- キーフレームの並び・再生時刻範囲 ----

    @Test
    fun computeKeyframes_areOrderedAscendingAndWithinPlaybackRange() {
        val (timestamps, latitudes, longitudes) = buildMultiDaySyntheticRoute()
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 60_000L)

        val keyframes = CameraDirector.computeKeyframes(timestamps, latitudes, longitudes, timeline)

        assertTrue(keyframes.isNotEmpty())
        assertEquals(0L, keyframes.first().playbackMillis)
        assertEquals(timeline.totalPlaybackMillis(), keyframes.last().playbackMillis)
        for (i in 1 until keyframes.size) {
            assertTrue(
                "playbackMillisは狭義単調増加である必要があります: index=$i",
                keyframes[i].playbackMillis > keyframes[i - 1].playbackMillis
            )
        }
        keyframes.forEach { keyframe ->
            assertTrue(keyframe.playbackMillis in 0L..timeline.totalPlaybackMillis())
        }
    }

    @Test
    fun computeKeyframes_intervalBetweenKeyframesNeverExceedsRequestedInterval() {
        val (timestamps, latitudes, longitudes) = buildMultiDaySyntheticRoute()
        val timeline = PlaybackTimeline.buildAuto(timestamps, latitudes, longitudes, targetDurationMillis = 60_000L)
        val requestedIntervalMillis = 5_000L

        val keyframes = CameraDirector.computeKeyframes(
            timestamps, latitudes, longitudes, timeline, keyframeIntervalMillis = requestedIntervalMillis
        )

        // 「長時間の静止画面凝視を避ける」要件: 隣接キーフレーム間の再生時刻の差は、
        // 指定したキーフレーム間隔を上回らないこと（具体的な上限値でのアサーション）。
        for (i in 1 until keyframes.size) {
            val gap = keyframes[i].playbackMillis - keyframes[i - 1].playbackMillis
            assertTrue("キーフレーム間隔が上限を超えています: gap=$gap at index=$i", gap <= requestedIntervalMillis)
        }
    }

    // ---- 広い範囲(都市間移動)と狭い範囲(滞在)でズームレベルが異なること ----

    @Test
    fun computeKeyframes_wideRangeKeyframeHasLowerZoomThanNarrowRangeKeyframe() {
        // 東京駅周辺での小さな滞在クラスタ(indices 0..4) → 約400km離れた大阪への移動 →
        // 大阪周辺での小さな滞在クラスタ(indices 5..8)、という合成ルート。
        val timestamps = longArrayOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 620_000L, 625_000L, 630_000L, 635_000L)
        val latitudes = doubleArrayOf(35.6812, 35.6813, 35.6811, 35.6812, 35.6812, 34.6937, 34.6938, 34.6936, 34.6937)
        val longitudes =
            doubleArrayOf(139.7671, 139.7670, 139.7672, 139.7671, 139.7671, 135.5023, 135.5024, 135.5022, 135.5023)
        // 実データ時間に対して単純に線形な写像（決定的なテストにするためbuildManualを使う）。
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 500.0)
        assertEquals(1270L, timeline.totalPlaybackMillis())

        val keyframes = CameraDirector.computeKeyframes(
            timestamps, latitudes, longitudes, timeline, keyframeIntervalMillis = 200L
        )

        // index0: window=[0,100]ms(再生) → 東京クラスタのみを含む狭い範囲 → 高いズーム。
        val narrowRangeKeyframe = keyframes[0]
        // index1: window=[100,300]ms(再生) → 東京出発直前点と大阪到着点の間の「移動中」窓
        // （実点が存在しない空白区間）→ 前後の点にブラケットされ移動全体を含む広い範囲 → 低いズーム。
        val wideRangeKeyframe = keyframes[1]

        assertEquals(0L, narrowRangeKeyframe.playbackMillis)
        assertEquals(200L, wideRangeKeyframe.playbackMillis)

        // 狭い範囲(東京クラスタ、直径数十m)は最大ズームに張り付く。
        assertEquals(CameraZoom.MAX_ZOOM, narrowRangeKeyframe.zoom, 1e-6)
        // 広い範囲(東京-大阪間、約400km)は最大ズームより明確に低い、具体的な上限値以下のズームになる。
        assertTrue("wideRangeKeyframe.zoom=${wideRangeKeyframe.zoom}", wideRangeKeyframe.zoom <= 10.0)
        // 両者の差が誤差ではなく明確な差であることを具体的な数値で確認する。
        assertTrue(wideRangeKeyframe.zoom < narrowRangeKeyframe.zoom - 3.0)
    }

    private fun buildMultiDaySyntheticRoute(): Triple<LongArray, DoubleArray, DoubleArray> {
        // 3日分、1日あたり滞在(密なクラスタ)＋短い移動、という典型的なパターンを模した合成データ。
        val timestamps = mutableListOf<Long>()
        val latitudes = mutableListOf<Double>()
        val longitudes = mutableListOf<Double>()
        var t = 0L
        var lat = 35.0
        var lon = 139.0
        repeat(3) { day ->
            repeat(20) { i ->
                timestamps.add(t)
                latitudes.add(lat + i * 0.00001)
                longitudes.add(lon + i * 0.00001)
                t += 60_000L
            }
            lat += 0.5
            lon += 0.5
            t += 3_600_000L
        }
        return Triple(timestamps.toLongArray(), latitudes.toDoubleArray(), longitudes.toDoubleArray())
    }
}
