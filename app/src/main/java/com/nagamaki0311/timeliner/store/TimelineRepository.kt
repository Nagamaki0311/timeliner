package com.nagamaki0311.timeliner.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nagamaki0311.timeliner.model.RawTrack
import com.nagamaki0311.timeliner.model.TimelineSegment
import com.nagamaki0311.timeliner.model.TimelineSegmentType
import com.nagamaki0311.timeliner.process.CleanOptions
import com.nagamaki0311.timeliner.process.CleanedTrack
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.process.TrackCleaner
import java.time.Instant
import java.time.ZoneId

/**
 * インポート（[RawTrack] → [TrackCleaner] → `days`/`segments`書き込み）と、
 * 期間指定での読み出しを担う（docs/tasks.md T-005・docs/decisions.md D-002）。
 *
 * 日付の決定: エクスポート側にタイムゾーンオフセットの情報が無いため（[RawTrack]/[TimelineSegment]は
 * いずれもエポックミリ秒のみを持つ）、常に端末タイムゾーン（[ZoneId.systemDefault]）にフォールバックする。
 * 1つの連続した点列が日付をまたぐ場合は、日付ごとに点を振り分けて複数の`days`行に分割する。
 */
class TimelineRepository(private val dbHelper: TimelineDb) {

    /** インポート結果のサマリ。UI表示用。 */
    data class ImportResult(
        val dayCount: Int,
        val pointCount: Int,
        val segmentCount: Int,
        val earliestDate: String?,
        val latestDate: String?
    )

    /** `segments`テーブルの1行に対応する読み出し専用モデル（テーブルスキーマに存在するフィールドのみ持つ）。 */
    data class SegmentRecord(
        val id: Long,
        val date: String,
        val startMillis: Long,
        val endMillis: Long,
        val kind: TimelineSegmentType,
        val placeName: String?,
        val activityType: String?,
        val distanceMeters: Double?
    )

    /** `days`テーブルの1行に対応する読み出し専用モデル。[points]はデコード済みの点列。 */
    data class DayRecord(
        val date: String,
        val startMillis: Long,
        val endMillis: Long,
        val pointCount: Int,
        val distanceMeters: Double,
        val points: PointBlobCodec.DecodedPoints
    )

    /**
     * [prepareImport]の結果。書き込み対象の日単位データ・セグメント一覧と、
     * 書き込みによって上書きされる既存`days`行の日数（[overwriteDayCount]）を保持する。
     * [overwriteDayCount]が0より大きい場合、[commitImport]の実行前に呼び出し側（UI）が
     * ユーザーへの確認を挟むことを想定する（docs/decisions.md D-006）。
     */
    class PreparedImport internal constructor(
        internal val dayGroups: List<DayGroup>,
        internal val segments: List<TimelineSegment>,
        val pointCount: Int,
        val overwriteDayCount: Int
    )

    /**
     * [track]をクリーニングし、日単位に分割した上で、書き込み対象日付のうち
     * 既存`days`行を持つ日数（[PreparedImport.overwriteDayCount]）を検出する。
     * この時点ではDBへの書き込みは行わない（[commitImport]を別途呼ぶこと）。
     */
    fun prepareImport(track: RawTrack, options: CleanOptions = CleanOptions()): PreparedImport =
        buildPreparedImport(track, options) { dates -> existingDates(dbHelper.readableDatabase, dates) }

    /**
     * [prepareImport]で準備した内容を実際に`days`/`segments`へ書き込む。
     * 同じ日付・同じセグメント由来の既存行は上書きする（再インポートに対する冪等性）。
     * トランザクション内で実行するため、大量`INSERT`でも書き込みが高速。
     */
    fun commitImport(prepared: PreparedImport): ImportResult {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (group in prepared.dayGroups) {
                writeDayRow(db, group)
            }
            val segmentDates = prepared.segments.map { localDateOf(it.startTimeMillis) }.toSet()
            if (segmentDates.isNotEmpty()) {
                for (chunk in segmentDates.chunked(SQLITE_IN_CLAUSE_CHUNK_SIZE)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.delete(TimelineDb.TABLE_SEGMENTS, "date IN ($placeholders)", chunk.toTypedArray())
                }
            }
            for (segment in prepared.segments) {
                writeSegmentRow(db, segment)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportResult(
            dayCount = prepared.dayGroups.size,
            pointCount = prepared.pointCount,
            segmentCount = prepared.segments.size,
            earliestDate = prepared.dayGroups.minOfOrNull { it.date },
            latestDate = prepared.dayGroups.maxOfOrNull { it.date }
        )
    }

    /** 指定した日付範囲（両端含む、`YYYY-MM-DD`）に含まれる`days`行を日付昇順で返す（詳細ウィンドウ用途、docs/tasks.md T-014）。 */
    fun queryDays(startDate: String, endDate: String): List<DayRecord> {
        val records = mutableListOf<DayRecord>()
        queryDaysStreaming(startDate, endDate) { records.add(it) }
        return records
    }

    /**
     * 指定した日付範囲（両端含む、`YYYY-MM-DD`）に含まれる`days`行を日付昇順で読み、1件ずつ[onDay]へ渡す。
     * [queryDays]と異なりリストへ溜め込まないため、[RouteOverview]構築のように560日規模の全行を
     * 走査する場合でも、同時に保持する`DayRecord`は常に高々1件で済む（docs/tasks.md T-014）。
     */
    fun queryDaysStreaming(startDate: String, endDate: String, onDay: (DayRecord) -> Unit) {
        val db = dbHelper.readableDatabase
        db.query(
            TimelineDb.TABLE_DAYS,
            arrayOf("date", "start_millis", "end_millis", "point_count", "distance_meters", "points"),
            "date BETWEEN ? AND ?",
            arrayOf(startDate, endDate),
            null,
            null,
            "date ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                onDay(
                    DayRecord(
                        date = cursor.getString(0),
                        startMillis = cursor.getLong(1),
                        endMillis = cursor.getLong(2),
                        pointCount = cursor.getInt(3),
                        distanceMeters = cursor.getDouble(4),
                        points = PointBlobCodec.decode(cursor.getBlob(5))
                    )
                )
            }
        }
    }

    /** `days`テーブルの`date`列のみを日付昇順で返す軽量クエリ（BLOBは読まない、docs/tasks.md T-014）。 */
    fun queryDayDates(): List<String> {
        val db = dbHelper.readableDatabase
        val dates = mutableListOf<String>()
        db.query(TimelineDb.TABLE_DAYS, arrayOf("date"), null, null, null, null, "date ASC").use { cursor ->
            while (cursor.moveToNext()) {
                dates.add(cursor.getString(0))
            }
        }
        return dates
    }

    /** `days`テーブルに存在する最古日〜最新日を返す。データが1件も無ければ`null`（docs/tasks.md T-014）。 */
    fun queryDateRange(): Pair<String, String>? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT MIN(date), MAX(date) FROM ${TimelineDb.TABLE_DAYS}", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) return null
            return Pair(cursor.getString(0), cursor.getString(1))
        }
    }

    /** 指定した日付範囲（両端含む、`YYYY-MM-DD`）に含まれる`segments`行を開始時刻昇順で返す。 */
    fun querySegments(startDate: String, endDate: String): List<SegmentRecord> {
        val db = dbHelper.readableDatabase
        val records = mutableListOf<SegmentRecord>()
        db.query(
            TimelineDb.TABLE_SEGMENTS,
            arrayOf("id", "date", "start_millis", "end_millis", "kind", "place_name", "activity_type", "distance_meters"),
            "date BETWEEN ? AND ?",
            arrayOf(startDate, endDate),
            null,
            null,
            "start_millis ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    SegmentRecord(
                        id = cursor.getLong(0),
                        date = cursor.getString(1),
                        startMillis = cursor.getLong(2),
                        endMillis = cursor.getLong(3),
                        kind = TimelineSegmentType.valueOf(cursor.getString(4)),
                        placeName = if (cursor.isNull(5)) null else cursor.getString(5),
                        activityType = if (cursor.isNull(6)) null else cursor.getString(6),
                        distanceMeters = if (cursor.isNull(7)) null else cursor.getDouble(7)
                    )
                )
            }
        }
        return records
    }

    private fun writeDayRow(db: SQLiteDatabase, group: DayGroup) {
        val values = ContentValues().apply {
            put("date", group.date)
            put("start_millis", group.timestampsMillis.first())
            put("end_millis", group.timestampsMillis.last())
            put("point_count", group.latitudes.size)
            put("distance_meters", group.distanceMeters)
            put("points", PointBlobCodec.encode(group.latitudes, group.longitudes, group.timestampsMillis))
        }
        db.insertWithOnConflict(TimelineDb.TABLE_DAYS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun writeSegmentRow(db: SQLiteDatabase, segment: TimelineSegment) {
        val values = ContentValues().apply {
            put("date", localDateOf(segment.startTimeMillis))
            put("start_millis", segment.startTimeMillis)
            put("end_millis", segment.endTimeMillis)
            put("kind", segment.type.name)
            // ponytail: 現状の4形式パーサ(TimelineJsonParser)は滞在地点の表示名(place name)を
            // 一切読み取っていない(placeIdのみ)ため、常にnullになる。表示名が必要になった時点で
            // パーサ側にフィールド追加が必要（このrepositoryの責務外）。
            putNull("place_name")
            put("activity_type", segment.activityType)
            if (segment.distanceMeters != null) {
                put("distance_meters", segment.distanceMeters)
            } else {
                putNull("distance_meters")
            }
        }
        db.insert(TimelineDb.TABLE_SEGMENTS, null, values)
    }

    /**
     * [dates]のうち`days`テーブルに既存行がある日付の集合を返す。
     * Android標準SQLiteの`SQLITE_MAX_VARIABLE_NUMBER=999`を超えないよう
     * [SQLITE_IN_CLAUSE_CHUNK_SIZE]件ずつに分割して複数回`SELECT`する（docs/decisions.md D-006）。
     */
    private fun existingDates(db: SQLiteDatabase, dates: List<String>): Set<String> {
        if (dates.isEmpty()) return emptySet()
        val result = mutableSetOf<String>()
        for (chunk in dates.distinct().chunked(SQLITE_IN_CLAUSE_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                TimelineDb.TABLE_DAYS,
                arrayOf("date"),
                "date IN ($placeholders)",
                chunk.toTypedArray(),
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0))
                }
            }
        }
        return result
    }

    /** 日付ごとに分割した点列と、その日内で連続する点間の実距離合計。 */
    internal class DayGroup(
        val date: String,
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val timestampsMillis: LongArray,
        val distanceMeters: Double
    )

    companion object {
        /**
         * Android標準SQLiteの`SQLITE_MAX_VARIABLE_NUMBER=999`を超えないための、
         * `IN`句1回あたりのプレースホルダ上限（安全マージンを見て900、docs/decisions.md D-006）。
         */
        private const val SQLITE_IN_CLAUSE_CHUNK_SIZE = 900

        /**
         * [prepareImport]の本体。既存日付の検出手段を[existingDatesLookup]として注入できるようにし、
         * DBに依存しない部分（クリーニング・日付分割・上書き件数の算出）を[TimelineDb]を介さずに
         * 単体テスト可能にする（[TimelineDb]はコンストラクタ自体がAndroid API（`SQLiteOpenHelper`）に
         * 依存するため、JVM単体テストからは実インスタンスを用意できない）。
         */
        internal fun buildPreparedImport(
            track: RawTrack,
            options: CleanOptions,
            existingDatesLookup: (List<String>) -> Set<String>
        ): PreparedImport {
            val cleaned = TrackCleaner.clean(track, options)
            val dayGroups = groupPointsByLocalDate(cleaned)
            val existing = existingDatesLookup(dayGroups.map { it.date })
            val overwriteDayCount = dayGroups.count { it.date in existing }
            return PreparedImport(dayGroups, track.segments, cleaned.pointCount, overwriteDayCount)
        }

        private fun localDateOf(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        /**
         * [cleaned]（時刻昇順）を端末タイムゾーンでのローカル日付ごとに分割する。
         * [CleanedTrack.segmentStartIndices]（長時間欠損での分断点）をまたぐ点同士は距離を合算しない
         * （実際には移動していない区間を距離に含めないため）。
         */
        private fun groupPointsByLocalDate(cleaned: CleanedTrack): List<DayGroup> {
            val size = cleaned.pointCount
            if (size == 0) return emptyList()

            val isTrackBreak = BooleanArray(size)
            for (start in cleaned.segmentStartIndices) {
                if (start > 0) isTrackBreak[start] = true
            }

            val groups = mutableListOf<DayGroup>()
            var groupStart = 0
            while (groupStart < size) {
                val date = localDateOf(cleaned.timestampsMillis[groupStart])
                var groupEnd = groupStart
                while (groupEnd + 1 < size && localDateOf(cleaned.timestampsMillis[groupEnd + 1]) == date) {
                    groupEnd++
                }

                var distance = 0.0
                for (i in groupStart until groupEnd) {
                    if (!isTrackBreak[i + 1]) {
                        distance += Mercator.haversineDistanceMeters(
                            cleaned.latitudes[i], cleaned.longitudes[i],
                            cleaned.latitudes[i + 1], cleaned.longitudes[i + 1]
                        )
                    }
                }

                groups.add(
                    DayGroup(
                        date = date,
                        latitudes = cleaned.latitudes.copyOfRange(groupStart, groupEnd + 1),
                        longitudes = cleaned.longitudes.copyOfRange(groupStart, groupEnd + 1),
                        timestampsMillis = cleaned.timestampsMillis.copyOfRange(groupStart, groupEnd + 1),
                        distanceMeters = distance
                    )
                )
                groupStart = groupEnd + 1
            }
            return groups
        }
    }
}
