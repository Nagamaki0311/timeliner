package com.nagamaki0311.timeliner.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * [Period]の種別。`CUSTOM`は任意範囲（[Period]を直接構築して使う。T-006のUI（[com.nagamaki0311.timeliner.ui.PeriodSelector]）は
 * 日/週/月/年/全期間のみ選択可能）。`ALL`は全期間（DBに実在する最古日〜最新日、[Period.ofAll]、docs/tasks.md T-017・docs/decisions.md D-017）。
 */
enum class PeriodType { DAY, WEEK, MONTH, YEAR, ALL, CUSTOM }

/**
 * 期間指定（docs/tasks.md T-006）。`startDate`/`endDate`は両端を含む。
 *
 * 週の起点は月曜（ISO週）に固定する。日本の一般的なカレンダーUIでは日曜起点も広く使われるが、
 * 仕様上の指定が無いため、`java.time`の標準（`DayOfWeek`）に合わせ、テスト結果が
 * ロケールに依存しない決定的な挙動を優先した（保守的判断、docs/progress.md参照）。
 */
data class Period(
    val type: PeriodType,
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    init {
        require(!endDate.isBefore(startDate)) {
            "endDateはstartDateより前であってはいけません: start=$startDate, end=$endDate"
        }
    }

    /** 次の期間へ移動する。`CUSTOM`は現在の期間の日数分だけ後ろへずらす。 */
    fun next(): Period = shift(1)

    /** 前の期間へ移動する。`CUSTOM`は現在の期間の日数分だけ前へずらす。 */
    fun previous(): Period = shift(-1)

    /** 期間の表示用ラベル（例:「2026年8月17日」「8月17日〜23日」「2026年8月」「2026年」「全期間（2024年1月1日〜2025年8月13日）」）。 */
    fun label(): String = when (type) {
        PeriodType.DAY -> dateLabel(startDate)
        PeriodType.WEEK -> weekLabel()
        PeriodType.MONTH -> "%d年%d月".format(startDate.year, startDate.monthValue)
        PeriodType.YEAR -> "%d年".format(startDate.year)
        PeriodType.ALL -> "全期間（${dateLabel(startDate)}〜${dateLabel(endDate)}）"
        PeriodType.CUSTOM -> "$startDate 〜 $endDate"
    }

    private fun dateLabel(date: LocalDate): String = "%d年%d月%d日".format(date.year, date.monthValue, date.dayOfMonth)

    private fun weekLabel(): String = when {
        startDate.year == endDate.year && startDate.month == endDate.month ->
            "%d月%d日〜%d日".format(startDate.monthValue, startDate.dayOfMonth, endDate.dayOfMonth)
        startDate.year == endDate.year ->
            "%d月%d日〜%d月%d日".format(startDate.monthValue, startDate.dayOfMonth, endDate.monthValue, endDate.dayOfMonth)
        else ->
            "%d年%d月%d日〜%d年%d月%d日".format(
                startDate.year, startDate.monthValue, startDate.dayOfMonth,
                endDate.year, endDate.monthValue, endDate.dayOfMonth
            )
    }

    private fun shift(direction: Long): Period = when (type) {
        PeriodType.DAY -> of(PeriodType.DAY, startDate.plusDays(direction))
        PeriodType.WEEK -> of(PeriodType.WEEK, startDate.plusWeeks(direction))
        PeriodType.MONTH -> of(PeriodType.MONTH, startDate.plusMonths(direction))
        PeriodType.YEAR -> of(PeriodType.YEAR, startDate.plusYears(direction))
        // 全期間に「次/前」の概念は無いため、変化なし＝自身を返す（docs/tasks.md T-017）。
        PeriodType.ALL -> this
        PeriodType.CUSTOM -> {
            val spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1
            val offset = spanDays * direction
            Period(PeriodType.CUSTOM, startDate.plusDays(offset), endDate.plusDays(offset))
        }
    }

    companion object {
        /**
         * [referenceDate]を含む[type]の期間を返す。`CUSTOM`は[referenceDate]単独の1日を返す（呼び出し側で範囲を指定したい場合は[Period]のコンストラクタを直接使うこと）。
         * `ALL`は基準日単独からは計算できない（DBに実在する最古日〜最新日が必要）ため未対応。[ofAll]を使うこと。
         */
        fun of(type: PeriodType, referenceDate: LocalDate): Period = when (type) {
            PeriodType.DAY -> Period(PeriodType.DAY, referenceDate, referenceDate)
            PeriodType.WEEK -> {
                val start = referenceDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                Period(PeriodType.WEEK, start, start.plusDays(6))
            }
            PeriodType.MONTH -> {
                val start = referenceDate.withDayOfMonth(1)
                Period(PeriodType.MONTH, start, start.plusMonths(1).minusDays(1))
            }
            PeriodType.YEAR -> {
                val start = referenceDate.withDayOfYear(1)
                Period(PeriodType.YEAR, start, start.plusYears(1).minusDays(1))
            }
            PeriodType.ALL -> throw UnsupportedOperationException(
                "PeriodType.ALLはPeriod.ofAll(earliestDate, latestDate)を使ってください（referenceDate単独からは計算できません）"
            )
            PeriodType.CUSTOM -> Period(PeriodType.CUSTOM, referenceDate, referenceDate)
        }

        /**
         * 全期間（[PeriodType.ALL]）を表す[Period]を返す。[earliestDate]/[latestDate]はDBに実在する最古日・最新日
         * （[com.nagamaki0311.timeliner.store.TimelineRepository.queryDateRange]）。[of]と異なり基準日単独からは
         * 計算できないため専用ファクトリとして分離した（docs/tasks.md T-017）。
         */
        fun ofAll(earliestDate: LocalDate, latestDate: LocalDate): Period = Period(PeriodType.ALL, earliestDate, latestDate)
    }
}
