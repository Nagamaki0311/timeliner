package com.nagamaki0311.timeliner.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteFrameRenderer.progressAtDataTime]のみを対象とする（`draw`本体は`android.graphics.Canvas`に
 * 依存するためJVM単体テスト不可、D-003と同様の制約。`app/build.gradle.kts`の
 * `isReturnDefaultValues = true`により、このテストが間接的に触れる`RouteFrameRenderer`オブジェクトの
 * 初期化[Style().routeColor]等が呼ぶ`android.graphics.Color.parseColor`は例外を投げず既定値[0]を返す）。
 */
class RouteFrameRendererTest {

    @Test
    fun progressAtDataTime_singlePoint_returnsOne() {
        val result = RouteFrameRenderer.progressAtDataTime(longArrayOf(1000L), 1000L)
        assertEquals(1f, result, 1e-6f)
    }

    @Test
    fun progressAtDataTime_beforeFirstTimestamp_returnsZero() {
        val timestamps = longArrayOf(1000L, 2000L, 3000L)
        assertEquals(0f, RouteFrameRenderer.progressAtDataTime(timestamps, 0L), 1e-6f)
        assertEquals(0f, RouteFrameRenderer.progressAtDataTime(timestamps, 1000L), 1e-6f)
    }

    @Test
    fun progressAtDataTime_afterLastTimestamp_returnsOne() {
        val timestamps = longArrayOf(1000L, 2000L, 3000L)
        assertEquals(1f, RouteFrameRenderer.progressAtDataTime(timestamps, 3000L), 1e-6f)
        assertEquals(1f, RouteFrameRenderer.progressAtDataTime(timestamps, 5000L), 1e-6f)
    }

    @Test
    fun progressAtDataTime_exactMiddleTimestamp_returnsHalf() {
        val timestamps = longArrayOf(1000L, 2000L, 3000L)
        assertEquals(0.5f, RouteFrameRenderer.progressAtDataTime(timestamps, 2000L), 1e-6f)
    }

    @Test
    fun progressAtDataTime_betweenTimestamps_interpolatesLinearly() {
        // 4点(index 0..3)、2000〜3000の中間(2500)はindex 1.5、progress=1.5/3=0.5
        val timestamps = longArrayOf(1000L, 2000L, 3000L, 4000L)
        assertEquals(0.5f, RouteFrameRenderer.progressAtDataTime(timestamps, 2500L), 1e-6f)
    }

    @Test
    fun progressAtDataTime_duplicateTimestamps_doesNotDivideByZero() {
        val timestamps = longArrayOf(1000L, 2000L, 2000L, 3000L)
        // 2000と2000の間(fraction計算のtHi==tLo分岐)を通っても例外にならず、有効な範囲の値を返す。
        val result = RouteFrameRenderer.progressAtDataTime(timestamps, 2000L)
        assertTrue(result in 0f..1f)
    }

    // --- computeGapBreakIndices（T-020: ギャップ分断） ---

    @Test
    fun computeGapBreakIndices_noGap_returnsEmpty() {
        val timestamps = longArrayOf(0L, 1000L, 2000L, 3000L)
        val result = RouteFrameRenderer.computeGapBreakIndices(timestamps, gapMillis = 6L * 60 * 60 * 1000)
        assertEquals(0, result.size)
    }

    @Test
    fun computeGapBreakIndices_gapExceedingThreshold_returnsBreakIndex() {
        val gapMillis = 6L * 60 * 60 * 1000
        // index2とindex3の間が閾値超(7時間)、それ以外は1秒間隔。
        val timestamps = longArrayOf(0L, 1000L, 2000L, 2000L + 7L * 60 * 60 * 1000)
        val result = RouteFrameRenderer.computeGapBreakIndices(timestamps, gapMillis)
        assertArrayEquals(intArrayOf(3), result)
    }

    @Test
    fun computeGapBreakIndices_gapExactlyAtThreshold_isNotABreak() {
        val gapMillis = 6L * 60 * 60 * 1000
        // ちょうど閾値と同じ間隔は「超えていない」ため分断しない（境界値）。
        val timestamps = longArrayOf(0L, gapMillis)
        val result = RouteFrameRenderer.computeGapBreakIndices(timestamps, gapMillis)
        assertEquals(0, result.size)
    }

    @Test
    fun computeGapBreakIndices_multipleGaps_returnsAllInAscendingOrder() {
        val gapMillis = 100L
        val timestamps = longArrayOf(0L, 50L, 200L, 250L, 400L)
        val result = RouteFrameRenderer.computeGapBreakIndices(timestamps, gapMillis)
        assertArrayEquals(intArrayOf(2, 4), result)
    }

    @Test
    fun computeGapBreakIndices_emptyOrSinglePoint_returnsEmpty() {
        assertEquals(0, RouteFrameRenderer.computeGapBreakIndices(longArrayOf()).size)
        assertEquals(0, RouteFrameRenderer.computeGapBreakIndices(longArrayOf(1000L)).size)
    }

    // --- recentWindowStartIndex（T-020: 過去/直近の描き分け） ---

    @Test
    fun recentWindowStartIndex_emptyArray_returnsZero() {
        assertEquals(0, RouteFrameRenderer.recentWindowStartIndex(longArrayOf(), windowMillis = 1000L))
    }

    @Test
    fun recentWindowStartIndex_allPointsWithinWindow_returnsZero() {
        val timestamps = longArrayOf(0L, 100L, 200L, 300L)
        assertEquals(0, RouteFrameRenderer.recentWindowStartIndex(timestamps, windowMillis = 1000L))
    }

    @Test
    fun recentWindowStartIndex_someOutsideWindow_returnsFirstIndexInWindow() {
        // 末尾は3000。ウィンドウ1000msなので、閾値は2000。timestamps[3]=2500が最初に閾値以上。
        val timestamps = longArrayOf(0L, 1000L, 1900L, 2500L, 3000L)
        assertEquals(3, RouteFrameRenderer.recentWindowStartIndex(timestamps, windowMillis = 1000L))
    }

    @Test
    fun recentWindowStartIndex_windowLargerThanRange_returnsZero() {
        val timestamps = longArrayOf(0L, 1000L, 2000L)
        assertEquals(0, RouteFrameRenderer.recentWindowStartIndex(timestamps, windowMillis = 1_000_000L))
    }

    @Test
    fun recentWindowStartIndex_thresholdExactlyOnTimestamp_includesThatPoint() {
        // 末尾5000、window2000→閾値3000。timestamps[1]=3000はちょうど閾値と一致するため直近に含める。
        val timestamps = longArrayOf(0L, 3000L, 4000L, 5000L)
        assertEquals(1, RouteFrameRenderer.recentWindowStartIndex(timestamps, windowMillis = 2000L))
    }
}
