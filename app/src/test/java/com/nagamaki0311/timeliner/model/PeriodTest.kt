package com.nagamaki0311.timeliner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class PeriodTest {

    @Test
    fun of_day_startAndEndAreSameDate() {
        val period = Period.of(PeriodType.DAY, LocalDate.of(2026, 8, 20))

        assertEquals(LocalDate.of(2026, 8, 20), period.startDate)
        assertEquals(LocalDate.of(2026, 8, 20), period.endDate)
    }

    @Test
    fun day_nextAndPrevious_shiftByOneDay() {
        val period = Period.of(PeriodType.DAY, LocalDate.of(2026, 8, 20))

        assertEquals(LocalDate.of(2026, 8, 21), period.next().startDate)
        assertEquals(LocalDate.of(2026, 8, 19), period.previous().startDate)
    }

    @Test
    fun day_nextThenPrevious_returnsToOriginal() {
        val period = Period.of(PeriodType.DAY, LocalDate.of(2026, 12, 31))

        assertEquals(period, period.next().previous())
    }

    @Test
    fun of_week_startsOnMonday_containingReferenceDate() {
        // 2026-08-20は木曜日
        val period = Period.of(PeriodType.WEEK, LocalDate.of(2026, 8, 20))

        assertEquals(LocalDate.of(2026, 8, 17), period.startDate) // 月曜
        assertEquals(LocalDate.of(2026, 8, 23), period.endDate) // 日曜
    }

    @Test
    fun of_week_referenceDateIsMonday_startsOnSameDate() {
        val monday = LocalDate.of(2026, 8, 17)
        val period = Period.of(PeriodType.WEEK, monday)

        assertEquals(monday, period.startDate)
        assertEquals(LocalDate.of(2026, 8, 23), period.endDate)
    }

    @Test
    fun week_next_crossesYearBoundary() {
        // 2025-12-29(月)〜2026-01-04(日)の次の週は2026-01-05(月)〜01-11(日)
        val period = Period.of(PeriodType.WEEK, LocalDate.of(2025, 12, 30))

        val next = period.next()

        assertEquals(LocalDate.of(2026, 1, 5), next.startDate)
        assertEquals(LocalDate.of(2026, 1, 11), next.endDate)
    }

    @Test
    fun week_nextThenPrevious_returnsToOriginal() {
        val period = Period.of(PeriodType.WEEK, LocalDate.of(2026, 3, 3))

        assertEquals(period, period.next().previous())
    }

    @Test
    fun of_month_coversWholeMonth() {
        val period = Period.of(PeriodType.MONTH, LocalDate.of(2026, 2, 15))

        assertEquals(LocalDate.of(2026, 2, 1), period.startDate)
        assertEquals(LocalDate.of(2026, 2, 28), period.endDate) // 2026年は平年
    }

    @Test
    fun of_month_leapYearFebruary_endsOn29th() {
        val period = Period.of(PeriodType.MONTH, LocalDate.of(2028, 2, 15))

        assertEquals(LocalDate.of(2028, 2, 29), period.endDate) // 2028年はうるう年
    }

    @Test
    fun month_next_fromJanuary31stBasedPeriod_movesToFebruaryWithoutOverflow() {
        // 基準日が31日でもMONTH期間のstartDateは常に月初(1日)のため、月またぎで例外を起こさない
        val period = Period.of(PeriodType.MONTH, LocalDate.of(2026, 1, 31))

        val next = period.next()

        assertEquals(LocalDate.of(2026, 2, 1), next.startDate)
        assertEquals(LocalDate.of(2026, 2, 28), next.endDate)
    }

    @Test
    fun month_nextThenPrevious_returnsToOriginal() {
        val period = Period.of(PeriodType.MONTH, LocalDate.of(2026, 12, 1))

        assertEquals(period, period.next().previous())
    }

    @Test
    fun of_year_coversWholeYear() {
        val period = Period.of(PeriodType.YEAR, LocalDate.of(2026, 6, 1))

        assertEquals(LocalDate.of(2026, 1, 1), period.startDate)
        assertEquals(LocalDate.of(2026, 12, 31), period.endDate)
    }

    @Test
    fun year_next_movesToNextYear() {
        val period = Period.of(PeriodType.YEAR, LocalDate.of(2027, 6, 1))

        val next = period.next()

        assertEquals(LocalDate.of(2028, 1, 1), next.startDate)
        assertEquals(LocalDate.of(2028, 12, 31), next.endDate) // 2028年はうるう年
    }

    @Test
    fun year_nextThenPrevious_returnsToOriginal() {
        val period = Period.of(PeriodType.YEAR, LocalDate.of(2026, 1, 1))

        assertEquals(period, period.next().previous())
    }

    @Test
    fun custom_next_shiftsByExactSpanLength() {
        val period = Period(PeriodType.CUSTOM, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)) // 3日間

        val next = period.next()

        assertEquals(LocalDate.of(2026, 8, 4), next.startDate)
        assertEquals(LocalDate.of(2026, 8, 6), next.endDate)
    }

    @Test
    fun custom_previous_shiftsByExactSpanLength() {
        val period = Period(PeriodType.CUSTOM, LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 6)) // 3日間

        val previous = period.previous()

        assertEquals(LocalDate.of(2026, 8, 1), previous.startDate)
        assertEquals(LocalDate.of(2026, 8, 3), previous.endDate)
    }

    @Test
    fun custom_nextThenPrevious_returnsToOriginal() {
        val period = Period(PeriodType.CUSTOM, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 20))

        assertEquals(period, period.next().previous())
    }

    @Test
    fun constructor_endDateBeforeStartDate_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            Period(PeriodType.CUSTOM, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 19))
        }
    }

    @Test
    fun label_day_formatsAsExpected() {
        val period = Period.of(PeriodType.DAY, LocalDate.of(2026, 8, 20))

        assertEquals("2026年8月20日", period.label())
    }

    @Test
    fun label_week_sameMonth_formatsAsRange() {
        val period = Period.of(PeriodType.WEEK, LocalDate.of(2026, 8, 20))

        assertEquals("8月17日〜23日", period.label())
    }

    @Test
    fun label_week_crossingMonth_formatsWithBothMonths() {
        // 2026-08-31(月)〜2026-09-06(日)
        val period = Period.of(PeriodType.WEEK, LocalDate.of(2026, 9, 3))

        assertEquals("8月31日〜9月6日", period.label())
    }

    @Test
    fun label_month_formatsAsExpected() {
        val period = Period.of(PeriodType.MONTH, LocalDate.of(2026, 8, 20))

        assertEquals("2026年8月", period.label())
    }

    @Test
    fun label_year_formatsAsExpected() {
        val period = Period.of(PeriodType.YEAR, LocalDate.of(2026, 8, 20))

        assertEquals("2026年", period.label())
    }

    @Test
    fun ofAll_setsStartAndEndDateFromArguments() {
        val period = Period.ofAll(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 8, 13))

        assertEquals(PeriodType.ALL, period.type)
        assertEquals(LocalDate.of(2024, 1, 1), period.startDate)
        assertEquals(LocalDate.of(2025, 8, 13), period.endDate)
    }

    @Test
    fun of_all_throwsUnsupportedOperationException() {
        // ALLは基準日単独からは計算できないため、Period.ofAllを使う必要がある（docs/tasks.md T-017）。
        assertThrows(UnsupportedOperationException::class.java) {
            Period.of(PeriodType.ALL, LocalDate.of(2026, 8, 20))
        }
    }

    @Test
    fun all_nextAndPrevious_returnSameInstance() {
        // 全期間に「次/前」の概念は無いため、変化なし＝自身を返す（docs/tasks.md T-017）。
        val period = Period.ofAll(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 8, 13))

        assertEquals(period, period.next())
        assertEquals(period, period.previous())
    }

    @Test
    fun label_all_formatsAsRangeWithPrefix() {
        val period = Period.ofAll(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 8, 13))

        assertEquals("全期間（2024年1月1日〜2025年8月13日）", period.label())
    }
}
