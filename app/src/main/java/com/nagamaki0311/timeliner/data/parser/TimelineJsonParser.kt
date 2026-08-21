package com.nagamaki0311.timeliner.data.parser

import android.util.Log
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.nagamaki0311.timeliner.model.RawTrack
import com.nagamaki0311.timeliner.model.TimelineSegment
import com.nagamaki0311.timeliner.model.TimelineSegmentType
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * タイムラインJSON（4形式）を[com.google.gson.stream.JsonReader]によるストリーミング走査で
 * 共通中間モデル[RawTrack]へ正規化するパーサ。
 *
 * 形式判別: ルートを1トークンだけ先読みし、`BEGIN_ARRAY`なら端末内Timeline(iOS)、
 * `BEGIN_OBJECT`なら最初に現れる既知キー（`semanticSegments`/`timelineObjects`/`locations`）で判定する。
 * 未知キーは`skipValue()`で読み飛ばし、Googleのスキーマ変更への耐性を持たせる。
 *
 * Gson `JsonReader`は`android.util.JsonReader`をフォークしたクラスでAPIが完全一致し、
 * かつAndroid API非依存のためプレーンなJVM単体テスト（`app/src/test`）から本番と同一コードパスを
 * 実行できる（docs/decisions.md D-003・D-004参照）。
 *
 * null耐性: 各フィールド読み取りは[readNullableString]/[readNullableLong]/[readNullableDouble]で
 * `JsonToken.NULL`を判定してから読む。加えて[parseArrayElementSafely]で配列要素単位を
 * JSONツリーとして一度安全に消費してから解釈するため、想定外の型不一致等で例外が発生しても
 * 元の`reader`の読み取り位置は壊れず、その要素だけをスキップしてファイル全体のパースを継続できる。
 */
object TimelineJsonParser {

    private const val TAG = "TimelineJsonParser"

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
     *
     * 同一zip内に`Semantic Location History/`配下のエントリが1件以上存在する場合、`Records.json`は
     * 由来の異なる点の混在を避けるためインポート対象から除外する（`Records.json`単体のエクスポートの
     * 場合のみ読み込む）。判定にはzip全体のエントリ種別を先に把握する必要があるため、
     * [openInput]（同一内容を指す新しい[InputStream]を返す関数）を2回呼び出して2パスで走査する
     * （`ZipInputStream`は巻き戻せないため。zip内容全体をメモリへ読み込むことは避ける）。
     */
    fun parseZip(openInput: () -> InputStream): RawTrack {
        val builder = RawTrackBuilder()
        val hasSemanticEntry = scanForSemanticLocationHistoryEntry(openInput)
        openInput().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && shouldParseZipEntry(entry.name, hasSemanticEntry)) {
                        // JsonReaderをcloseするとzip全体のストリームが閉じてしまうため、意図的にcloseしない。
                        val reader = JsonReader(InputStreamReader(zip, Charsets.UTF_8))
                        parseRoot(reader, builder)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return builder.build()
    }

    private fun scanForSemanticLocationHistoryEntry(openInput: () -> InputStream): Boolean {
        openInput().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isSemanticLocationHistoryEntry(entry.name)) {
                        return true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return false
    }

    /**
     * zipエントリのパスが対応対象（Semantic Location History配下のjson、またはRecords.json）かどうかを判定する。
     * 純Kotlinのみで完結するためJVM単体テストで検証できる。
     */
    internal fun isTargetZipEntry(entryName: String): Boolean =
        isSemanticLocationHistoryEntry(entryName) || isRecordsJsonEntry(entryName)

    private fun isSemanticLocationHistoryEntry(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        return normalized.endsWith(".json", ignoreCase = true) &&
            normalized.contains("Semantic Location History/", ignoreCase = true)
    }

    private fun isRecordsJsonEntry(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        return normalized.endsWith(".json", ignoreCase = true) &&
            normalized.substringAfterLast('/').equals("Records.json", ignoreCase = true)
    }

    /** `Records.json`は、同一zip内に`Semantic Location History`が存在する場合は除外する。 */
    private fun shouldParseZipEntry(entryName: String, hasSemanticEntry: Boolean): Boolean =
        isSemanticLocationHistoryEntry(entryName) || (!hasSemanticEntry && isRecordsJsonEntry(entryName))

    // ---- null耐性ヘルパー ----

    private fun readNullableString(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextString()
    }

    private fun readNullableLong(reader: JsonReader): Long? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextLong()
    }

    /** 数値・文字列のどちらでも表現されうるdouble値（`distance`/`distanceMeters`）をnull耐性込みで読む。 */
    private fun readNullableDouble(reader: JsonReader): Double? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString().toDouble()
            else -> reader.nextDouble()
        }
    }

    /**
     * 配列内の1要素をJSONツリー（[com.google.gson.JsonElement]）として丸ごと消費してから、
     * その文字列表現を新しい[JsonReader]で読み直して[parseElement]に渡す。
     * 想定外の型不一致・欠損等で[parseElement]が例外を送出しても、元の`reader`はこの要素を
     * 正しく消費し終えた状態のままなので、後続要素の走査に影響しない
     * （`reader`のスキャン位置は壊さず、その要素だけをスキップできる）。
     */
    private fun parseArrayElementSafely(reader: JsonReader, parseElement: (JsonReader) -> Unit) {
        val element = JsonParser.parseReader(reader)
        try {
            JsonReader(StringReader(element.toString())).use { elementReader ->
                parseElement(elementReader)
            }
        } catch (e: RuntimeException) {
            // 想定外の型不一致・欠損等が発生した要素はスキップし、他の要素の処理は継続する（docs/decisions.md D-004決定3）。
            Log.w(TAG, "要素のパースに失敗したためスキップします: ${e.message}", e)
        }
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
        // while条件（hasNext()）自体もtryの内側に含める。あるキーの処理が成功しformatが確定した
        // 直後の「次のキー名確認」自体が例外を投げるケースも保護対象に含める必要があるため
        // （docs/decisions.md D-015決定1）。
        try {
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "semanticSegments" -> {
                        // 対応するキーが判明した時点でformatを確定させる（配列パース呼び出しの前）。
                        // 配列自身の2件目以降の要素で例外が発生しても、1件目までの成果を
                        // 「format確定済み」として回収できるようにするため（docs/decisions.md D-015決定2）。
                        format = TimelineFormat.DEVICE_TIMELINE_ANDROID
                        parseDeviceTimelineArray(reader, builder)
                    }
                    "timelineObjects" -> {
                        format = TimelineFormat.TAKEOUT_SEMANTIC_LOCATION_HISTORY
                        parseTimelineObjectsArray(reader, builder)
                    }
                    "locations" -> {
                        format = TimelineFormat.TAKEOUT_RECORDS
                        parseRecordsArray(reader, builder)
                    }
                    // rawSignals/userLocationProfile等の兄弟キーはv1スコープ外（docs/decisions.md D-002参照）。
                    else -> reader.skipValue()
                }
            }
        } catch (e: IOException) {
            return recoverRootObjectOrRethrow(e, format, builder)
        } catch (e: JsonParseException) {
            return recoverRootObjectOrRethrow(e, format, builder)
        }
        reader.endObject()
        return format ?: throw IllegalArgumentException("既知のタイムラインJSON形式と一致しませんでした")
    }

    /**
     * ルートオブジェクト走査中にストリーム破損由来の例外（`IOException`系/`JsonParseException`系）が
     * 発生した際、既に主要キーから有効なデータを1件以上読み終えていれば（=`format`確定かつ
     * `builder`が空でなければ）そのデータを保持したまま復旧する（docs/decisions.md D-015決定3）。
     * `format`は判明したが1件もデータを読めなかった場合（真の失敗）は救済せず再送出する。
     *
     * 注意: 呼び出し時点で`reader`のストリーム位置は壊れており、`hasNext()`/`endObject()`等の
     * 以降の呼び出しも同じ例外を再送出する（実測確認済み、docs/decisions.md D-014決定4）。
     * `reader`へは以降一切触れず、収集済みの`builder`データのみを使って即座に返す。
     */
    private fun recoverRootObjectOrRethrow(
        e: Exception,
        format: TimelineFormat?,
        builder: RawTrackBuilder
    ): TimelineFormat {
        if (format != null && !builder.isEmpty()) {
            Log.w(
                TAG,
                "ルートオブジェクトのフィールド読み込み中にエラーが発生しましたが、" +
                    "既に${format}形式の有効なデータを取得済みのため、そのままインポートを完了します: ${e.message}",
                e
            )
            return format
        }
        throw e
    }

    // ---- 形式A/B: 端末内Timeline(Android/iOS) ----

    private fun parseDeviceTimelineArray(reader: JsonReader, builder: RawTrackBuilder) {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            parseArrayElementSafely(reader) { elementReader ->
                parseDeviceTimelineSegment(elementReader, builder)
            }
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
                "startTime" -> startTimeMillis = readNullableString(reader)?.let { parseTimestampMillis(it) }
                "endTime" -> endTimeMillis = readNullableString(reader)?.let { parseTimestampMillis(it) }
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
                        key.equals("placeId", ignoreCase = true) -> placeId = readNullableString(reader)
                        key == "placeLocation" -> location = parseLatLngField(reader)
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
                "distanceMeters" -> distanceMeters = readNullableDouble(reader)
                "start" -> start = parseLatLngField(reader)
                "end" -> end = parseLatLngField(reader)
                "topCandidate" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "type") {
                            type = readNullableString(reader)
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
                    "point" -> point = readNullableString(reader)?.let { parseCoordinateString(it) }
                    "time" -> timeMillis = readNullableString(reader)?.let { parseTimestampMillis(it) }
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
                        latLng = readNullableString(reader)
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

    // ---- 形式C: Takeout Semantic Location History(旧) ----

    private fun parseTimelineObjectsArray(reader: JsonReader, builder: RawTrackBuilder) {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            parseArrayElementSafely(reader) { elementReader ->
                parseTimelineObject(elementReader, builder)
            }
        }
        reader.endArray()
    }

    private fun parseTimelineObject(reader: JsonReader, builder: RawTrackBuilder) {
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

    /** `duration`オブジェクトの開始・終了時刻を読む。新形式(`startTimestamp`)・旧形式(`startTimestampMs`)の両方に対応する。 */
    private fun parseDuration(reader: JsonReader): Pair<Long?, Long?> {
        reader.beginObject()
        var start: Long? = null
        var end: Long? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTimestamp", "startTimestampMs" -> start = readNullableString(reader)?.let { parseTimestampMillis(it) }
                "endTimestamp", "endTimestampMs" -> end = readNullableString(reader)?.let { parseTimestampMillis(it) }
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
                            "latitudeE7", "latE7" -> latE7 = readNullableLong(reader)
                            "longitudeE7", "lngE7" -> lonE7 = readNullableLong(reader)
                            "placeId" -> placeId = readNullableString(reader)
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
                "distance" -> distanceMeters = readNullableDouble(reader)
                "activityType" -> activityType = readNullableString(reader)
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
                            "latE7" -> latE7 = readNullableLong(reader)
                            "lngE7" -> lonE7 = readNullableLong(reader)
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
                            "latE7" -> latE7 = readNullableLong(reader)
                            "lngE7" -> lonE7 = readNullableLong(reader)
                            "timestamp" -> timeMillis = readNullableString(reader)?.let { parseTimestampMillis(it) }
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
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            parseArrayElementSafely(reader) { elementReader ->
                parseRecord(elementReader, builder)
            }
        }
        reader.endArray()
    }

    private fun parseRecord(reader: JsonReader, builder: RawTrackBuilder) {
        reader.beginObject()
        var latE7: Long? = null
        var lonE7: Long? = null
        var timeMillis: Long? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "latitudeE7" -> latE7 = readNullableLong(reader)
                "longitudeE7" -> lonE7 = readNullableLong(reader)
                "timestamp" -> timeMillis = readNullableString(reader)?.let { parseTimestampMillis(it) }
                // accuracy等の精度向上フィールドはv1スコープ外（docs/decisions.md D-002参照）。
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (latE7 != null && lonE7 != null && timeMillis != null) {
            builder.addPoint(parseE7(latE7), parseE7(lonE7), timeMillis)
        }
    }
}

/**
 * [RawTrack]構築用の内部ビルダー。点数が非常に多い前提（Records.json等）のため、
 * 倍々に拡張するDoubleArray/LongArrayで点列を蓄積し、ボクシングを避ける。
 *
 * [build]は複数データ源の結合順・zip格納順が時系列と一致しない場合に備え、
 * 全点を時刻昇順に安定ソートしてから[RawTrack]を返す（docs/decisions.md D-004参照）。
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

    /** 点0件かつセグメント0件（=まだ何も有効なデータを取得していない）かどうかを返す。 */
    fun isEmpty(): Boolean = size == 0 && segments.isEmpty()

    private fun grow() {
        val newCapacity = latitudes.size * 2
        latitudes = latitudes.copyOf(newCapacity)
        longitudes = longitudes.copyOf(newCapacity)
        timestamps = timestamps.copyOf(newCapacity)
    }

    fun build(): RawTrack {
        // 安定ソート（KotlinのsortedByはマージソート相当で安定）。同時刻点は元の追加順を保つ。
        val order = (0 until size).sortedBy { timestamps[it] }
        val sortedLatitudes = DoubleArray(size)
        val sortedLongitudes = DoubleArray(size)
        val sortedTimestamps = LongArray(size)
        order.forEachIndexed { newIndex, oldIndex ->
            sortedLatitudes[newIndex] = latitudes[oldIndex]
            sortedLongitudes[newIndex] = longitudes[oldIndex]
            sortedTimestamps[newIndex] = timestamps[oldIndex]
        }
        return RawTrack(
            latitudes = sortedLatitudes,
            longitudes = sortedLongitudes,
            timestampsMillis = sortedTimestamps,
            segments = segments.toList()
        )
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
    }
}
