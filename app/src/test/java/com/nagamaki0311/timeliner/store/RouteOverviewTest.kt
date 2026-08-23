package com.nagamaki0311.timeliner.store

import com.nagamaki0311.timeliner.process.GeoBounds
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteOverview]の構築ロジック（DBに依存しない[RouteOverview.buildFrom]経由）の単体テスト（docs/tasks.md T-014）。
 * [TimelineRepository]/[TimelineDb]（Android API依存）を介さず、[TimelineRepository.DayRecord]を直接組み立てて検証する。
 */
class RouteOverviewTest {

    @Test
    fun buildFrom_emptyInput_returnsEmptyOverview() {
        val overview = RouteOverview.buildFrom(emptyList())

        assertEquals(0, overview.pointCount)
        assertNull(overview.bounds)
        assertNull(overview.earliestDate)
        assertNull(overview.latestDate)
        assertTrue(overview.breakIndices.isEmpty())
        assertTrue(overview.days.isEmpty())
    }

    @Test
    fun buildFrom_multipleDaysWithLargeGapBetween_recordsBreakAtEachDayBoundary() {
        val days = listOf(
            randomWalkDay("2026-01-01", dayStartMillis("2026-01-01"), pointCount = 50, intervalMillis = 60_000L, seed = 1),
            randomWalkDay("2026-01-02", dayStartMillis("2026-01-02"), pointCount = 50, intervalMillis = 60_000L, seed = 2),
            randomWalkDay("2026-01-03", dayStartMillis("2026-01-03"), pointCount = 50, intervalMillis = 60_000L, seed = 3)
        )

        val overview = RouteOverview.buildFrom(days, pointsPerDay = 128)

        assertEquals(3, overview.days.size)
        val breakSet = overview.breakIndices.toSet()
        for (day in overview.days) {
            assertTrue(
                "日境界(${day.date}, index=${day.overviewRange.first})がbreakIndicesに含まれていません",
                day.overviewRange.first in breakSet
            )
        }
    }

    @Test
    fun buildFrom_dayBoundaryWithSmallGap_stillRecordsBreak() {
        // 日1の最終点(23:58)→日2の最初の点(00:02)は4分しか離れておらず、GAP_BREAK_MILLIS(6時間)未満だが、
        // 日境界そのものが常にbreakIndicesに含まれることを確認する。
        val day1End = dayStartMillis("2026-01-01") + 23L * 60 * 60 * 1000 + 58L * 60 * 1000
        val day1 = randomWalkDay("2026-01-01", day1End - 10 * 60_000L, pointCount = 10, intervalMillis = 60_000L, seed = 10)
        val day2Start = dayStartMillis("2026-01-02") + 2L * 60 * 1000
        val day2 = randomWalkDay("2026-01-02", day2Start, pointCount = 10, intervalMillis = 60_000L, seed = 11)

        val overview = RouteOverview.buildFrom(listOf(day1, day2), pointsPerDay = 128)

        val day2Summary = overview.days[1]
        assertTrue(day2Summary.overviewRange.first in overview.breakIndices.toSet())
    }

    @Test
    fun buildFrom_gapWithinSingleDay_recordsBreakAtGapPosition() {
        val day = randomWalkDay(
            "2026-01-01", dayStartMillis("2026-01-01"), pointCount = 50, intervalMillis = 60_000L, seed = 20,
            gapAfterIndex = 20, gapMillis = 7L * 60 * 60 * 1000
        )

        val overview = RouteOverview.buildFrom(listOf(day), pointsPerDay = 128)

        var gapIndex = -1
        for (i in 1 until overview.pointCount) {
            if (overview.timestampsMillis[i] - overview.timestampsMillis[i - 1] > RouteOverview.GAP_BREAK_MILLIS) {
                gapIndex = i
                break
            }
        }
        assertTrue("6時間超ギャップが概観点列上に見つかりません", gapIndex >= 0)
        assertTrue(gapIndex in overview.breakIndices.toSet())
    }

    @Test
    fun buildFrom_perDayPointCountIsCappedAtPointsPerDay() {
        val pointsPerDay = 50
        val days = (1..4).map { dayIndex ->
            randomWalkDay(
                "2026-01-0$dayIndex", dayStartMillis("2026-01-0$dayIndex"),
                pointCount = 500, intervalMillis = 5_000L, seed = dayIndex.toLong()
            )
        }

        val overview = RouteOverview.buildFrom(days, pointsPerDay = pointsPerDay)

        assertTrue(overview.pointCount <= days.size * pointsPerDay)
        for (day in overview.days) {
            val daySize = day.overviewRange.last - day.overviewRange.first + 1
            assertTrue("${day.date}の点数($daySize)が上限($pointsPerDay)を超えています", daySize <= pointsPerDay)
        }
    }

    @Test
    fun buildFrom_boundsUsesFullResolutionExtremesNotSimplifiedOnes() {
        // DPで間引かれうる中間点に極値を混ぜ込み、概観のbboxが簡略化後ではなく元データ全体から
        // 正しく求まっている(D-017のfitBounds再利用要件)ことを確認する。
        val day = randomWalkDay("2026-01-01", dayStartMillis("2026-01-01"), pointCount = 200, intervalMillis = 10_000L, seed = 30)
        val extremeLat = 89.0
        val extremeLon = -179.0
        day.points.latitudes[100] = extremeLat
        day.points.longitudes[100] = extremeLon

        val overview = RouteOverview.buildFrom(listOf(day), pointsPerDay = 20)

        assertNotNull(overview.bounds)
        assertEquals(extremeLat, overview.bounds!!.maxLatitude, 1e-9)
        assertEquals(extremeLon, overview.bounds!!.minLongitude, 1e-9)
    }

    @Test
    fun sliceRange_matchesDaySummaryRangeForThatDaysOwnTimestampBounds() {
        val days = (1..5).map { dayIndex ->
            randomWalkDay(
                "2026-01-0$dayIndex", dayStartMillis("2026-01-0$dayIndex"),
                pointCount = 30, intervalMillis = 60_000L, seed = dayIndex.toLong()
            )
        }
        val overview = RouteOverview.buildFrom(days, pointsPerDay = 128)
        val targetDay = overview.days[2] // 2026-01-03

        val range = overview.sliceRange(targetDay.startMillis, targetDay.endMillis)

        assertNotNull(range)
        assertEquals(targetDay.overviewRange.first, range!!.first)
        assertEquals(targetDay.overviewRange.last, range.last)
    }

    @Test
    fun sliceRange_outOfDataRange_returnsNull() {
        val day = randomWalkDay("2026-01-01", dayStartMillis("2026-01-01"), pointCount = 10, intervalMillis = 60_000L, seed = 40)
        val overview = RouteOverview.buildFrom(listOf(day), pointsPerDay = 128)

        val range = overview.sliceRange(dayStartMillis("2020-01-01"), dayStartMillis("2020-01-02"))

        assertNull(range)
    }

    @Test
    fun boundsForDateRange_onlyMergesDaysWithinRange() {
        val days = (1..3).map { dayIndex ->
            randomWalkDay(
                "2026-01-0$dayIndex", dayStartMillis("2026-01-0$dayIndex"),
                pointCount = 20, intervalMillis = 60_000L, seed = dayIndex.toLong() + 100
            )
        }
        val overview = RouteOverview.buildFrom(days, pointsPerDay = 128)

        val singleDayBounds = overview.boundsForDateRange("2026-01-02", "2026-01-02")
        val allDaysBounds = overview.boundsForDateRange("2026-01-01", "2026-01-03")

        assertNotNull(singleDayBounds)
        assertEquals(overview.days[1].bounds, singleDayBounds)
        assertNotNull(allDaysBounds)
        assertEquals(overview.bounds, allDaysBounds)
    }

    // ---- テスト用データ生成 ----

    private fun dayStartMillis(date: String): Long {
        val (year, month, dayOfMonth) = date.split("-").map { it.toInt() }
        return java.time.LocalDate.of(year, month, dayOfMonth)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * 決定的なランダムウォーク（[seed]固定）で日ごとの合成[TimelineRepository.DayRecord]を作る。
     * [gapAfterIndex]を指定すると、その直後の点との間隔だけ[gapMillis]に差し替える
     * （日内の長時間欠損を再現するため）。
     */
    private fun randomWalkDay(
        date: String,
        startMillis: Long,
        pointCount: Int,
        intervalMillis: Long,
        seed: Long,
        gapAfterIndex: Int? = null,
        gapMillis: Long = 0L
    ): TimelineRepository.DayRecord {
        val random = Random(seed)
        val latitudes = DoubleArray(pointCount)
        val longitudes = DoubleArray(pointCount)
        val timestamps = LongArray(pointCount)
        var lat = 35.0
        var lon = 139.0
        var time = startMillis
        for (i in 0 until pointCount) {
            lat += (random.nextDouble() - 0.5) * 0.0005
            lon += (random.nextDouble() - 0.5) * 0.0005
            latitudes[i] = lat
            longitudes[i] = lon
            timestamps[i] = time
            time += if (gapAfterIndex == i) gapMillis else intervalMillis
        }
        return TimelineRepository.DayRecord(
            date = date,
            startMillis = timestamps.first(),
            endMillis = timestamps.last(),
            pointCount = pointCount,
            distanceMeters = 0.0,
            points = PointBlobCodec.DecodedPoints(latitudes, longitudes, timestamps)
        )
    }
}
