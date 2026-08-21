package com.nagamaki0311.timeliner.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * [RouteOverview]の遅延構築・キャッシュを担う（docs/tasks.md T-014・T-014b、docs/decisions.md D-020）。
 * [com.nagamaki0311.timeliner.ui.TimelineViewModel]が`viewModelScope`と、既定では
 * [RouteOverview.build]を呼ぶ[builder]を渡して保持する。
 *
 * [builder]を構築時に注入可能にしているのは、[TimelineRepository]/[TimelineDb]（`SQLiteOpenHelper`という
 * Android API依存）を介さず、世代ガードによる並行性ロジックだけを[RouteOverviewCacheTest]でJVM単体テスト
 * できるようにするため（`TimelineViewModel`自体は`ViewModel`基底クラス・`viewModelScope`の`Dispatchers.Main`
 * 依存があり、テストでインスタンス化できないため分離した。docs/progress.md参照）。
 *
 * [PlaybackController][com.nagamaki0311.timeliner.playback.PlaybackController]の`rebuildGeneration`と
 * 同じ世代ガードパターン（docs/decisions.md D-019）を使い、構築中（[builder]の実行中）に[invalidate]が
 * 呼ばれても、完了した古い構築結果を[ensure]の戻り値以外（内部キャッシュ）へ書き戻さない。
 * 並行して複数回[ensure]が呼ばれた場合も、同じ構築[Deferred]を共有し二重に構築処理を走らせない。
 */
class RouteOverviewCache(
    private val scope: CoroutineScope,
    private val builder: suspend () -> RouteOverview
) {
    private var cached: RouteOverview? = null
    private var buildJob: Deferred<RouteOverview>? = null
    private var generation = 0L

    /** キャッシュ済みなら即返す。未構築なら[Dispatchers.Default]上で1度だけ構築してキャッシュする。 */
    suspend fun ensure(): RouteOverview {
        cached?.let { return it }
        val myGeneration = generation
        val job = buildJob ?: scope.async(Dispatchers.Default) { builder() }.also { buildJob = it }
        val result = job.await()
        if (myGeneration == generation) {
            cached = result
            buildJob = null
        }
        return result
    }

    /**
     * キャッシュを無効化し、次回[ensure]呼び出し時に再構築させる。構築中の[Deferred]があれば
     * キャンセルする（無駄な処理の完走を避ける、docs/decisions.md D-020決定2）。
     */
    fun invalidate() {
        generation++
        buildJob?.cancel()
        buildJob = null
        cached = null
    }
}
