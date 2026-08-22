package com.nagamaki0311.timeliner.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 長期間（[RouteOverview]経由）選択時、再生位置近傍の全解像度データを遅延ロードして描画に反映する
 * 「詳細ウィンドウ」のDB非依存ロジック（docs/tasks.md T-021・docs/decisions.md D-017）。
 *
 * [RouteOverview]は日ごとに最大[RouteOverview.OVERVIEW_POINTS_PER_DAY]点へ簡略化済みのため、
 * ズームインしても実際に記録されたGPS軌跡までは再現できない（T-020完了時点の既知の制約）。
 * 再生中の現在データ時刻の近傍だけ`days`テーブルから全解像度で読み直し、概観点列の該当区間と
 * 差し替えることで、その部分だけ高精細に描画できるようにする。
 *
 * [com.nagamaki0311.timeliner.ui.TimelineViewModel]をJVM単体テストからインスタンス化できない制約
 * （docs/decisions.md D-020）があるため、ウィンドウ範囲の計算・再ロード要否判定・点列の結合という
 * 純粋なロジックをこのオブジェクトへ切り出し、[DetailWindowTest]で検証する。
 */
object DetailWindow {
    /** ウィンドウ半径（日）。現在のローカル日付の前後この日数分を1回のロードでまとめて取得する。 */
    const val RADIUS_DAYS = 1L

    /** 再ロードが必要と判定してから実際にロードを開始するまでのデバウンス間隔（ミリ秒）。 */
    const val DEBOUNCE_MILLIS = 300L

    /** ロード対象の日付範囲（両端含む）。 */
    data class Range(val startDate: LocalDate, val endDate: LocalDate)

    /**
     * [dataTimeMillis]（現在の再生データ時刻）を中心に、[RADIUS_DAYS]日分の前後を含むロード対象範囲を返す。
     * [periodStartDate]/[periodEndDate]（選択期間の境界）を超えないようクランプする。
     */
    fun rangeFor(
        dataTimeMillis: Long,
        periodStartDate: LocalDate,
        periodEndDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): Range {
        val date = localDateOf(dataTimeMillis, zone)
        val start = maxOf(date.minusDays(RADIUS_DAYS), periodStartDate)
        val end = minOf(date.plusDays(RADIUS_DAYS), periodEndDate)
        return Range(start, end)
    }

    /**
     * [dataTimeMillis]が現在ロード済みの[loaded]範囲に収まっているかを判定し、収まっていなければ
     * （または[loaded]が`null`＝未ロードなら）`true`（再ロードが必要）を返す。
     * 範囲内にとどまっている間は`false`を返し、キャッシュ（既にロード済みの詳細ウィンドウ）を再利用する。
     */
    fun needsReload(loaded: Range?, dataTimeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (loaded == null) return true
        val date = localDateOf(dataTimeMillis, zone)
        return date < loaded.startDate || date > loaded.endDate
    }

    /**
     * [base]（概観点列、または選択期間分の概観切り出し）のうち[detail]（全解像度の詳細ウィンドウ）の
     * 時刻範囲に含まれる部分を[detail]で置き換えた点列を返す。[base]・[detail]とも時刻昇順を前提とする。
     * [detail]が空なら[base]をそのまま返す。
     */
    fun merge(base: PointBlobCodec.DecodedPoints, detail: PointBlobCodec.DecodedPoints): PointBlobCodec.DecodedPoints {
        if (detail.timestampsMillis.isEmpty()) return base
        if (base.timestampsMillis.isEmpty()) return detail

        val windowStartMillis = detail.timestampsMillis.first()
        val windowEndMillis = detail.timestampsMillis.last()
        val beforeCount = lowerBound(base.timestampsMillis, windowStartMillis)
        val afterStart = upperBound(base.timestampsMillis, windowEndMillis)
        val afterCount = base.timestampsMillis.size - afterStart
        val detailCount = detail.timestampsMillis.size
        val totalCount = beforeCount + detailCount + afterCount

        val latitudes = DoubleArray(totalCount)
        val longitudes = DoubleArray(totalCount)
        val timestampsMillis = LongArray(totalCount)

        System.arraycopy(base.latitudes, 0, latitudes, 0, beforeCount)
        System.arraycopy(base.longitudes, 0, longitudes, 0, beforeCount)
        System.arraycopy(base.timestampsMillis, 0, timestampsMillis, 0, beforeCount)

        System.arraycopy(detail.latitudes, 0, latitudes, beforeCount, detailCount)
        System.arraycopy(detail.longitudes, 0, longitudes, beforeCount, detailCount)
        System.arraycopy(detail.timestampsMillis, 0, timestampsMillis, beforeCount, detailCount)

        val afterOffset = beforeCount + detailCount
        System.arraycopy(base.latitudes, afterStart, latitudes, afterOffset, afterCount)
        System.arraycopy(base.longitudes, afterStart, longitudes, afterOffset, afterCount)
        System.arraycopy(base.timestampsMillis, afterStart, timestampsMillis, afterOffset, afterCount)

        return PointBlobCodec.DecodedPoints(latitudes, longitudes, timestampsMillis)
    }

    private fun localDateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** [arr]（昇順）内で値が[target]以上となる最小インデックス（無ければ[arr].size）。 */
    private fun lowerBound(arr: LongArray, target: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [arr]（昇順）内で値が[target]より大きくなる最小インデックス（無ければ[arr].size）。 */
    private fun upperBound(arr: LongArray, target: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
