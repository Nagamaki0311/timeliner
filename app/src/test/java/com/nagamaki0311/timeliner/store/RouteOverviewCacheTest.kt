package com.nagamaki0311.timeliner.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RouteOverviewCache]（旧`TimelineViewModel.ensureRouteOverview`/`invalidateRouteOverview`の並行性ロジック）を
 * 検証する（docs/decisions.md D-020決定1）。`kotlinx-coroutines-test`等の新規依存は追加せず、
 * [PlaybackControllerTest][com.nagamaki0311.timeliner.playback.PlaybackControllerTest]（D-019）と同じ
 * `kotlinx.coroutines.runBlocking`＋`launch`のみで検証する。
 *
 * `TimelineViewModel`は`ViewModel`基底クラス・`viewModelScope`（`Dispatchers.Main`依存、実際のAndroid実行環境
 * またはRobolectric/`kotlinx-coroutines-test`が無いと`init`ブロックの`viewModelScope.launch`呼び出し自体が
 * `IllegalStateException`で失敗する）と、`TimelineRepository`（コンストラクタが`TimelineDb`＝`SQLiteOpenHelper`
 * のサブクラスを要求し、これも実`Context`無しにJVM単体テストからインスタンス化できない）の両方に依存するため、
 * `TimelineViewModel`自体をJVM単体テストからインスタンス化することはできない（docs/progress.md参照）。
 * そのためD-020の決定に従い、`ensureRouteOverview`/`invalidateRouteOverview`の並行性ロジック（世代ガード）を
 * DB非依存の[RouteOverviewCache]へ切り出し、`TimelineViewModel`はこれへ委譲する形にした上で、
 * このテストは[RouteOverviewCache]を直接インスタンス化して検証する。
 */
class RouteOverviewCacheTest {

    private fun newScope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.Default)

    private fun overviewFor(date: String): RouteOverview {
        val points = PointBlobCodec.DecodedPoints(
            doubleArrayOf(35.0, 35.001),
            doubleArrayOf(139.0, 139.001),
            longArrayOf(0L, 60_000L)
        )
        val day = TimelineRepository.DayRecord(
            date = date,
            startMillis = 0L,
            endMillis = 60_000L,
            pointCount = 2,
            distanceMeters = 0.0,
            points = points
        )
        return RouteOverview.buildFrom(listOf(day))
    }

    /**
     * 構築中（[RouteOverviewCache]の[builder]実行中）に[RouteOverviewCache.invalidate]が呼ばれた場合、
     * その構築[Deferred]がキャンセルされ（docs/decisions.md D-020決定2）、待ち受けていた[ensure]呼び出しは
     * 古い結果を返さず[CancellationException]で終わる。かつ、その後の[ensure]呼び出しは古い結果を
     * キャッシュから返さず（世代ガード、決定1）必ず新規に再構築する。
     * [buildCount]は`delay`（`NonCancellable`保護なし）を挟むため、無効化後の[Job.cancel]により
     * `builder`のdelay中断がそのまま[CancellationException]として伝播する（実測確認済み、
     * `Job.cancel`後は本体が値を返せたとしても最終的に必ずキャンセル完了になるkotlinx.coroutinesの仕様のため、
     * この結果は実時間のタイミングに依存せず決定的）。
     */
    @Test
    fun invalidateCalledDuringBuild_awaitingCallerGetsCancelled_nextEnsureRebuilds() {
        var buildCount = 0
        val staleOverview = overviewFor("2026-01-01")
        val rebuiltOverview = overviewFor("2026-01-02")
        val cache = RouteOverviewCache(newScope()) {
            buildCount++
            // 1回目(キャンセルされる古い構築)だけ時間がかかり、2回目(再構築)は即座に終わる想定。
            if (buildCount == 1) delay(100)
            if (buildCount == 1) staleOverview else rebuiltOverview
        }

        runBlocking {
            val deferredFirst = async { cache.ensure() }
            // builderが開始しbuildJobへ登録されるだけの猶予を与えてから無効化する
            // （delayを伴う協調的スケジューリング、PlaybackControllerTestと同種の手法）。
            delay(20)
            cache.invalidate()
            try {
                deferredFirst.await()
                fail("invalidateでキャンセルされたbuildJobを待っていた呼び出しはCancellationExceptionで終わるはず")
            } catch (e: CancellationException) {
                // 期待どおり：古い結果は呼び出し元にも返らない。
            }
        }
        assertEquals(1, buildCount)

        val secondResult = runBlocking { cache.ensure() }
        // invalidate後は再構築が必要なため、builderが2回目呼ばれるはず（staleOverviewがキャッシュに
        // 書き戻されていれば、ここでbuilderは呼ばれず1回目のstaleOverviewが即返ってしまう）。
        assertEquals("invalidate後は再構築が必要なため、builderが2回目呼ばれるはず", 2, buildCount)
        assertSame("再構築された新しい結果が返るはず", rebuiltOverview, secondResult)
        assertTrue("古いstaleOverviewがそのままキャッシュから返ってきてはいけない", secondResult !== staleOverview)
    }

    /**
     * 並行して複数回[RouteOverviewCache.ensure]が呼ばれても、同じ構築[kotlinx.coroutines.Deferred]を共有し、
     * [builder]が二重に呼ばれない（結果はすべて同一インスタンス）こと。
     */
    @Test
    fun ensureCalledConcurrently_sharesSingleBuildJob_buildsOnlyOnce() {
        var buildCount = 0
        val overview = overviewFor("2026-02-01")
        val cache = RouteOverviewCache(newScope()) {
            buildCount++
            delay(50)
            overview
        }

        val results = java.util.Collections.synchronizedList(mutableListOf<RouteOverview>())
        runBlocking {
            val jobs = List(5) { launch { results.add(cache.ensure()) } }
            jobs.joinAll()
        }

        assertEquals(1, buildCount)
        assertEquals(5, results.size)
        results.forEach { assertSame(overview, it) }

        // 構築済みキャッシュにヒットする以降の呼び出しでは、builderが再度呼ばれない。
        val cachedResult = runBlocking { cache.ensure() }
        assertEquals(1, buildCount)
        assertSame(overview, cachedResult)
    }
}
