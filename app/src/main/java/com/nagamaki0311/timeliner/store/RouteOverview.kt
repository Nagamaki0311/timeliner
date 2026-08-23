package com.nagamaki0311.timeliner.store

import com.nagamaki0311.timeliner.process.CleanOptions
import com.nagamaki0311.timeliner.process.GeoBounds
import com.nagamaki0311.timeliner.process.Simplifier

/**
 * 全期間を俯瞰するための軽量な点列（docs/tasks.md T-014・docs/decisions.md D-017）。
 *
 * 560日規模（数十万点）では、期間切替のたびに選択期間の全`days`行を毎回展開する
 * （旧`TimelineViewModel.mergeDayPoints`方式）とメモリ・処理時間の両面で無視できないコストがかかる。
 * `RouteOverview`は起動時（または初回のルート画面表示時）に一度だけ構築し、日ごとに小予算で
 * Douglas-Peucker簡略化（[Simplifier]、既定[OVERVIEW_POINTS_PER_DAY]点/日）した点列を結合して
 * メモリ上に保持することで、長い期間（[com.nagamaki0311.timeliner.ui.TimelineViewModel]が定義する
 * 短期間しきい値を超える期間）を選ぶたびに全解像度データを読み直さずに済むようにする。
 *
 * DBスキーマは変更しない（D-017: `TimelineDb.onUpgrade`が現状DROP TABLE実装であり、
 * スキーマ変更はユーザーの取り込み済みデータを失わせるリスクがあるため）。
 */
class RouteOverview private constructor(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val timestampsMillis: LongArray,
    /**
     * 日境界、および隣接点間の経過時間が[GAP_BREAK_MILLIS]を超える箇所の開始インデックス
     * （昇順・重複なし、先頭の0を含む）。将来のポリライン分断描画（T-020）で使う想定で、
     * 本タスク（T-014）では算出・保持のみ行う。
     */
    val breakIndices: IntArray,
    /** 日付昇順の日別サマリ。[DaySummary.overviewRange]は本インスタンスの配列内でのその日の範囲。 */
    val days: List<DaySummary>,
    /** 全期間を包含するbbox。データが無ければ`null`。 */
    val bounds: GeoBounds.Bounds?,
    val earliestDate: String?,
    val latestDate: String?
) {
    val pointCount: Int get() = latitudes.size

    /** `days`テーブル1行分のサマリ。点数・距離は元データ（DP適用前）の値を保持する。 */
    data class DaySummary(
        val date: String,
        val startMillis: Long,
        val endMillis: Long,
        val pointCount: Int,
        val distanceMeters: Double,
        /** その日の全解像度点列から求めたbbox（DP適用後の点列からではなく、元データから求めた方が正確なため）。 */
        val bounds: GeoBounds.Bounds,
        /** この日の点が[RouteOverview]の配列内で占める範囲（両端含む）。 */
        val overviewRange: IntRange
    )

    /**
     * [startMillis]〜[endMillis]（両端含む）に対応する配列内の範囲を二分探索（O(log n)）で求める。
     * [timestampsMillis]は日付昇順で結合しているため単調増加であることを前提とする。
     * 範囲内に点が1つも無い場合は`null`を返す。
     */
    fun sliceRange(startMillis: Long, endMillis: Long): IntRange? {
        if (pointCount == 0 || startMillis > endMillis) return null
        val lower = lowerBoundIndex(startMillis)
        val upper = upperBoundIndex(endMillis)
        if (lower >= pointCount || lower > upper) return null
        return lower..upper
    }

    /** [days]のうち[startDate]〜[endDate]（両端含む、`YYYY-MM-DD`の文字列比較で日付順と一致）に含まれる日のbboxを結合する。 */
    fun boundsForDateRange(startDate: String, endDate: String): GeoBounds.Bounds? {
        var result: GeoBounds.Bounds? = null
        for (day in days) {
            if (day.date < startDate || day.date > endDate) continue
            result = if (result == null) day.bounds else mergeBounds(result, day.bounds)
        }
        return result
    }

    /** [timestampsMillis]内で値が[target]以上となる最小インデックス（無ければ[pointCount]）。 */
    private fun lowerBoundIndex(target: Long): Int {
        var lo = 0
        var hi = pointCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestampsMillis[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [timestampsMillis]内で値が[target]以下となる最大インデックス（無ければ-1）。 */
    private fun upperBoundIndex(target: Long): Int {
        var lo = 0
        var hi = pointCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestampsMillis[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    companion object {
        /** 日ごとにDPで残す点数の既定上限（docs/decisions.md D-017決定3）。将来調整可能なよう定数として公開する。 */
        const val OVERVIEW_POINTS_PER_DAY = 128

        /** 日境界に加え、この時間を超える隣接点間のギャップも[breakIndices]に含める。[TrackCleaner]の`segmentGapMillis`既定値と同じ値を使う。 */
        val GAP_BREAK_MILLIS = CleanOptions().segmentGapMillis

        private val EMPTY = RouteOverview(
            DoubleArray(0), DoubleArray(0), LongArray(0), IntArray(0), emptyList(), null, null, null
        )

        /** [repository]の全`days`行から[RouteOverview]を構築する。1件ずつストリーミング処理し、生データを一括保持しない。 */
        fun build(repository: TimelineRepository, pointsPerDay: Int = OVERVIEW_POINTS_PER_DAY): RouteOverview {
            val range = repository.queryDateRange() ?: return EMPTY
            val builder = Builder(pointsPerDay)
            repository.queryDaysStreaming(range.first, range.second) { day -> builder.addDay(day) }
            return builder.build()
        }

        /**
         * DBに依存しない本体ロジック（日ごとのDP適用・結合・bbox/breakIndices算出）を、
         * [TimelineRepository]/[TimelineDb]（Android API依存）を介さずJVM単体テストできるようにする
         * （[TimelineRepository.buildPreparedImport]と同じ理由・同じパターン）。
         */
        internal fun buildFrom(
            days: Iterable<TimelineRepository.DayRecord>,
            pointsPerDay: Int = OVERVIEW_POINTS_PER_DAY
        ): RouteOverview {
            val builder = Builder(pointsPerDay)
            for (day in days) builder.addDay(day)
            return builder.build()
        }

        private fun mergeBounds(a: GeoBounds.Bounds, b: GeoBounds.Bounds): GeoBounds.Bounds = GeoBounds.Bounds(
            minLatitude = minOf(a.minLatitude, b.minLatitude),
            maxLatitude = maxOf(a.maxLatitude, b.maxLatitude),
            minLongitude = minOf(a.minLongitude, b.minLongitude),
            maxLongitude = maxOf(a.maxLongitude, b.maxLongitude)
        )
    }

    /** [build]/[buildFrom]の内部アキュムレータ。日ごとのDP適用結果（小容量）のみをリストに溜め、最後に1回だけ結合する。 */
    private class Builder(private val pointsPerDay: Int) {
        private val latChunks = mutableListOf<DoubleArray>()
        private val lonChunks = mutableListOf<DoubleArray>()
        private val timeChunks = mutableListOf<LongArray>()
        private val summaries = mutableListOf<DaySummary>()
        private var overallBounds: GeoBounds.Bounds? = null
        private var totalPoints = 0

        fun addDay(day: TimelineRepository.DayRecord) {
            if (day.pointCount == 0) return
            val points = day.points
            val keptIndices = Simplifier.simplify(
                points.latitudes, points.longitudes, points.timestampsMillis,
                epsilonMeters = 0.0,
                maxPointCount = pointsPerDay
            )
            val lat = DoubleArray(keptIndices.size) { points.latitudes[keptIndices[it]] }
            val lon = DoubleArray(keptIndices.size) { points.longitudes[keptIndices[it]] }
            val time = LongArray(keptIndices.size) { points.timestampsMillis[keptIndices[it]] }

            val dayBounds = GeoBounds.compute(points.latitudes, points.longitudes)
            overallBounds = overallBounds?.let { mergeBounds(it, dayBounds) } ?: dayBounds

            latChunks.add(lat)
            lonChunks.add(lon)
            timeChunks.add(time)
            summaries.add(
                DaySummary(
                    date = day.date,
                    startMillis = day.startMillis,
                    endMillis = day.endMillis,
                    pointCount = day.pointCount,
                    distanceMeters = day.distanceMeters,
                    bounds = dayBounds,
                    overviewRange = totalPoints until (totalPoints + keptIndices.size)
                )
            )
            totalPoints += keptIndices.size
        }

        fun build(): RouteOverview {
            if (totalPoints == 0) return EMPTY

            val latitudes = DoubleArray(totalPoints)
            val longitudes = DoubleArray(totalPoints)
            val timestampsMillis = LongArray(totalPoints)
            var offset = 0
            for (i in latChunks.indices) {
                val count = latChunks[i].size
                System.arraycopy(latChunks[i], 0, latitudes, offset, count)
                System.arraycopy(lonChunks[i], 0, longitudes, offset, count)
                System.arraycopy(timeChunks[i], 0, timestampsMillis, offset, count)
                offset += count
            }

            val dayStartIndices = IntArray(summaries.size) { summaries[it].overviewRange.first }
            val breakIndices = computeBreakIndices(timestampsMillis, dayStartIndices)

            return RouteOverview(
                latitudes, longitudes, timestampsMillis, breakIndices,
                summaries.toList(), overallBounds,
                summaries.first().date, summaries.last().date
            )
        }

        /** 日境界（[dayStartIndices]）と、隣接点間の経過時間が[GAP_BREAK_MILLIS]を超える箇所の両方を[breakIndices]として返す（昇順・重複なし）。 */
        private fun computeBreakIndices(timestampsMillis: LongArray, dayStartIndices: IntArray): IntArray {
            val breaks = sortedSetOf(0)
            for (idx in dayStartIndices) breaks.add(idx)
            for (i in 1 until timestampsMillis.size) {
                if (timestampsMillis[i] - timestampsMillis[i - 1] > GAP_BREAK_MILLIS) breaks.add(i)
            }
            return breaks.toIntArray()
        }
    }
}
