package com.nagamaki0311.timeliner.ui

import com.nagamaki0311.timeliner.model.PeriodType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimelineViewModel.isManualModeAllowed]（docs/decisions.md D-017決定2）を検証する。
 *
 * [TimelineViewModel]自体は`ViewModel`基底クラス・`TimelineRepository`（`SQLiteOpenHelper`のサブクラスを要求する
 * コンストラクタ）というAndroid API依存のため実`Context`無しにJVM単体テストからインスタンス化できない
 * （[com.nagamaki0311.timeliner.store.RouteOverviewCacheTest]・docs/decisions.md D-020と同じ制約）。
 * [isManualModeAllowed]はインスタンス状態に依存しない純粋関数として companion object に切り出しているため、
 * インスタンス化せずテストできる。
 */
class TimelineViewModelCompanionTest {

    @Test
    fun isManualModeAllowed_all_isFalse() {
        assertFalse(TimelineViewModel.isManualModeAllowed(PeriodType.ALL))
    }

    @Test
    fun isManualModeAllowed_dayWeekMonthYearCustom_areTrue() {
        assertTrue(TimelineViewModel.isManualModeAllowed(PeriodType.DAY))
        assertTrue(TimelineViewModel.isManualModeAllowed(PeriodType.WEEK))
        assertTrue(TimelineViewModel.isManualModeAllowed(PeriodType.MONTH))
        assertTrue(TimelineViewModel.isManualModeAllowed(PeriodType.YEAR))
        assertTrue(TimelineViewModel.isManualModeAllowed(PeriodType.CUSTOM))
    }
}
