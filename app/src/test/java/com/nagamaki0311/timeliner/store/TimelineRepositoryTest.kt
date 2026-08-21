package com.nagamaki0311.timeliner.store

import com.nagamaki0311.timeliner.model.RawTrack
import com.nagamaki0311.timeliner.process.CleanOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
     * 560日規模・130万点超の実データ相当の合成入力で、クリーニング（[com.nagamaki0311.timeliner.process.TrackCleaner]の
     * 4段パイプライン）〜日単位分割（[TimelineRepository.buildPreparedImport]内の`groupPointsByLocalDate`）の
     * フルパイプラインがクラッシュせず、点の欠落・重複が無いことを確認する（docs/decisions.md D-017・docs/tasks.md T-016）。
     * 各点の移動量・間隔は速度スパイク除去（300km/h超）・停留ジッタ抑制（15m未満かつ60秒未満）のいずれの
     * 閾値にも該当しないよう設計しており、クリーニングで1点も除去されない入力になっている
     * （[SimplifierTest][com.nagamaki0311.timeliner.process.SimplifierTest]の大規模合成データによる検証手法を踏襲）。
     */
    @Test
    fun buildPreparedImport_largeScale560DayTrack_completesWithoutCrashAndPreservesAllPoints() {
        val pointCount = 1_300_000
        val startMillis = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        val totalDurationMillis = 560L * 24 * 60 * 60 * 1000
        val stepMillis = totalDurationMillis / pointCount

        val latitudes = DoubleArray(pointCount)
        val longitudes = DoubleArray(pointCount)
        val timestamps = LongArray(pointCount)
        var lat = 35.0
        var direction = 1.0
        val amplitudeDegrees = 5.0
        for (i in 0 until pointCount) {
            // 常に約22m（0.0002度、停留ジッタの閾値15mを常に上回る）だけ移動させ続ける。
            // 緯度の有効範囲を超えないよう、振幅5度の範囲で往復させる（折り返し時も移動量は一定のまま）。
            lat += direction * 0.0002
            if (lat > 35.0 + amplitudeDegrees) direction = -1.0
            if (lat < 35.0 - amplitudeDegrees) direction = 1.0
            latitudes[i] = lat
            longitudes[i] = 139.0
            timestamps[i] = startMillis + i.toLong() * stepMillis
        }
        val track = RawTrack(latitudes, longitudes, timestamps, emptyList())

        val prepared = TimelineRepository.buildPreparedImport(track, CleanOptions()) { emptySet() }

        assertEquals(pointCount, prepared.pointCount)
        assertEquals(pointCount, prepared.dayGroups.sumOf { it.latitudes.size })
        // 開始日・終了日の実際の暦日数はテスト実行環境のタイムゾーンにより1日程度前後しうるため、
        // 560日規模であることのみを確認する（環境依存で不安定になる厳密な日数一致は避ける）。
        assertTrue("dayGroups.size=${prepared.dayGroups.size}", prepared.dayGroups.size in 555..565)
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
