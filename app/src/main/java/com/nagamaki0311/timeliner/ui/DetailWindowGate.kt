package com.nagamaki0311.timeliner.ui

/**
 * [TimelineViewModel]における「詳細ウィンドウ機構（長期間選択時のみ有効な遅延ロード、
 * [com.nagamaki0311.timeliner.store.DetailWindow]、docs/tasks.md T-021）が有効かどうか」の状態と、
 * `scheduleDetailWindowLoad`（`repository.queryDays`のIO待ち）とユーザー操作（`selectPeriod`等による
 * 期間切り替え）が競合しないようにする世代ガードをまとめて保持する（docs/decisions.md D-027決定1）。
 *
 * `selectPeriod`は`_selectedPeriod`を同期的に即時更新するが、[isLongPeriodSelected]・詳細ウィンドウの
 * ロード結果は`loadRoute`のIO・計算完了後まで更新されない。この間に旧期間の再生ループ由来で
 * `scheduleDetailWindowLoad`が呼ばれると、`_selectedPeriod`を読み直して既に切り替わった新期間の境界を
 * 誤って使ってしまう。[invalidate]を`_selectedPeriod`更新と同じ同期区間で呼ぶことで[isLongPeriodSelected]を
 * 即座にfalseへ戻し、詳細ウィンドウの呼び出し自体を発生させないようにする。
 *
 * [com.nagamaki0311.timeliner.store.RouteOverviewCache]・[PeriodResolutionGate]と同じ「世代カウンタで
 * 古い非同期結果の書き戻しを防ぐ」パターンを採用しているが、対象がDB/Repositoryに依存する構築処理ではなく
 * 単純な整数カウンタと真偽値のみのため、`TimelineViewModel`をインスタンス化できないJVM単体テスト
 * （D-020と同じ制約）からもこのクラス単体で検証できる。
 */
class DetailWindowGate {
    /** 現在選択中の期間が長期間（[com.nagamaki0311.timeliner.store.RouteOverview]経由）かどうか。詳細ウィンドウ機構の有効/無効を切り替える。 */
    var isLongPeriodSelected: Boolean = false
        private set

    private var generation = 0L

    /**
     * 期間切り替えの同期区間（`_selectedPeriod`を更新した直後）で呼ぶ。[isLongPeriodSelected]を即座にfalseへ戻し、
     * 世代を進めることでこの時点までに発行されていた[beginLoad]の結果を[isCurrent]経由で無効化する。
     */
    fun invalidate() {
        isLongPeriodSelected = false
        generation++
    }

    /** `loadRoute`完了時に呼ぶ。[isLongPeriodSelected]を新期間の判定結果へ更新し、世代を進める。 */
    fun activate(isLongPeriod: Boolean) {
        isLongPeriodSelected = isLongPeriod
        generation++
    }

    /** `scheduleDetailWindowLoad`の呼び出し世代を発行する。呼び出し元はロード完了後[isCurrent]で結果の反映可否を判定すること。 */
    fun beginLoad(): Long = ++generation

    /** [beginLoad]が返した世代が依然として最新（その後[invalidate]・[activate]・別の[beginLoad]で上書きされていない）かどうか。 */
    fun isCurrent(generation: Long): Boolean = this.generation == generation
}
