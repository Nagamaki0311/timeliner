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

    // ---- 隣接キーフレーム窓の境界（D-029、T-023bで修正した境界二重カウント） ----

    @Test
    fun resolveWindowIndexRange_nonLastWindow_excludesPointExactlyAtWindowEnd() {
        // windowEnd自身に一致するデータ時刻の点は、次の窓（境界を共有する側）だけに属するべきで、
        // この窓（非最終窓）のtoIndexには含まれない（半開区間）ことを確認する。
        val timestamps = longArrayOf(0L, 500L, 1_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val (fromIndex, toIndex) = CameraDirector.resolveWindowIndexRange(
            windowStart = 0L,
            windowEnd = 500L,
            isLastWindow = false,
            timestampsMillis = timestamps,
            timeline = timeline
        )

        // index0(t=0)のみを含み、windowEndちょうど(t=500, index1)は含まない。
        assertEquals(0, fromIndex)
        assertEquals(1, toIndex)
    }

    @Test
    fun resolveWindowIndexRange_lastWindow_includesPointExactlyAtWindowEnd() {
        // 最後の窓のみ、windowEnd自身（再生時間全体の最終点に対応するデータ時刻）を含む閉区間として扱う。
        val timestamps = longArrayOf(0L, 500L, 1_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val (fromIndex, toIndex) = CameraDirector.resolveWindowIndexRange(
            windowStart = 500L,
            windowEnd = 1_000L,
            isLastWindow = true,
            timestampsMillis = timestamps,
            timeline = timeline
        )

        // index1(t=500)とindex2(t=1000、windowEndちょうど)の両方を含む。
        assertEquals(1, fromIndex)
        assertEquals(3, toIndex)
    }

    @Test
    fun resolveWindowIndexRange_adjacentWindows_partitionAllPointsWithoutOverlapOrGap() {
        // buildManual(speedMultiplier=1.0)は再生時刻=データ時刻-originの単純な線形写像になるため、
        // 窓境界を任意のデータ時刻に厳密に一致させられる。t=0,100,...,1000の11点に対し、境界がちょうど
        // t=300とt=700に一致する3つの隣接窓[0,300),[300,700),[700,1000]を直接指定して検証する。
        val timestamps = LongArray(11) { it * 100L } // 0,100,...,1000
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val windowBoundaries = listOf(0L, 300L, 700L, 1_000L) // 3つの窓: [0,300),[300,700),[700,1000]
        val windowCount = windowBoundaries.size - 1
        val ranges = (0 until windowCount).map { i ->
            CameraDirector.resolveWindowIndexRange(
                windowStart = windowBoundaries[i],
                windowEnd = windowBoundaries[i + 1],
                isLastWindow = i == windowCount - 1,
                timestampsMillis = timestamps,
                timeline = timeline
            )
        }

        // 取りこぼし無し: 最初の窓はindex0から始まり、最後の窓はtimestamps.size(=11)で終わる。
        assertEquals(0, ranges.first().first)
        assertEquals(timestamps.size, ranges.last().second)
        // 隙間・重複無し: 隣接する窓のtoIndexと次の窓のfromIndexが厳密に一致する。
        for (i in 1 until ranges.size) {
            assertEquals(
                "窓${i - 1}のtoIndexと窓${i}のfromIndexが一致しません(重複または隙間がある可能性)",
                ranges[i - 1].second, ranges[i].first
            )
        }
        // 全ての点(0..10)がちょうど1つの窓に属することを、区間の和が[0,11)を隙間・重複なく覆うことで確認する。
        val coveredIndices = ranges.flatMap { range -> (range.first until range.second).toList() }
        assertEquals((0 until timestamps.size).toList(), coveredIndices)
    }

    // ---- 退化ケース（窓内に点が1つも無い）のブラケット処理（D-030、T-023c） ----

    @Test
    fun resolveWindowIndexRange_degenerateWindow_returnsEmptyRangeThatDoesNotOverlapNeighbors() {
        // Reviewerが実際に再現したシナリオ（docs/decisions.md D-030）: 2点のみのルートを
        // keyframeIntervalMillis=500で3窓に分割すると、中央の窓(w1)は窓内に点が1つも無い退化ケースになる。
        val timestamps = longArrayOf(0L, 1_000L)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val w0 = CameraDirector.resolveWindowIndexRange(
            windowStart = 0L, windowEnd = 250L, isLastWindow = false,
            timestampsMillis = timestamps, timeline = timeline
        )
        val w1 = CameraDirector.resolveWindowIndexRange(
            windowStart = 250L, windowEnd = 750L, isLastWindow = false,
            timestampsMillis = timestamps, timeline = timeline
        )
        val w2 = CameraDirector.resolveWindowIndexRange(
            windowStart = 750L, windowEnd = 1_000L, isLastWindow = true,
            timestampsMillis = timestamps, timeline = timeline
        )

        assertEquals(0 to 1, w0) // index0のみ（所有権あり）
        assertEquals(1 to 1, w1) // 退化: 空範囲（所有権を主張しない）
        assertEquals(1 to 2, w2) // index1のみ（所有権あり）

        // 重複無し: 隣接する窓のtoIndexと次の窓のfromIndexが厳密に一致する。
        assertEquals(
            "w0のtoIndexとw1のfromIndexが一致しません(重複または隙間がある可能性)",
            w0.second, w1.first
        )
        assertEquals(
            "w1のtoIndexとw2のfromIndexが一致しません(重複または隙間がある可能性)",
            w1.second, w2.first
        )
        // 取りこぼし無し: 全ての点(index0,1)が、重複・隙間なくちょうど1つの窓の所有範囲に属する。
        val coveredIndices = listOf(w0, w1, w2).flatMap { (from, to) -> (from until to).toList() }
        assertEquals(listOf(0, 1), coveredIndices)
    }

    @Test
    fun computeKeyframes_degenerateMiddleWindow_showsBothEndpointsWithoutOwnershipOverlap() {
        // D-030の再現シナリオをcomputeKeyframes全体で検証する: 東京(index0)→大阪(index1)の2点のみ、
        // keyframeIntervalMillis=500で3窓（先頭・末尾は単一点、中央窓は退化）に分割される。
        val timestamps = longArrayOf(0L, 1_000L)
        val latitudes = doubleArrayOf(35.6812, 34.6937) // 東京, 大阪
        val longitudes = doubleArrayOf(139.7671, 135.5023)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val keyframes = CameraDirector.computeKeyframes(
            timestamps, latitudes, longitudes, timeline, keyframeIntervalMillis = 500L
        )

        assertEquals(3, keyframes.size)
        // 先頭(窓0)は東京のみを排他的に所有する非退化窓 → 単一点のため最大ズーム。
        assertEquals(139.7671, keyframes[0].centerLongitude, 1e-9)
        assertEquals(CameraZoom.MAX_ZOOM, keyframes[0].zoom, 1e-9)
        // 末尾(窓2)は大阪のみを排他的に所有する非退化窓（isLastWindowの閉区間） → 単一点のため最大ズーム。
        assertEquals(135.5023, keyframes[2].centerLongitude, 1e-9)
        assertEquals(CameraZoom.MAX_ZOOM, keyframes[2].zoom, 1e-9)
        // 中央(窓1、退化)はresolveWindowIndexRangeの所有範囲としては空だが、bboxは東京・大阪の座標を
        // 読むだけでブラケットするため、中心経度は両者の間になり、ズームは明確に広い範囲になる。
        val middle = keyframes[1]
        assertTrue(
            "中央キーフレームの中心経度が東京・大阪の間にありません: ${middle.centerLongitude}",
            middle.centerLongitude in 135.5023..139.7671
        )
        assertTrue(
            "中央キーフレームのズームが十分に低くありません(広い範囲であるはず): ${middle.zoom}",
            middle.zoom < CameraZoom.MAX_ZOOM - 3.0
        )
    }

    @Test
    fun computeKeyframes_pointExactlyOnSharedWindowBoundary_isNotDuplicatedAcrossKeyframes() {
        // 東京(index0,1)→境界ちょうどの中間点(index2)→大阪(index3,4)という配置。
        // speedMultiplier=1.0(再生時刻=データ時刻)・keyframeIntervalMillis=500Lにより、
        // keyframeTimes=[0,500,1000]、窓境界(中点)は250msと750msになり、750msに対応するデータ時刻が
        // ちょうどindex2(t=500)に一致する（境界共有ケースを再現する）。
        val timestamps = longArrayOf(0L, 100L, 500L, 900L, 1_000L)
        val latitudes = doubleArrayOf(35.6812, 35.6812, 35.0, 34.6937, 34.6937)
        val longitudes = doubleArrayOf(139.7671, 139.7671, 137.0, 135.5023, 135.5023)
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        val keyframes = CameraDirector.computeKeyframes(
            timestamps, latitudes, longitudes, timeline, keyframeIntervalMillis = 500L
        )

        assertEquals(3, keyframes.size)
        // 先頭キーフレーム(窓=[0,250))は東京クラスタ(index0,1)のみを含み、境界点(index2)を含まない
        // → 中心経度は東京付近(139.7671)のまま、大阪方向(135台)に引っ張られない。
        assertEquals(139.7671, keyframes[0].centerLongitude, 1e-9)
        // 2番目のキーフレーム(窓=[250,750))は境界点(index2)のみを含む(重複していれば東京/大阪の座標も
        // 混ざって中心・ズームがずれる)ので、中心が境界点の座標と厳密に一致し、1点のみのため最大ズームになる。
        assertEquals(35.0, keyframes[1].centerLatitude, 1e-9)
        assertEquals(137.0, keyframes[1].centerLongitude, 1e-9)
        assertEquals(CameraZoom.MAX_ZOOM, keyframes[1].zoom, 1e-9)
    }

    // ---- currentKeyframeIndex（T-024、画面再生でのカメラ追従が「現在のキーフレーム」を特定するロジック） ----

    @Test
    fun currentKeyframeIndex_emptyKeyframes_returnsMinusOne() {
        assertEquals(-1, CameraDirector.currentKeyframeIndex(emptyList(), 1_000L))
    }

    @Test
    fun currentKeyframeIndex_beforeFirstKeyframe_returnsFirstIndex() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(1_000L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(2_000L, 35.1, 139.1, 10.0)
        )

        assertEquals(0, CameraDirector.currentKeyframeIndex(keyframes, 0L))
    }

    @Test
    fun currentKeyframeIndex_afterLastKeyframe_returnsLastIndex() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        assertEquals(1, CameraDirector.currentKeyframeIndex(keyframes, 5_000L))
    }

    @Test
    fun currentKeyframeIndex_exactMatch_returnsThatIndex() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0),
            CameraDirector.CameraKeyframe(2_000L, 35.2, 139.2, 10.0)
        )

        assertEquals(1, CameraDirector.currentKeyframeIndex(keyframes, 1_000L))
    }

    @Test
    fun currentKeyframeIndex_betweenTwoKeyframes_returnsNearestOne() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        // 中点(500)より前は前のキーフレーム、後は次のキーフレームに近いと判定される。
        assertEquals(0, CameraDirector.currentKeyframeIndex(keyframes, 499L))
        assertEquals(1, CameraDirector.currentKeyframeIndex(keyframes, 501L))
    }

    @Test
    fun currentKeyframeIndex_exactlyAtMidpoint_prefersLaterKeyframe() {
        // 境界ミリ秒ちょうど(playbackMillis == midpoint)は、computeKeyframes内部の窓所有権
        // （resolveWindowIndexRangeが共有境界を後の窓に割り当てる規約）と一致させるため、
        // 後のキーフレームを優先する（docs/decisions.md D-031）。
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        assertEquals(1, CameraDirector.currentKeyframeIndex(keyframes, 500L))
    }

    // ---- currentKeyframeIndexとcomputeKeyframesの窓所有権の等価性（D-031） ----

    @Test
    fun currentKeyframeIndex_matchesResolveWindowIndexRangeOwnership_acrossVariousKeyframeLayouts() {
        // currentKeyframeIndexが返すキーフレームは、その再生時刻(playbackMillis)に対応するデータ時刻を
        // resolveWindowIndexRangeが「所有」する窓のキーフレームと厳密に一致するべきである(D-031)。
        // buildManual(speedMultiplier=1.0)は再生時刻=データ時刻-originの単純な線形写像になるため、
        // 窓境界(中点)を任意のミリ秒に厳密に一致させて検証できる。
        val timestamps = LongArray(21) { it * 100L } // 0,100,...,2000
        val timeline = PlaybackTimeline.buildManual(timestamps, speedMultiplier = 1.0)

        // 均等間隔・不均等間隔の両方を含む複数のキーフレーム時刻レイアウトで検証する。
        val keyframeTimesLayouts = listOf(
            listOf(0L, 400L, 800L, 1_200L, 1_600L, 2_000L), // 均等間隔(400ms刻み)、境界がデータ点にも一致
            listOf(0L, 100L, 350L, 900L, 2_000L), // 不均等間隔
            listOf(0L, 2_000L) // 2点のみ
        )

        for (keyframeTimes in keyframeTimesLayouts) {
            val keyframes = keyframeTimes.map { t ->
                CameraDirector.CameraKeyframe(t, 0.0, 0.0, 0.0)
            }
            val windowCount = keyframeTimes.size
            // windowBoundaries[i]は窓iのwindowStart（i=0の窓は0、以降は隣接キーフレームの中点）。
            val windowBoundaries = keyframeTimes.indices.map { index ->
                if (index == 0) 0L else (keyframeTimes[index - 1] + keyframeTimes[index]) / 2
            }

            // 各窓の所有インデックス範囲を、computeKeyframesと同じ規約で事前計算する。
            val ownedRanges = (0 until windowCount).map { index ->
                val isLastWindow = index == windowCount - 1
                val windowStart = windowBoundaries[index]
                val windowEnd = if (isLastWindow) keyframeTimes.last() else windowBoundaries[index + 1]
                CameraDirector.resolveWindowIndexRange(
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    isLastWindow = isLastWindow,
                    timestampsMillis = timestamps,
                    timeline = timeline
                )
            }

            // 全データ点（窓境界ちょうどに一致する点を含む）について、currentKeyframeIndexの結果が、
            // そのデータ時刻を所有する窓のインデックスと一致することを確認する。
            for (dataIndex in timestamps.indices) {
                val dataTimeMillis = timestamps[dataIndex]
                if (dataTimeMillis < keyframeTimes.first() || dataTimeMillis > keyframeTimes.last()) continue

                val ownerWindowIndex = ownedRanges.indexOfFirst { (from, to) -> dataIndex in from until to }
                if (ownerWindowIndex == -1) continue // 退化窓が挟む未所有点はブラケット対象で本検証の対象外。

                val actualIndex = CameraDirector.currentKeyframeIndex(keyframes, dataTimeMillis)
                assertEquals(
                    "playbackMillis=$dataTimeMillis(keyframeTimes=$keyframeTimes)で" +
                        "currentKeyframeIndexとresolveWindowIndexRangeの所有権が一致しません",
                    ownerWindowIndex, actualIndex
                )
            }

            // データ点に一致しない窓境界ミリ秒(中点)ちょうどについても直接検証する。
            // resolveWindowIndexRangeの規約(D-029/D-030)では共有境界は後の窓のwindowStart（lowerBoundベース、
            // 含む）に属し前の窓のwindowEnd（含まない）には属さないため、境界ミリ秒ちょうどの所有者は
            // 常に後の窓（インデックスがそのままwindowBoundariesの添字）になる。
            for (laterWindowIndex in 1 until windowCount) {
                val boundary = windowBoundaries[laterWindowIndex]
                val actualIndex = CameraDirector.currentKeyframeIndex(keyframes, boundary)
                assertEquals(
                    "境界ミリ秒=$boundary(keyframeTimes=$keyframeTimes)で" +
                        "currentKeyframeIndexが後の窓を選んでいません",
                    laterWindowIndex, actualIndex
                )
            }
        }
    }

    // ---- resolveKeyframeBlend（T-025、動画書き出しでの背景クロスフェード） ----

    @Test
    fun resolveKeyframeBlend_singleKeyframe_neverBlends() {
        val keyframes = listOf(CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0))

        val blend = CameraDirector.resolveKeyframeBlend(keyframes, 12_345L)

        assertEquals(0, blend.fromIndex)
        assertEquals(0, blend.toIndex)
        assertEquals(0f, blend.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_atOrBeforeFirstKeyframe_staysFullyOnFirst() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(1_000L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(2_000L, 35.1, 139.1, 10.0)
        )

        val blend = CameraDirector.resolveKeyframeBlend(keyframes, 1_000L)

        assertEquals(0, blend.fromIndex)
        assertEquals(0, blend.toIndex)
        assertEquals(0f, blend.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_atOrAfterLastKeyframe_staysFullyOnLast() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        val blend = CameraDirector.resolveKeyframeBlend(keyframes, 5_000L)

        assertEquals(1, blend.fromIndex)
        assertEquals(1, blend.toIndex)
        assertEquals(0f, blend.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_atMidpoint_isHalfBlended() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        val blend = CameraDirector.resolveKeyframeBlend(keyframes, 500L)

        assertEquals(0, blend.fromIndex)
        assertEquals(1, blend.toIndex)
        assertEquals(0.5f, blend.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_linearlyInterpolatesAcrossMultipleKeyframes() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0),
            CameraDirector.CameraKeyframe(3_000L, 35.2, 139.2, 10.0)
        )

        val early = CameraDirector.resolveKeyframeBlend(keyframes, 250L)
        assertEquals(0, early.fromIndex)
        assertEquals(1, early.toIndex)
        assertEquals(0.25f, early.toAlpha, 1e-6f)

        val late = CameraDirector.resolveKeyframeBlend(keyframes, 2_500L)
        assertEquals(1, late.fromIndex)
        assertEquals(2, late.toIndex)
        assertEquals(0.75f, late.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_exactlyAtInteriorKeyframe_isFullyOnThatKeyframe() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0),
            CameraDirector.CameraKeyframe(2_000L, 35.2, 139.2, 10.0)
        )

        // ちょうどindex1のplaybackMillisでは、二分探索が区間[1,2]のfromIndex側(=1)を選び、
        // toAlpha=0(区間の始端)になる。fromIndex=1が100%不透明で描画されるため、
        // 結果的にindex1のキーフレームが完全に表示される（境界での「どちらか一方が完全に表示される」
        // という不変条件は、先頭/末尾キーフレーム（早期returnの分岐）と共通する）。
        val blend = CameraDirector.resolveKeyframeBlend(keyframes, 1_000L)
        assertEquals(1, blend.fromIndex)
        assertEquals(0f, blend.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_alphaIsMonotonicallyIncreasingWithinASegment() {
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 10.0),
            CameraDirector.CameraKeyframe(1_000L, 35.1, 139.1, 10.0)
        )

        // t=0はfromIndex==toIndex==0（先頭キーフレーム自身、早期returnの分岐）となりtoAlpha=0、
        // t=1_000（最後のキーフレームちょうど）も同様にfromIndex==toIndex==1でtoAlpha=0という退化ケースに
        // なるため、区間[100,900]（fromIndex=0,toIndex=1が維持される範囲）ではインデックスも検証し、
        // 区間全体ではtoAlphaの値そのものが単調非減少であることのみを検証する。
        var previousAlpha = -1f
        for (t in 0L until 1_000L step 100L) {
            val blend = CameraDirector.resolveKeyframeBlend(keyframes, t)
            if (t > 0L) {
                assertEquals(0, blend.fromIndex)
                assertEquals(1, blend.toIndex)
            }
            assertTrue("alphaは単調増加である必要があります: t=$t", blend.toAlpha >= previousAlpha)
            previousAlpha = blend.toAlpha
        }

        val atLast = CameraDirector.resolveKeyframeBlend(keyframes, 1_000L)
        assertEquals(1, atLast.fromIndex)
        assertEquals(1, atLast.toIndex)
        assertEquals(0f, atLast.toAlpha, 1e-6f)
    }

    @Test
    fun resolveKeyframeBlend_emptyKeyframes_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraDirector.resolveKeyframeBlend(emptyList(), 0L)
        }
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
