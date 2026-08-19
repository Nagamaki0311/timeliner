package com.nagamaki0311.timeliner.data.parser

import android.util.JsonReader
import android.util.JsonToken
import com.nagamaki0311.timeliner.model.RawTrack
import com.nagamaki0311.timeliner.model.TimelineSegment
import com.nagamaki0311.timeliner.model.TimelineSegmentType
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * タイムラインJSON（4形式）を[android.util.JsonReader]によるストリーミング走査で
 * 共通中間モデル[RawTrack]へ正規化するパーサ。
 *
 * 形式判別: ルートを1トークンだけ先読みし、`BEGIN_ARRAY`なら端末内Timeline(iOS)、
 * `BEGIN_OBJECT`なら最初に現れる既知キー（`semanticSegments`/`timelineObjects`/`locations`）で判定する。
 * 未知キーは`skipValue()`で読み飛ばし、Googleのスキーマ変更への耐性を持たせる。
 *
 * 対応不可: `android.util.JsonReader`はAndroid API依存のため、Robolectric等を追加しない限り
 * プレーンなJVM単体テスト（`app/src/test`）からは実行できない（`Method ... not mocked`で例外になる。
 * 実機/Android実行環境が必要）。純Kotlinで完結する[parseCoordinateString]・[parseE7]・
 * [parseTimestampMillis]・[isTargetZipEntry]のみJVM単体テストで検証する。
 */
object TimelineJsonParser {

    /** 単体の`.json`ファイルをパースする。 */
    fun parseJson(input: InputStream): RawTrack {
        val builder = RawTrackBuilder()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            parseRoot(reader, builder)
        }
        return builder.build()
    }

    /**
     * Takeout配布のzipを走査し、`Semantic Location History`配下の`.json`と`Records.json`をパースする。
     * 複数エントリが見つかった場合はすべて同一の[RawTrack]へ統合する。
     */
    fun parseZip(input: InputStream): RawTrack {
        val builder = RawTrackBuilder()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isTargetZipEntry(entry.name)) {
                    // JsonReaderをcloseするとzip全体のストリームが閉じてしまうため、意図的にcloseしない。
                    val reader = JsonReader(InputStreamReader(zip, Charsets.UTF_8))
                    parseRoot(reader, builder)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return builder.build()
    }

    /**
     * zipエントリのパスが対応対象（Semantic Location History配下のjson、またはRecords.json）かどうかを判定する。
     * 純Kotlinのみで完結するためJVM単体テストで検証できる。
     */
    internal fun isTargetZipEntry(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        if (!normalized.endsWith(".json", ignoreCase = true)) return false
        return normalized.contains("Semantic Location History/", ignoreCase = true) ||
            normalized.substringAfterLast('/').equals("Records.json", ignoreCase = true)
    }

    // ---- ルート判別 ----

    private fun parseRoot(reader: JsonReader, builder: RawTrackBuilder): TimelineFormat {
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                parseDeviceTimelineArray(reader, builder)
                TimelineFormat.DEVICE_TIMELINE_IOS
            }
            JsonToken.BEGIN_OBJECT -> parseRootObject(reader, builder)
            else -> throw IllegalArgumentException("未対応のJSONルート形式です: ${reader.peek()}")
        }
    }

    private fun parseRootObject(reader: JsonReader, builder: RawTrackBuilder): TimelineFormat {
        reader.beginObject()
        var format: TimelineFormat? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "semanticSegments" -> {
                    parseDeviceTimelineArray(reader, builder)
                    format = TimelineFormat.DEVICE_TIMELINE_ANDROID
                }
                "timelineObjects" -> {
                    parseTimelineObjectsArray(reader, builder)
                    format = TimelineFormat.TAKEOUT_SEMANTIC_LOCATION_HISTORY
                }
                "locations" -> {
                    parseRecordsArray(reader, builder)
                    format = TimelineFormat.TAKEOUT_RECORDS
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return format ?: throw IllegalArgumentException("既知のタイムラインJSON形式と一致しませんでした")
    }

    // ---- 形式A/B: 端末内Timeline(Android/iOS) ----

    private fun parseDeviceTimelineArray(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginArray()
        while (reader.hasNext()) {
            parseDeviceTimelineSegment(reader, builder)
        }
        reader.endArray()
    }

    private fun parseDeviceTimelineSegment(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginObject()
        var startTimeMillis: Long? = null
        var endTimeMillis: Long? = null
        var visit: VisitInfo? = null
        var activity: ActivityInfo? = null
        var hasPath = false
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTime" -> startTimeMillis = parseTimestampMillis(reader.nextString())
                "endTime" -> endTimeMillis = parseTimestampMillis(reader.nextString())
                "visit" -> visit = parseVisit(reader)
                "activity" -> activity = parseActivity(reader)
                "timelinePath" -> {
                    parseTimelinePath(reader, builder)
                    hasPath = true
                }
                // rawSignals/userLocationProfile等の兄弟キー、startTimeTimezoneUtcOffsetMinutes等はv1スコープ外。
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val start = startTimeMillis ?: return
        val end = endTimeMillis ?: return

        when {
            visit != null -> {
                val lat = visit.latitude
                val lon = visit.longitude
                if (lat != null && lon != null) {
                    builder.addPoint(lat, lon, start)
                }
                builder.addSegment(
                    TimelineSegment(
                        type = TimelineSegmentType.VISIT,
                        startTimeMillis = start,
                        endTimeMillis = end,
                        placeId = visit.placeId,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
            activity != null -> {
                if (!hasPath) {
                    activity.start?.let { builder.addPoint(it.first, it.second, start) }
                    activity.end?.let { builder.addPoint(it.first, it.second, end) }
                }
                builder.addSegment(
                    TimelineSegment(
                        type = TimelineSegmentType.ACTIVITY,
                        startTimeMillis = start,
                        endTimeMillis = end,
                        activityType = activity.type,
                        distanceMeters = activity.distanceMeters
                    )
                )
            }
            else -> {
                builder.addSegment(
                    TimelineSegment(
                        type = TimelineSegmentType.PATH_ONLY,
                        startTimeMillis = start,
                        endTimeMillis = end
                    )
                )
            }
        }
    }

    private data class VisitInfo(val placeId: String?, val latitude: Double?, val longitude: Double?)

    private fun parseVisit(reader: JsonReader): VisitInfo {
        reader.beginObject()
        var placeId: String? = null
        var location: Pair<Double, Double>? = null
        while (reader.hasNext()) {
            if (reader.nextName() == "topCandidate") {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    when {
                        // Androidは"placeId"、iOSは"placeID"。大文字小文字差を吸収する。
                        key.equals("placeId", ignoreCase = true) -> placeId = reader.nextString()
                        key.equals("placeLocation", ignoreCase = true) -> location = parseLatLngField(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return VisitInfo(placeId, location?.first, location?.second)
    }

    private data class ActivityInfo(
        val type: String?,
        val distanceMeters: Double?,
        val start: Pair<Double, Double>?,
        val end: Pair<Double, Double>?
    )

    private fun parseActivity(reader: JsonReader): ActivityInfo {
        reader.beginObject()
        var type: String? = null
        var distanceMeters: Double? = null
        var start: Pair<Double, Double>? = null
        var end: Pair<Double, Double>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "distanceMeters" -> distanceMeters = readFlexibleDouble(reader)
                "start" -> start = parseLatLngField(reader)
                "end" -> end = parseLatLngField(reader)
                "topCandidate" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "type") {
                            type = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ActivityInfo(type, distanceMeters, start, end)
    }

    private fun parseTimelinePath(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var point: Pair<Double, Double>? = null
            var timeMillis: Long? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "point" -> point = parseCoordinateString(reader.nextString())
                    "time" -> timeMillis = parseTimestampMillis(reader.nextString())
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (point != null && timeMillis != null) {
                builder.addPoint(point.first, point.second, timeMillis)
            }
        }
        reader.endArray()
    }

    /**
     * `placeLocation`/`start`/`end`の値をパースする。
     * Androidは`{"latLng": "<座標文字列>"}`というネストしたオブジェクト、
     * iOSは座標文字列がキーの値として直置きされる。両方を吸収する。
     */
    private fun parseLatLngField(reader: JsonReader): Pair<Double, Double>? {
        return when (reader.peek()) {
            JsonToken.STRING -> parseCoordinateString(reader.nextString())
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                var latLng: String? = null
                while (reader.hasNext()) {
                    if (reader.nextName().equals("latLng", ignoreCase = true)) {
                        latLng = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                latLng?.let { parseCoordinateString(it) }
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readFlexibleDouble(reader: JsonReader): Double {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString().toDouble()
            else -> reader.nextDouble()
        }
    }

    // ---- 形式C: Takeout Semantic Location History(旧) ----

    private fun parseTimelineObjectsArray(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "placeVisit" -> parsePlaceVisit(reader, builder)
                    "activitySegment" -> parseActivitySegment(reader, builder)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
    }

    /** `duration`オブジェクトの開始・終了時刻を読む。新形式(`startTimestamp`)・旧形式(`startTimestampMs`)の両方に対応する。 */
    private fun parseDuration(reader: JsonReader): Pair<Long?, Long?> {
        reader.beginObject()
        var start: Long? = null
        var end: Long? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTimestamp", "startTimestampMs" -> start = parseTimestampMillis(reader.nextString())
                "endTimestamp", "endTimestampMs" -> end = parseTimestampMillis(reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return start to end
    }

    private fun parsePlaceVisit(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginObject()
        var placeId: String? = null
        var latE7: Long? = null
        var lonE7: Long? = null
        var start: Long? = null
        var end: Long? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "location" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "latitudeE7", "latE7" -> latE7 = reader.nextLong()
                            "longitudeE7", "lngE7" -> lonE7 = reader.nextLong()
                            "placeId" -> placeId = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "duration" -> {
                    val (s, e) = parseDuration(reader)
                    start = s
                    end = e
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val startMillis = start ?: return
        val endMillis = end ?: return
        val lat = latE7?.let { parseE7(it) }
        val lon = lonE7?.let { parseE7(it) }
        if (lat != null && lon != null) {
            builder.addPoint(lat, lon, startMillis)
        }
        builder.addSegment(
            TimelineSegment(
                type = TimelineSegmentType.VISIT,
                startTimeMillis = startMillis,
                endTimeMillis = endMillis,
                placeId = placeId,
                latitude = lat,
                longitude = lon
            )
        )
    }

    private fun parseActivitySegment(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginObject()
        var start: Long? = null
        var end: Long? = null
        var activityType: String? = null
        var distanceMeters: Double? = null
        var hasSimplifiedRawPath = false
        val waypoints = mutableListOf<Pair<Double, Double>>()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "duration" -> {
                    val (s, e) = parseDuration(reader)
                    start = s
                    end = e
                }
                "distance" -> distanceMeters = readFlexibleDouble(reader)
                "activityType" -> activityType = reader.nextString()
                "waypointPath" -> parseWaypointPath(reader, waypoints)
                "simplifiedRawPath" -> {
                    if (parseSimplifiedRawPath(reader, builder)) {
                        hasSimplifiedRawPath = true
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val startMillis = start
        val endMillis = end

        // 時刻付きのsimplifiedRawPathがあればそちらを優先し、無ければwaypointPathをdurationの範囲で等時間割り付けする。
        if (!hasSimplifiedRawPath && waypoints.isNotEmpty() && startMillis != null && endMillis != null) {
            addEvenlySpacedPoints(builder, waypoints, startMillis, endMillis)
        }

        if (startMillis != null && endMillis != null) {
            builder.addSegment(
                TimelineSegment(
                    type = TimelineSegmentType.ACTIVITY,
                    startTimeMillis = startMillis,
                    endTimeMillis = endMillis,
                    activityType = activityType,
                    distanceMeters = distanceMeters
                )
            )
        }
    }

    private fun parseWaypointPath(reader: JsonReader, waypoints: MutableList<Pair<Double, Double>>) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "waypoints") {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    var latE7: Long? = null
                    var lonE7: Long? = null
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "latE7" -> latE7 = reader.nextLong()
                            "lngE7" -> lonE7 = reader.nextLong()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (latE7 != null && lonE7 != null) {
                        waypoints.add(parseE7(latE7) to parseE7(lonE7))
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    /** @return 1点以上をbuilderへ追加した場合true。 */
    private fun parseSimplifiedRawPath(reader: JsonReader, builder: RawTrackBuilder): Boolean {
        var added = false
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "points") {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    var latE7: Long? = null
                    var lonE7: Long? = null
                    var timeMillis: Long? = null
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "latE7" -> latE7 = reader.nextLong()
                            "lngE7" -> lonE7 = reader.nextLong()
                            "timestamp" -> timeMillis = parseTimestampMillis(reader.nextString())
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (latE7 != null && lonE7 != null && timeMillis != null) {
                        builder.addPoint(parseE7(latE7), parseE7(lonE7), timeMillis)
                        added = true
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return added
    }

    private fun addEvenlySpacedPoints(
        builder: RawTrackBuilder,
        points: List<Pair<Double, Double>>,
        startMillis: Long,
        endMillis: Long
    ) {
        val count = points.size
        if (count == 1) {
            builder.addPoint(points[0].first, points[0].second, startMillis)
            return
        }
        val span = endMillis - startMillis
        points.forEachIndexed { index, (lat, lon) ->
            val fraction = index.toDouble() / (count - 1)
            val time = startMillis + (span * fraction).toLong()
            builder.addPoint(lat, lon, time)
        }
    }

    // ---- 形式D: Takeout Records(生GPS) ----

    private fun parseRecordsArray(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var latE7: Long? = null
            var lonE7: Long? = null
            var timeMillis: Long? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "latitudeE7" -> latE7 = reader.nextLong()
                    "longitudeE7" -> lonE7 = reader.nextLong()
                    "timestamp" -> timeMillis = parseTimestampMillis(reader.nextString())
                    // accuracy等の精度向上フィールドはv1スコープ外（docs/decisions.md D-002参照）。
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (latE7 != null && lonE7 != null && timeMillis != null) {
                builder.addPoint(parseE7(latE7), parseE7(lonE7), timeMillis)
            }
        }
        reader.endArray()
    }
}

/**
 * [RawTrack]構築用の内部ビルダー。点数が非常に多い前提（Records.json等）のため、
 * 倍々に拡張するDoubleArray/LongArrayで点列を蓄積し、ボクシングを避ける。
 */
private class RawTrackBuilder {
    private var latitudes = DoubleArray(INITIAL_CAPACITY)
    private var longitudes = DoubleArray(INITIAL_CAPACITY)
    private var timestamps = LongArray(INITIAL_CAPACITY)
    private var size = 0
    private val segments = mutableListOf<TimelineSegment>()

    fun addPoint(latitude: Double, longitude: Double, timestampMillis: Long) {
        if (size == latitudes.size) {
            grow()
        }
        latitudes[size] = latitude
        longitudes[size] = longitude
        timestamps[size] = timestampMillis
        size++
    }

    fun addSegment(segment: TimelineSegment) {
        segments.add(segment)
    }

    private fun grow() {
        val newCapacity = latitudes.size * 2
        latitudes = latitudes.copyOf(newCapacity)
        longitudes = longitudes.copyOf(newCapacity)
        timestamps = timestamps.copyOf(newCapacity)
    }

    fun build(): RawTrack = RawTrack(
        latitudes = latitudes.copyOf(size),
        longitudes = longitudes.copyOf(size),
        timestampsMillis = timestamps.copyOf(size),
        segments = segments.toList()
    )

    companion object {
        private const val INITIAL_CAPACITY = 64
    }
}
