package com.nagamaki0311.timeliner.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [PlaybackController.setRoute]/[PlaybackController.setSpeedMode]の並行性制御（`rebuildGeneration`）を
 * 検証する（docs/decisions.md D-019決定2）。`kotlinx-coroutines-test`等の新規依存は追加せず、
 * `kotlinx.coroutines.runBlocking`＋`launch`のみで検証する。
 *
 * `runBlocking`のイベントループはコルーチンを協調的（1スレッド内で、いずれかが中断するまで次を開始しない）に
 * 実行するため、`launch`した順に各呼び出しの同期部分（`rebuildGeneration`のインクリメント、`route`/`speedMode`の
 * 書き換え）が必ずその順で走る。これにより、`withContext(Dispatchers.Default)`側の実計算がどちらのスレッドで
 * 先に終わるか（実時間の競合）に関わらず、「後から呼ばれた方の`rebuildGeneration`が常に最新」という前提の下で
 * 最終的にどちらの結果がコミットされるべきかを決定的に予測できる。
 */
class PlaybackControllerTest {

    private fun newController(): PlaybackController = PlaybackController(CoroutineScope(Job() + Dispatchers.Default))

    // 1時間滞在後、1秒で約1km移動する3点。Auto/Manualいずれのモードでも非退化な結果になる（PlaybackTimelineTestと同型）。
    private val routeB = Triple(
        doubleArrayOf(35.0, 35.0, 35.009),
        doubleArrayOf(139.0, 139.0, 139.0),
        longArrayOf(0L, 3_600_000L, 3_601_000L)
    )

    private val initialRoute = Triple(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0),
        longArrayOf(1000L, 2000L, 3000L)
    )

    // routeBとは時刻・座標が明確に異なる別ルート（2時間滞在後、1.5秒で約2km移動）。
    private val routeC = Triple(
        doubleArrayOf(36.0, 36.0, 36.02),
        doubleArrayOf(140.0, 140.0, 140.0),
        longArrayOf(0L, 7_200_000L, 7_201_500L)
    )

    // ファイルシステムのエンコーディング制約(クラスファイル名に日本語を含められない環境がある)のため
    // メソッド名はASCIIのみとし、意図はKDoc/コメントで補う。
    /** setRoute実行中にsetSpeedModeを呼ぶと、最終状態は後から呼ばれたsetSpeedModeの入力を反映する。 */
    @Test
    fun setSpeedModeCalledDuringSetRoute_finalStateReflectsTheLaterSetSpeedModeCall() {
        val controller = newController()
        val expectedMode = SpeedMode.Manual(speedMultiplier = 60.0)
        val expectedWinningTimeline = PlaybackTimeline.buildManual(routeB.third, expectedMode.speedMultiplier)
        // 生成に失敗しない（discardされる）ことだけ確認できればよい、setRoute単独のデフォルトモード計算結果。
        val discardedTimeline = PlaybackTimeline.buildAuto(routeB.third, routeB.first, routeB.second, (SpeedMode.DEFAULT as SpeedMode.Auto).targetDurationMillis)

        runBlocking {
            // launchした順（setRoute→setSpeedMode）に同期部分が走ることを前提に、setSpeedModeの方を
            // 「後から呼ばれた」呼び出しとして扱う。
            launch { controller.setRoute(routeB.first, routeB.second, routeB.third) }
            launch { controller.setSpeedMode(expectedMode) }
        }

        controller.seekTo(0.5f)

        val expectedElapsed = (0.5f * expectedWinningTimeline.totalPlaybackMillis()).toLong()
        val expectedDataTime = expectedWinningTimeline.dataTimeAtPlaybackMillis(expectedElapsed)
        val discardedElapsed = (0.5f * discardedTimeline.totalPlaybackMillis()).toLong()
        val discardedDataTime = discardedTimeline.dataTimeAtPlaybackMillis(discardedElapsed)

        // 想定した2つの候補（後着のsetSpeedMode由来 / 先着のsetRoute単独由来）が実際に見分けられる値であることの確認。
        assertNotEquals(discardedDataTime, expectedDataTime)
        assertEquals(expectedDataTime, controller.state.value.dataTimeMillis)
        assertEquals(expectedMode, controller.state.value.speedMode)
    }

    /** 逆順（setSpeedMode→setRoute）で呼んだ場合、最終状態は後から呼ばれたsetRouteの入力を反映する。 */
    @Test
    fun setRouteCalledDuringSetSpeedMode_reverseOrder_finalStateReflectsTheLaterSetRouteCall() {
        val controller = newController()
        runBlocking { controller.setRoute(initialRoute.first, initialRoute.second, initialRoute.third) }

        val commonMode = SpeedMode.Auto(targetDurationMillis = 45_000L)
        val expectedWinningTimeline = PlaybackTimeline.buildAuto(routeC.third, routeC.first, routeC.second, commonMode.targetDurationMillis)
        val discardedTimeline = PlaybackTimeline.buildAuto(initialRoute.third, initialRoute.first, initialRoute.second, commonMode.targetDurationMillis)

        runBlocking {
            // 今度は逆順（setSpeedMode→setRoute）でlaunchする。setRouteの方が後から呼ばれた呼び出しになる。
            launch { controller.setSpeedMode(commonMode) }
            launch { controller.setRoute(routeC.first, routeC.second, routeC.third) }
        }

        controller.seekTo(0.5f)

        val expectedElapsed = (0.5f * expectedWinningTimeline.totalPlaybackMillis()).toLong()
        val expectedDataTime = expectedWinningTimeline.dataTimeAtPlaybackMillis(expectedElapsed)
        val discardedElapsed = (0.5f * discardedTimeline.totalPlaybackMillis()).toLong()
        val discardedDataTime = discardedTimeline.dataTimeAtPlaybackMillis(discardedElapsed)

        assertNotEquals(discardedDataTime, expectedDataTime)
        assertEquals(expectedDataTime, controller.state.value.dataTimeMillis)
        assertEquals(commonMode, controller.state.value.speedMode)
    }
}
