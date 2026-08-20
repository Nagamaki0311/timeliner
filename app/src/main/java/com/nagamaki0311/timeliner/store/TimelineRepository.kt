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
     * [track]をクリーニングし、日単位に分割して`days`/`segments`へ書き込む。
     * 同じ日付・同じセグメント由来の既存行は上書きする（再インポートに対する冪等性）。
     * トランザクション内で実行するため、大量`INSERT`でも書き込みが高速。
     */
    fun importTrack(track: RawTrack, options: CleanOptions = CleanOptions()): ImportResult {
        val cleaned = TrackCleaner.clean(track, options)
        val dayGroups = groupPointsByLocalDate(cleaned)
        val segmentDates = track.segments.map { localDateOf(it.startTimeMillis) }.toSet()

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (group in dayGroups) {
                writeDayRow(db, group)
            }
            if (segmentDates.isNotEmpty()) {
                val placeholders = segmentDates.joinToString(",") { "?" }
                db.delete(TimelineDb.TABLE_SEGMENTS, "date IN ($placeholders)", segmentDates.toTypedArray())
            }
            for (segment in track.segments) {
                writeSegmentRow(db, segment)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportResult(
            dayCount = dayGroups.size,
            pointCount = cleaned.pointCount,
            segmentCount = track.segments.size,
            earliestDate = dayGroups.minOfOrNull { it.date },
            latestDate = dayGroups.maxOfOrNull { it.date }
        )
    }

    /** 指定した日付範囲（両端含む、`YYYY-MM-DD`）に含まれる`days`行を日付昇順で返す。 */
    fun queryDays(startDate: String, endDate: String): List<DayRecord> {
        val db = dbHelper.readableDatabase
        val records = mutableListOf<DayRecord>()
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
                records.add(
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
        return records
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

    private fun localDateOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** 日付ごとに分割した点列と、その日内で連続する点間の実距離合計。 */
    private class DayGroup(
        val date: String,
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val timestampsMillis: LongArray,
        val distanceMeters: Double
    )

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
