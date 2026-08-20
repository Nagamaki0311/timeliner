package com.nagamaki0311.timeliner.store

import com.nagamaki0311.timeliner.model.RawTrack
import com.nagamaki0311.timeliner.process.CleanOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * [TimelineRepository]は`android.database.sqlite.SQLiteDatabase`/[TimelineDb]
 * （`SQLiteOpenHelper`、Android API）に依存するため、`prepareImport`/`commitImport`の
 * 実DB書き込み経路自体はJVM単体テストの対象外（[TimelineDb]はコンストラクタ自体が
 * Android APIを呼ぶため、テストからインスタンス化すらできない。D-003・T-005レビューと同種の制約）。
 *
 * ただし「既存日付の検出手段（DB問い合わせ）」をラムダとして注入できる
 * `TimelineRepository.buildPreparedImport`（companion object、internal）を使うことで、
 * DBに依存しない部分（クリーニング・日付分割・上書き日数の算出＝docs/decisions.md D-006決定1の
 * 中核ロジック）は、[TimelineRepository]インスタンスを一切生成せずに、実際の本番コードパスのまま検証できる。
 */
class TimelineRepositoryTest {

    @Test
    fun buildPreparedImport_noExistingDates_overwriteDayCountIsZero() {
        val track = twoDayTrack()

        val prepared = TimelineRepository.buildPreparedImport(track, CleanOptions()) { emptySet() }

        assertEquals(0, prepared.overwriteDayCount)
        assertEquals(2, prepared.dayGroups.size)
        assertEquals(track.pointCount, prepared.pointCount)
    }

    @Test
    fun buildPreparedImport_oneExistingDate_overwriteDayCountIsOne() {
        val track = twoDayTrack()
        val firstDate = localDateOf(track.timestampsMillis.first())

        val prepared = TimelineRepository.buildPreparedImport(track, CleanOptions()) { setOf(firstDate) }

        assertEquals(1, prepared.overwriteDayCount)
    }

    @Test
    fun buildPreparedImport_allDatesExisting_overwriteDayCountMatchesDayGroupCount() {
        val track = twoDayTrack()

        val prepared = TimelineRepository.buildPreparedImport(track, CleanOptions()) { dates -> dates.toSet() }

        assertEquals(prepared.dayGroups.size, prepared.overwriteDayCount)
        assertEquals(2, prepared.overwriteDayCount)
    }

    @Test
    fun buildPreparedImport_existingDateOutsideImportRange_isIgnored() {
        val track = twoDayTrack()

        val prepared = TimelineRepository.buildPreparedImport(track, CleanOptions()) { setOf("1999-01-01") }

        assertEquals(0, prepared.overwriteDayCount)
    }

    /**
     * インポート対象日を2日に分ける点列。日をまたぐ2点間の速度・距離が
     * [com.nagamaki0311.timeliner.process.TrackCleaner]の除去閾値（速度300km/h、
     * 停留15m/60秒）を超えないよう、緯度を小刻みに変化させつつ日を3日離す
     * （タイムゾーンオフセット[-12,+14]のどれでも日付が確実に分かれるようにするため）。
     */
    private fun twoDayTrack(): RawTrack {
        val day1Start = Instant.parse("2026-01-01T03:00:00Z").toEpochMilli()
        val latitudes = doubleArrayOf(35.00, 35.01, 35.10, 35.11)
        val longitudes = doubleArrayOf(139.00, 139.00, 139.00, 139.00)
        val timestamps = longArrayOf(
            day1Start,
            day1Start + 2 * 60 * 60 * 1000L,
            day1Start + 3L * 24 * 60 * 60 * 1000L,
            day1Start + 3L * 24 * 60 * 60 * 1000L + 2 * 60 * 60 * 1000L
        )
        return RawTrack(latitudes, longitudes, timestamps, emptyList())
    }

    private fun localDateOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}
