package com.nagamaki0311.timeliner.ui

/**
 * [TimelineViewModel]における「ユーザーが全期間（[com.nagamaki0311.timeliner.model.PeriodType.ALL]）を
 * 意図しているか」の状態と、非同期な全期間解決（`resolveAllPeriod`、DBに実在する最古日〜最新日のクエリ待ち）と
 * ユーザー操作（`selectPeriod`/`selectAllPeriod`）が競合しないようにする世代ガードをまとめて保持する
 * （docs/decisions.md D-023決定1）。
 *
 * [_selectedPeriod][TimelineViewModel]自体はDBが空の場合、全期間解決が
 * [com.nagamaki0311.timeliner.model.PeriodType.DAY]（今日）へフォールバックしうるため、
 * `Period.type`だけでは「ユーザーが明示選択したDAY」なのか「全期間を意図した暫定フォールバック」なのかを
 * 区別できない。[isAllSelected]でこの意図を型とは独立に保持することで区別する。
 *
 * [com.nagamaki0311.timeliner.store.RouteOverviewCache]（docs/decisions.md D-020）や
 * [com.nagamaki0311.timeliner.playback.PlaybackController]の`rebuildGeneration`（docs/decisions.md D-019）と
 * 同じ「世代カウンタで古い非同期結果の書き戻しを防ぐ」パターンを採用しているが、対象がDB/Repositoryに
 * 依存する構築処理ではなく単純な整数カウンタと真偽値のみのため、`TimelineViewModel`をインスタンス化できない
 * JVM単体テスト（D-020と同じ制約、docs/progress.md参照）からもこのクラス単体で検証できる。
 */
class PeriodResolutionGate {
    /** ユーザーが最後に選択した意図が全期間かどうか。既定は`true`（起動時デフォルトは全期間、docs/decisions.md D-017決定1）。 */
    var isAllSelected: Boolean = true
        private set

    private var generation = 0L

    /**
     * `TimelineViewModel.selectPeriod`から呼ぶ。意図をDAY/WEEK/MONTH/YEAR/CUSTOMへ切り替え、
     * 進行中（または今後resumeする）全期間解決の結果を[isCurrent]経由で無視させる。
     */
    fun selectExplicit() {
        generation++
        isAllSelected = false
    }

    /**
     * 全期間解決（`resolveAllPeriod`のIO待ち）を開始する直前に呼ぶ
     * （`TimelineViewModel.init`・`selectAllPeriod`・インポート成功時の再解決）。
     * 意図を全期間へ設定し、この呼び出し以降に始まった解決を識別する世代番号を返す。
     * 呼び出し元は、解決完了後に[isCurrent]でこの世代番号がまだ最新かを確認してから結果を反映すること。
     */
    fun beginResolution(): Long {
        isAllSelected = true
        return ++generation
    }

    /** [beginResolution]が返した[generation]が依然として最新（その後ユーザー操作等で上書きされていない）かどうか。 */
    fun isCurrent(generation: Long): Boolean = this.generation == generation
}
