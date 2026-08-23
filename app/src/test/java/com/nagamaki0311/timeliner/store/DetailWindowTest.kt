package com.nagamaki0311.timeliner.store

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DetailWindow]（詳細ウィンドウの遅延ロード、docs/tasks.md T-021）のDB非依存ロジックを検証する。 */
class DetailWindowTest {

    private val zone = ZoneOffset.UTC

    private fun millisAt(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun rangeFor_centersOnRadiusDays_andClampsToPeriodBounds() {
        val periodStart = LocalDate.of(2026, 1, 1)
        val periodEnd = LocalDate.of(2026, 12, 31)
        val dataTime = millisAt(LocalDate.of(2026, 6, 15))

        val range = DetailWindow.rangeFor(dataTime, periodStart, periodEnd, zone)

        assertEquals(LocalDate.of(2026, 6, 14), range.startDate)
        assertEquals(LocalDate.of(2026, 6, 16), range.endDate)
    }

    @Test
    fun rangeFor_nearPeriodStart_doesNotExtendBeforePeriodStartDate() {
        val periodStart = LocalDate.of(2026, 1, 1)
        val periodEnd = LocalDate.of(2026, 12, 31)
        val dataTime = millisAt(periodStart)

        val range = DetailWindow.rangeFor(dataTime, periodStart, periodEnd, zone)

        assertEquals(periodStart, range.startDate)
        assertEquals(LocalDate.of(2026, 1, 2), range.endDate)
    }

    @Test
    fun needsReload_returnsTrueWhenNoWindowLoadedYet() {
        assertTrue(DetailWindow.needsReload(null, millisAt(LocalDate.of(2026, 6, 15)), zone))
    }

    @Test
    fun needsReload_returnsFalseWhileWithinLoadedWindow() {
        val loaded = DetailWindow.Range(LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 16))
        assertFalse(DetailWindow.needsReload(loaded, millisAt(LocalDate.of(2026, 6, 15)), zone))
        // 境界（両端）も再利用対象に含む。
        assertFalse(DetailWindow.needsReload(loaded, millisAt(LocalDate.of(2026, 6, 14)), zone))
        assertFalse(DetailWindow.needsReload(loaded, millisAt(LocalDate.of(2026, 6, 16)), zone))
    }

    @Test
    fun needsReload_returnsTrueWhenPlaybackMovesOutsideLoadedWindow() {
        val loaded = DetailWindow.Range(LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 16))
        assertTrue(DetailWindow.needsReload(loaded, millisAt(LocalDate.of(2026, 6, 13)), zone))
        assertTrue(DetailWindow.needsReload(loaded, millisAt(LocalDate.of(2026, 6, 17)), zone))
    }

    @Test
    fun merge_replacesOverlappingBasePointsWithDetailPoints_keepingTimeOrder() {
        // base: 概観点列（粗い間引き）。2500は詳細ウィンドウ範囲(2100〜2900)に含まれるため置き換えられる想定。
        val base = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.0, 2.0, 2.5, 3.0, 4.0, 5.0),
            longitudes = doubleArrayOf(10.0, 20.0, 25.0, 30.0, 40.0, 50.0),
            timestampsMillis = longArrayOf(1_000L, 2_000L, 2_500L, 3_000L, 4_000L, 5_000L)
        )
        // detail: 2100〜2900の時間帯を全解像度で置き換える
        val detail = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(2.1, 2.4, 2.9),
            longitudes = doubleArrayOf(21.0, 24.0, 29.0),
            timestampsMillis = longArrayOf(2_100L, 2_400L, 2_900L)
        )

        val merged = DetailWindow.merge(base, detail)

        assertEquals(
            "base[2500]はdetailウィンドウ範囲(2100〜2900)内のため置き換えられ、それ以外のbase点は保持される",
            listOf(1_000L, 2_000L, 2_100L, 2_400L, 2_900L, 3_000L, 4_000L, 5_000L),
            merged.timestampsMillis.toList()
        )
        assertEquals(listOf(1.0, 2.0, 2.1, 2.4, 2.9, 3.0, 4.0, 5.0), merged.latitudes.toList())
        assertEquals(listOf(10.0, 20.0, 21.0, 24.0, 29.0, 30.0, 40.0, 50.0), merged.longitudes.toList())
    }

    /**
     * detail範囲の両端がbaseの既存点と時刻完全一致するケース（docs/decisions.md D-027決定1、Reviewer提案の境界例）。
     * `lowerBound`/`upperBound`は半開区間的な二分探索のため、一致点をdetail側だけが保持し、base側では
     * 重複も欠落もなく置き換わることを検証する。
     */
    @Test
    fun merge_detailBoundsExactlyMatchExistingBasePoints_replacesWithoutDuplicationOrGap() {
        val base = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            longitudes = doubleArrayOf(10.0, 20.0, 30.0, 40.0),
            timestampsMillis = longArrayOf(1_000L, 2_000L, 3_000L, 4_000L)
        )
        // detailの開始時刻(2000)・終了時刻(3000)がどちらもbaseの既存点と完全一致する。
        val detail = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(2.0, 2.5, 3.0),
            longitudes = doubleArrayOf(20.0, 25.0, 30.0),
            timestampsMillis = longArrayOf(2_000L, 2_500L, 3_000L)
        )

        val merged = DetailWindow.merge(base, detail)

        assertEquals(
            "base[2000]・base[3000]はdetailと完全一致するためdetail側のみが残り、重複も欠落も無いはず",
            listOf(1_000L, 2_000L, 2_500L, 3_000L, 4_000L),
            merged.timestampsMillis.toList()
        )
        assertEquals(listOf(1.0, 2.0, 2.5, 3.0, 4.0), merged.latitudes.toList())
        assertEquals(listOf(10.0, 20.0, 25.0, 30.0, 40.0), merged.longitudes.toList())
    }

    @Test
    fun merge_detailCoveringEntireBaseRange_replacesAllBasePoints() {
        val base = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.0, 2.0),
            longitudes = doubleArrayOf(10.0, 20.0),
            timestampsMillis = longArrayOf(1_000L, 2_000L)
        )
        val detail = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.1, 1.5, 1.9),
            longitudes = doubleArrayOf(11.0, 15.0, 19.0),
            timestampsMillis = longArrayOf(500L, 1_500L, 2_500L)
        )

        val merged = DetailWindow.merge(base, detail)

        assertEquals(listOf(500L, 1_500L, 2_500L), merged.timestampsMillis.toList())
    }

    @Test
    fun merge_emptyDetail_returnsBaseUnchanged() {
        val base = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.0),
            longitudes = doubleArrayOf(10.0),
            timestampsMillis = longArrayOf(1_000L)
        )
        val detail = PointBlobCodec.DecodedPoints(DoubleArray(0), DoubleArray(0), LongArray(0))

        val merged = DetailWindow.merge(base, detail)

        assertEquals(base, merged)
    }

    @Test
    fun merge_emptyBase_returnsDetailUnchanged() {
        val base = PointBlobCodec.DecodedPoints(DoubleArray(0), DoubleArray(0), LongArray(0))
        val detail = PointBlobCodec.DecodedPoints(
            latitudes = doubleArrayOf(1.0),
            longitudes = doubleArrayOf(10.0),
            timestampsMillis = longArrayOf(1_000L)
        )

        val merged = DetailWindow.merge(base, detail)

        assertEquals(detail, merged)
    }
}
