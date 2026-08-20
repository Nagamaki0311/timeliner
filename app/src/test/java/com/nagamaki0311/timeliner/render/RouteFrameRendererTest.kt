package com.nagamaki0311.timeliner.render

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
}
