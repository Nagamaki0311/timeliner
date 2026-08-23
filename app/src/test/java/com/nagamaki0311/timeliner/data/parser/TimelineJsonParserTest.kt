package com.nagamaki0311.timeliner.data.parser

import com.nagamaki0311.timeliner.model.TimelineSegmentType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimelineJsonParser]のテスト。
 *
 * `com.google.gson.stream.JsonReader`（`android.util.JsonReader`をフォークしたクラスでAPIが完全一致し、
 * Android API非依存）へ切り替えたことで（docs/decisions.md D-003・D-004参照）、
 * プレーンなJVM単体テストから本番と同一の`parseJson`/`parseZip`コードパスを実行できる。
 */
class TimelineJsonParserTest {

    companion object {
        private const val BASE_TIMESTAMP_MILLIS = 1_700_000_000_000L
    }

    // ---- isTargetZipEntry（zipエントリのパス判定） ----

    @Test
    fun isTargetZipEntry_semanticLocationHistoryJson_isTarget() {
        assertTrue(
            TimelineJsonParser.isTargetZipEntry(
                "Takeout/Location History (Timeline)/Semantic Location History/2026/2026_AUGUST.json"
            )
        )
    }

    @Test
    fun isTargetZipEntry_recordsJson_isTarget() {
        assertTrue(
            TimelineJsonParser.isTargetZipEntry(
                "Takeout/Location History (Timeline)/Records.json"
            )
        )
    }

    @Test
    fun isTargetZipEntry_recordsJsonCaseInsensitive_isTarget() {
        assertTrue(TimelineJsonParser.isTargetZipEntry("Takeout/records.JSON"))
    }

    @Test
    fun isTargetZipEntry_windowsStyleBackslashPath_isTarget() {
        assertTrue(
            TimelineJsonParser.isTargetZipEntry(
                "Takeout\\Location History (Timeline)\\Semantic Location History\\2026\\2026_AUGUST.json"
            )
        )
    }

    @Test
    fun isTargetZipEntry_unrelatedJson_isNotTarget() {
        assertFalse(TimelineJsonParser.isTargetZipEntry("Takeout/Location History (Timeline)/Settings.json"))
    }

    @Test
    fun isTargetZipEntry_nonJsonFile_isNotTarget() {
        assertFalse(
            TimelineJsonParser.isTargetZipEntry(
                "Takeout/Location History (Timeline)/Semantic Location History/2026/2026_AUGUST.csv"
            )
        )
    }

    @Test
    fun isTargetZipEntry_readmeAtRoot_isNotTarget() {
        assertFalse(TimelineJsonParser.isTargetZipEntry("Takeout/README.json"))
    }

    // ---- 形式A: 端末内Timeline(Android) ----

    @Test
    fun parseJson_deviceTimelineAndroid_parsesVisitActivityAndPathSegments() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "1700000000000",
                  "endTime": "1700000001000",
                  "visit": {
                    "topCandidate": {
                      "placeId": "ChIJ_VISIT",
                      "placeLocation": {"latLng": "35.6812°, 139.7671°"}
                    }
                  }
                },
                {
                  "startTime": "1700000002000",
                  "endTime": "1700000003000",
                  "activity": {
                    "distanceMeters": 500.5,
                    "start": {"latLng": "35.1°, 139.1°"},
                    "end": {"latLng": "35.2°, 139.2°"},
                    "topCandidate": {"type": "WALKING"}
                  }
                },
                {
                  "startTime": "1700000004000",
                  "endTime": "1700000005000",
                  "timelinePath": [
                    {"point": "35.3°, 139.3°", "time": "1700000004200"},
                    {"point": "35.4°, 139.4°", "time": "1700000004800"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(5, track.pointCount)
        assertEquals(3, track.segments.size)

        val visitPoint = track.point(0)
        assertEquals(35.6812, visitPoint.latitude, 1e-9)
        assertEquals(139.7671, visitPoint.longitude, 1e-9)
        assertEquals(1700000000000L, visitPoint.timestampMillis)

        val activityStart = track.point(1)
        assertEquals(35.1, activityStart.latitude, 1e-9)
        val activityEnd = track.point(2)
        assertEquals(35.2, activityEnd.latitude, 1e-9)

        val pathPoint1 = track.point(3)
        assertEquals(35.3, pathPoint1.latitude, 1e-9)
        val pathPoint2 = track.point(4)
        assertEquals(35.4, pathPoint2.latitude, 1e-9)

        val visitSegment = track.segments[0]
        assertEquals(TimelineSegmentType.VISIT, visitSegment.type)
        assertEquals("ChIJ_VISIT", visitSegment.placeId)

        val activitySegment = track.segments[1]
        assertEquals(TimelineSegmentType.ACTIVITY, activitySegment.type)
        assertEquals("WALKING", activitySegment.activityType)
        assertEquals(500.5, activitySegment.distanceMeters!!, 1e-9)

        val pathSegment = track.segments[2]
        assertEquals(TimelineSegmentType.PATH_ONLY, pathSegment.type)
    }

    // ---- 形式B: 端末内Timeline(iOS) ----

    @Test
    fun parseJson_deviceTimelineIos_parsesVisitSegment() {
        val json = """
            [
              {
                "startTime": "1700000000000",
                "endTime": "1700000001000",
                "visit": {
                  "topCandidate": {
                    "placeID": "ID_IOS",
                    "placeLocation": "geo:36.0,140.0"
                  }
                }
              }
            ]
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        val point = track.point(0)
        assertEquals(36.0, point.latitude, 1e-9)
        assertEquals(140.0, point.longitude, 1e-9)
        assertEquals(1700000000000L, point.timestampMillis)

        assertEquals(1, track.segments.size)
        assertEquals("ID_IOS", track.segments[0].placeId)
    }

    // ---- 形式C: Takeout Semantic Location History(旧) ----

    @Test
    fun parseJson_takeoutSemanticLocationHistory_parsesPlaceVisitAndActivitySegment() {
        val json = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {
                      "latitudeE7": 356812000,
                      "longitudeE7": 1397671000,
                      "placeId": "PID_SEMANTIC"
                    },
                    "duration": {
                      "startTimestamp": "1700000000000",
                      "endTimestamp": "1700000001000"
                    }
                  }
                },
                {
                  "activitySegment": {
                    "duration": {
                      "startTimestamp": "1700000002000",
                      "endTimestamp": "1700000003000"
                    },
                    "activityType": "WALKING",
                    "distance": 123.4,
                    "waypointPath": {
                      "waypoints": [
                        {"latE7": 356800000, "lngE7": 1397600000},
                        {"latE7": 356900000, "lngE7": 1397900000}
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(3, track.pointCount)
        assertEquals(2, track.segments.size)

        val visitPoint = track.point(0)
        assertEquals(35.6812, visitPoint.latitude, 1e-9)
        assertEquals(1700000000000L, visitPoint.timestampMillis)

        val waypoint0 = track.point(1)
        assertEquals(35.68, waypoint0.latitude, 1e-9)
        assertEquals(1700000002000L, waypoint0.timestampMillis)

        val waypoint1 = track.point(2)
        assertEquals(35.69, waypoint1.latitude, 1e-9)
        assertEquals(1700000003000L, waypoint1.timestampMillis)

        assertEquals(TimelineSegmentType.VISIT, track.segments[0].type)
        assertEquals("PID_SEMANTIC", track.segments[0].placeId)
        assertEquals(TimelineSegmentType.ACTIVITY, track.segments[1].type)
        assertEquals(123.4, track.segments[1].distanceMeters!!, 1e-9)
    }

    // ---- 形式D: Takeout Records(生GPS) ----

    @Test
    fun parseJson_takeoutRecords_parsesLocationPoints() {
        val json = """
            {
              "locations": [
                {"latitudeE7": 356812000, "longitudeE7": 1397671000, "timestamp": "1700000000000"},
                {"latitudeE7": 356813000, "longitudeE7": 1397672000, "timestamp": "1700000001000"}
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(2, track.pointCount)
        assertEquals(0, track.segments.size)
        assertEquals(35.6812, track.point(0).latitude, 1e-9)
        assertEquals(35.6813, track.point(1).latitude, 1e-9)
    }

    // ---- null耐性 ----

    @Test
    fun parseJson_placeVisitWithNullPlaceId_parsesSuccessfullyWithNullPlaceId() {
        val json = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {
                      "latitudeE7": 356812000,
                      "longitudeE7": 1397671000,
                      "placeId": null
                    },
                    "duration": {
                      "startTimestamp": "1700000000000",
                      "endTimestamp": "1700000001000"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(1, track.segments.size)
        assertNull(track.segments[0].placeId)
    }

    @Test
    fun parseJson_activitySegmentWithNullActivityType_parsesSuccessfullyWithNullActivityType() {
        val json = """
            {
              "timelineObjects": [
                {
                  "activitySegment": {
                    "duration": {
                      "startTimestamp": "1700000000000",
                      "endTimestamp": "1700000001000"
                    },
                    "activityType": null,
                    "distance": 42.0
                  }
                }
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.segments.size)
        assertNull(track.segments[0].activityType)
        assertEquals(42.0, track.segments[0].distanceMeters!!, 1e-9)
    }

    @Test
    fun parseJson_recordWithNullLatitude_skipsThatRecordButParsesOthers() {
        val json = """
            {
              "locations": [
                {"latitudeE7": null, "longitudeE7": 1397671000, "timestamp": "1700000000000"},
                {"latitudeE7": 356813000, "longitudeE7": 1397672000, "timestamp": "1700000001000"}
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(35.6813, track.point(0).latitude, 1e-9)
        assertEquals(1700000001000L, track.point(0).timestampMillis)
    }

    @Test
    fun parseJson_malformedElementInArray_skipsElementButParsesOtherElements() {
        val json = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {"latitudeE7": 356812000, "longitudeE7": 1397671000, "placeId": "PID_BAD"},
                    "duration": "not-an-object"
                  }
                },
                {
                  "placeVisit": {
                    "location": {"latitudeE7": 356813000, "longitudeE7": 1397672000, "placeId": "PID_GOOD"},
                    "duration": {"startTimestamp": "1700000000000", "endTimestamp": "1700000001000"}
                  }
                }
              ]
            }
        """.trimIndent()

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(1, track.segments.size)
        assertEquals("PID_GOOD", track.segments[0].placeId)
    }

    @Test
    fun parseJson_locationsFieldIsExplicitNull_parsesSuccessfullyWithNoPoints() {
        val json = """{"locations": null}"""

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(0, track.pointCount)
        assertEquals(0, track.segments.size)
    }

    // ---- ルート直下の未知キー（rawSignals等）の読み飛ばし失敗からの回復 ----

    /**
     * 実機で報告された不具合の再現テスト（docs/decisions.md D-014）。
     * `rawSignals`は`semanticSegments`の兄弟キー（ルートオブジェクト直下）であり、アプリが
     * 使わないv1スコープ外のフィールドとして`skipValue()`で読み飛ばされる。ファイルがこの
     * `rawSignals`配列の途中で切り詰められている（`End of input`となる）場合でも、
     * 既に`semanticSegments`から読み終えている有効なデータは失わずにインポートを完了できることを検証する。
     */
    @Test
    fun parseJson_rawSignalsTruncatedMidArrayAfterValidSemanticSegments_returnsAlreadyParsedData() {
        val json = buildString {
            append(
                """
                {
                  "semanticSegments": [
                    {
                      "startTime": "1700000000000",
                      "endTime": "1700000001000",
                      "visit": {
                        "topCandidate": {
                          "placeId": "ChIJ_TRUNCATED_RAWSIGNALS",
                          "placeLocation": {"latLng": "35.6812°, 139.7671°"}
                        }
                      }
                    }
                  ],
                  "rawSignals": [
                """.trimIndent()
            )
            repeat(5000) { i ->
                if (i > 0) append(",")
                append("{\"idx\":").append(i).append(",\"noise\":\"x\"}")
            }
            // 意図的に配列・オブジェクトを閉じない（ファイルが途中で切り詰められた状態を再現する）。
        }

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(1, track.segments.size)
        assertEquals("ChIJ_TRUNCATED_RAWSIGNALS", track.segments[0].placeId)
        assertEquals(35.6812, track.point(0).latitude, 1e-9)
    }

    // ---- レビュー指摘（docs/decisions.md D-015）に基づく境界値テスト ----

    /**
     * 既知キー（`semanticSegments`/`timelineObjects`/`locations`）が一つも現れないまま
     * （＝`format`未確定のまま）ストリームが切り詰められた場合は、回収可能な有効データが
     * 無いため従来通り例外が再送出されることを検証する（D-015決定3、救済条件`format != null`）。
     */
    @Test
    fun parseJson_unknownKeyTruncatedBeforeAnyKnownKeyAppears_rethrowsException() {
        val json = buildString {
            append("""{"rawSignals": [""")
            repeat(100) { i ->
                if (i > 0) append(",")
                append("{\"idx\":").append(i).append(",\"noise\":\"x\"}")
            }
            // 意図的に配列・オブジェクトを閉じない。
        }

        assertThrows(Exception::class.java) {
            TimelineJsonParser.parseJson(json.byteInputStream())
        }
    }

    /**
     * 主要配列自身（`semanticSegments`）が2件目以降の要素で途中切り詰めになった場合、
     * `format`は配列パース呼び出し前に確定済み・1件目は既に`builder`へ追加済みのため、
     * 例外を投げずに1件目の有効なデータを保持したまま復旧することを検証する（D-015決定1・2）。
     */
    @Test
    fun parseJson_semanticSegmentsTruncatedFromSecondElement_recoversFirstElementData() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "1700000000000",
                  "endTime": "1700000001000",
                  "visit": {
                    "topCandidate": {
                      "placeId": "ChIJ_FIRST_ELEMENT",
                      "placeLocation": {"latLng": "35.6812°, 139.7671°"}
                    }
                  }
                },
                {
                  "startTime": "1700000002000",
                  "endTime": "1700000003000"
        """.trimIndent()
        // 2件目の要素・配列・ルートオブジェクトのいずれも閉じない（途中切り詰めを再現する）。

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(1, track.segments.size)
        assertEquals("ChIJ_FIRST_ELEMENT", track.segments[0].placeId)
    }

    /**
     * ルートオブジェクトの閉じ`}`直前（次のキーがあるかどうかの境界）で切り詰められた場合、
     * `while (reader.hasNext())`の条件式評価自体が例外を投げるが、この条件式もtryの内側に
     * 含まれるため、既に確定していたデータを保持したまま例外を投げずに復旧することを検証する
     * （D-015決定1）。
     */
    @Test
    fun parseJson_truncatedRightAfterKnownKeyAtObjectCloseBoundary_recoversParsedData() {
        val json = """{"locations": [{"latitudeE7": 356812000, "longitudeE7": 1397671000, "timestamp": "1700000000000"}]"""
        // ルートオブジェクトを閉じる"}"を意図的に含めない。

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(1, track.pointCount)
        assertEquals(0, track.segments.size)
        assertEquals(35.6812, track.point(0).latitude, 1e-9)
    }

    // ---- レビュー指摘（docs/decisions.md D-016）: 非EOF系IOExceptionがJsonIOExceptionへ
    //      ラップされて送出されるケースからの回復 ----

    /**
     * Gsonの`JsonParser.parseReader(reader)`（[TimelineJsonParser]の`parseArrayElementSafely`が
     * 配列要素単位のJSONツリー化に使用）は、主ストリームからの非EOF系（`EOFException`ではない）
     * 通常の`IOException`を`com.google.gson.JsonIOException`にラップして送出する
     * （gson 2.14.0の`com.google.gson.internal.Streams.parse`のバイトコードで確認済み）。
     * `JsonIOException`は`JsonSyntaxException`のサブクラスではなく`java.io.IOException`の
     * サブクラスでもないため、`semanticSegments`の2件目の要素消費中にこの種の`IOException`が
     * 発生しても、1件目までの有効なデータを保持したまま例外を投げずに復旧できることを検証する
     * （D-016、`catch (e: JsonParseException)`への変更で捕捉できることの確認）。
     */
    @Test
    fun parseJson_nonEofIOExceptionDuringSecondElementParsing_recoversFirstElementData() {
        val validFirstElementJson =
            "{\"startTime\":\"1700000000000\",\"endTime\":\"1700000001000\"," +
                "\"visit\":{\"topCandidate\":{\"placeId\":\"ChIJ_IO_ERROR_RECOVERY\"," +
                "\"placeLocation\":{\"latLng\":\"35.6812°, 139.7671°\"}}}}"
        val validPrefix = "{\"semanticSegments\":[$validFirstElementJson,"
        val secondElementJson = "{\"startTime\":\"1700000002000\",\"endTime\":\"1700000003000\"}]}"
        val fullJson = validPrefix + secondElementJson

        // 1件目の要素＋直後のカンマまでは正常に読めるが、2件目の要素の内容を読み進める途中
        // （配列末尾や閉じ括弧などのEOF相当の位置ではない）で意図的に非EOF系のIOExceptionを送出する。
        val thresholdBytes = validPrefix.toByteArray(Charsets.UTF_8).size + 5
        val input = FailingAfterThresholdInputStream(fullJson.toByteArray(Charsets.UTF_8), thresholdBytes)

        val track = TimelineJsonParser.parseJson(input)

        assertEquals(1, track.pointCount)
        assertEquals(1, track.segments.size)
        assertEquals("ChIJ_IO_ERROR_RECOVERY", track.segments[0].placeId)
    }

    /**
     * [parseJson_nonEofIOExceptionDuringSecondElementParsing_recoversFirstElementData]用の
     * テスト専用`InputStream`。指定バイト数までは正常にデータを返すが、それ以降の読み取りでは
     * `EOFException`ではない通常の`IOException`を送出する（意図的な非EOF系読み取りエラーの再現）。
     */
    private class FailingAfterThresholdInputStream(
        private val bytes: ByteArray,
        private val thresholdBytes: Int
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= thresholdBytes || position >= bytes.size) {
                throw IOException("simulated non-EOF read error")
            }
            return bytes[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val allowed = minOf(len, thresholdBytes - position, bytes.size - position)
            if (allowed <= 0) {
                throw IOException("simulated non-EOF read error")
            }
            System.arraycopy(bytes, position, b, off, allowed)
            position += allowed
            return allowed
        }
    }

    // ---- zip: 複数データ源の優先順位付け・時刻ソート ----

    @Test
    fun parseZip_recordsAndSemanticLocationHistoryPresent_excludesRecordsJson() {
        val recordsJson = """
            {"locations": [{"latitudeE7": 10000000, "longitudeE7": 10000000, "timestamp": "1600000000000"}]}
        """.trimIndent()
        val semanticJson = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {"latitudeE7": 356812000, "longitudeE7": 1397671000, "placeId": "PID"},
                    "duration": {"startTimestamp": "1700000000000", "endTimestamp": "1700000001000"}
                  }
                }
              ]
            }
        """.trimIndent()
        val zipBytes = buildZip(
            listOf(
                "Takeout/Location History (Timeline)/Records.json" to recordsJson,
                "Takeout/Location History (Timeline)/Semantic Location History/2023/2023_JANUARY.json" to semanticJson
            )
        )

        val track = TimelineJsonParser.parseZip { ByteArrayInputStream(zipBytes) }

        assertEquals(1, track.pointCount)
        assertEquals(35.6812, track.point(0).latitude, 1e-9)
    }

    @Test
    fun parseZip_recordsOnly_parsesRecordsJson() {
        val recordsJson = """
            {"locations": [{"latitudeE7": 10000000, "longitudeE7": 10000000, "timestamp": "1600000000000"}]}
        """.trimIndent()
        val zipBytes = buildZip(
            listOf("Takeout/Location History (Timeline)/Records.json" to recordsJson)
        )

        val track = TimelineJsonParser.parseZip { ByteArrayInputStream(zipBytes) }

        assertEquals(1, track.pointCount)
        assertEquals(1.0, track.point(0).latitude, 1e-9)
    }

    @Test
    fun parseZip_multipleEntriesOutOfOrder_pointsAreSortedByTimestampAscending() {
        fun placeVisitJson(startEpoch: Long, endEpoch: Long, placeId: String): String = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {"latitudeE7": 356812000, "longitudeE7": 1397671000, "placeId": "$placeId"},
                    "duration": {"startTimestamp": "$startEpoch", "endTimestamp": "$endEpoch"}
                  }
                }
              ]
            }
        """.trimIndent()

        // zip格納順はMARCH→JANUARY（時系列と逆）。ZipInputStreamはこの格納順のまま返す。
        val zipBytes = buildZip(
            listOf(
                "Takeout/Semantic Location History/2023/2023_MARCH.json" to
                    placeVisitJson(3_000_000_000L, 3_000_001_000L, "MARCH"),
                "Takeout/Semantic Location History/2023/2023_JANUARY.json" to
                    placeVisitJson(1_000_000_000L, 1_000_001_000L, "JANUARY")
            )
        )

        val track = TimelineJsonParser.parseZip { ByteArrayInputStream(zipBytes) }

        assertEquals(2, track.pointCount)
        assertEquals(1_000_000_000L, track.timestampsMillis[0])
        assertEquals(3_000_000_000L, track.timestampsMillis[1])
    }

    // ---- 進捗コールバック（docs/tasks.md T-015） ----

    /**
     * `onProgress`が最終的に一度以上呼ばれ、`pointCount`が単調増加し常に最終`track.pointCount`以下
     * であること、通知される最古タイムスタンプは（時刻昇順データのため）常に先頭点の時刻と一致し、
     * 通知される最新タイムスタンプはその時点までの点の範囲内に収まることを検証する。
     * 間引きにより、最後の通知が必ずしも最終点そのものを指すとは限らない（docs/tasks.md T-015、
     * 完了自体は[ImportUiState.Success]側で別途通知されるため、進捗表示としてはこれで十分）。
     */
    @Test
    fun parseJson_onProgressCallback_reportsMonotonicPointCountAndTimestampRange() {
        val pointCount = 20_000
        val json = buildRecordsJson(pointCount)
        val reportedPointCounts = mutableListOf<Int>()
        var lastEarliest = Long.MIN_VALUE
        var lastLatest = Long.MIN_VALUE

        val track = TimelineJsonParser.parseJson(json.byteInputStream(), onProgress = { count, earliest, latest ->
            reportedPointCounts.add(count)
            lastEarliest = earliest
            lastLatest = latest
        })

        assertEquals(pointCount, track.pointCount)
        assertTrue("onProgressが少なくとも1回は呼ばれること", reportedPointCounts.isNotEmpty())
        assertEquals(reportedPointCounts.sorted(), reportedPointCounts)
        assertTrue(reportedPointCounts.last() <= pointCount)
        assertTrue(lastEarliest <= lastLatest)
        assertEquals(BASE_TIMESTAMP_MILLIS, lastEarliest)
        assertEquals(BASE_TIMESTAMP_MILLIS + (reportedPointCounts.last() - 1) * 1000L, lastLatest)
    }

    /**
     * `onProgress`の呼び出し回数が点数の増分（内部の間引き閾値）で間引かれ、
     * 全点ごとに呼ばれることはない（過剰なオーバーヘッドにならない）ことを検証する（D-017 T-015）。
     */
    @Test
    fun parseJson_onProgressCallback_isThrottledAndNotCalledPerPoint() {
        val pointCount = 50_000
        val json = buildRecordsJson(pointCount)
        var callCount = 0

        TimelineJsonParser.parseJson(json.byteInputStream(), onProgress = { _, _, _ -> callCount++ })

        // 点数ベースの間引き閾値が概ね機能していれば、呼び出し回数は点数よりはるかに少ない。
        assertTrue("callCount=$callCount は点数に対して間引かれているはず", callCount < pointCount / 100)
        assertTrue("onProgressが少なくとも1回は呼ばれること", callCount > 0)
    }

    /**
     * 初回`addPoint`（`pointCount=1`）時点では基準時刻の初期化のみ行い、`onProgress`を発火しないことを検証する
     * （docs/decisions.md D-021決定1、レビュー指摘3への対応）。修正前は`lastProgressTimeMillis`の初期値が`0L`のため、
     * 最初の`addPoint`で経過時間条件が必ず真になり`pointCount=1`という意図しないタイミングで発火していた。
     */
    @Test
    fun parseJson_onProgressCallback_singlePointDoesNotFireImmediately() {
        val json = buildRecordsJson(1)
        var callCount = 0

        TimelineJsonParser.parseJson(json.byteInputStream(), onProgress = { _, _, _ -> callCount++ })

        assertEquals(0, callCount)
    }

    /** `onProgress`を渡さない（既定null）場合、従来通り例外なくパースできることを検証する。 */
    @Test
    fun parseJson_onProgressOmitted_parsesSuccessfullyWithoutCallback() {
        val json = buildRecordsJson(10)

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(10, track.pointCount)
    }

    /**
     * `parseZip`が複数エントリをまたいでも同一の`RawTrackBuilder`を共有し、`onProgress`の`pointCount`が
     * エントリをまたいで累積されることを検証する（docs/decisions.md D-021決定1、レビュー指摘4への対応）。
     */
    @Test
    fun parseZip_multipleEntries_onProgressAccumulatesPointCountAcrossEntries() {
        val pointsPerEntry = 5_000
        val zipBytes = buildZip(
            listOf(
                "Takeout/Semantic Location History/2023/2023_JANUARY.json" to buildRecordsJson(pointsPerEntry),
                "Takeout/Semantic Location History/2023/2023_FEBRUARY.json" to buildRecordsJson(pointsPerEntry)
            )
        )
        val reportedPointCounts = mutableListOf<Int>()

        val track = TimelineJsonParser.parseZip(onProgress = { count, _, _ -> reportedPointCounts.add(count) }) {
            ByteArrayInputStream(zipBytes)
        }

        assertEquals(pointsPerEntry * 2, track.pointCount)
        assertTrue("onProgressが少なくとも1回は呼ばれること", reportedPointCounts.isNotEmpty())
        assertEquals(reportedPointCounts.sorted(), reportedPointCounts)
        assertTrue(
            "2エントリ目の点も累積されている（1エントリ分の点数を超えて通知される）はず",
            reportedPointCounts.last() > pointsPerEntry
        )
        assertTrue(reportedPointCounts.last() <= track.pointCount)
    }

    // ---- 大規模データ（docs/decisions.md D-022決定1） ----

    /**
     * `RawTrackBuilder.isAlreadySortedAscending()`（既ソート時のコピー省略高速パス、T-016）を
     * 130万点規模（[com.nagamaki0311.timeliner.store.TimelineRepositoryTest]の560日規模テストと
     * 同等規模）で検証する。既存の大規模テストは`RawTrack`を直接構築し`RawTrackBuilder`を経由しない
     * ため、この高速パスは未検証だった（レビュー指摘、docs/decisions.md D-022決定1）。
     * 時刻昇順で生成しているため`isAlreadySortedAscending()`は`true`を返し、ソートを伴わない
     * コピーのみの経路（本テストの主目的）を通る。クラッシュせず、点数・端点の値を保つことを確認する。
     */
    @Test
    fun parseJson_largeScale1_3MillionPointsAscendingTimestamps_completesWithoutCrashAndPreservesAllPoints() {
        val pointCount = 1_300_000
        val json = buildLargeAscendingRecordsJson(pointCount)

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(pointCount, track.pointCount)
        assertEquals(0, track.segments.size)

        for (i in 1 until pointCount) {
            assertTrue(
                "timestampsMillis[$i]がtimestampsMillis[${i - 1}]未満（既ソート前提が崩れている）",
                track.timestampsMillis[i] >= track.timestampsMillis[i - 1]
            )
        }

        val firstPoint = track.point(0)
        assertEquals(BASE_TIMESTAMP_MILLIS, firstPoint.timestampMillis)
        assertEquals(35.0, firstPoint.latitude, 1e-9)
        assertEquals(139.0, firstPoint.longitude, 1e-9)

        val lastPoint = track.point(pointCount - 1)
        assertEquals(BASE_TIMESTAMP_MILLIS + (pointCount - 1) * 1000L, lastPoint.timestampMillis)
        val expectedLastLatitude = (350000000L + (pointCount - 1)) / 1.0e7
        assertEquals(expectedLastLatitude, lastPoint.latitude, 1e-9)
    }

    /**
     * [parseJson_largeScale1_3MillionPointsAscendingTimestamps_completesWithoutCrashAndPreservesAllPoints]用の
     * 130万点規模JSON生成。文字列結合を都度行うと遅いため、[buildRecordsJson]と異なり
     * [StringBuilder]へ直接追記する（`joinToString`も内部で`StringBuilder`を使うが、
     * 事前に容量を確保できる分こちらの方が130万点規模ではやや効率的）。
     */
    private fun buildLargeAscendingRecordsJson(pointCount: Int): String {
        val builder = StringBuilder(pointCount * 70)
        builder.append("{\"locations\": [")
        for (i in 0 until pointCount) {
            if (i > 0) builder.append(",")
            val latE7 = 350000000L + i
            val timestamp = BASE_TIMESTAMP_MILLIS + i * 1000L
            builder.append("{\"latitudeE7\": ").append(latE7)
                .append(", \"longitudeE7\": 1390000000, \"timestamp\": \"").append(timestamp).append("\"}")
        }
        builder.append("]}")
        return builder.toString()
    }

    // ---- キャンセル（docs/decisions.md D-021決定1） ----

    /** `isActive`が`false`を返した場合、パースが打ち切られ`CancellationException`が送出されることを検証する。 */
    @Test
    fun parseJson_isActiveBecomesFalse_throwsCancellationException() {
        val json = buildRecordsJson(50_000)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            var callCount = 0
            TimelineJsonParser.parseJson(json.byteInputStream(), isActive = {
                callCount++
                callCount < 3
            })
        }
    }

    /** `isActive`を渡さない（既定`{ true }`）場合、従来通りキャンセルされず最後まで正常にパースできることを検証する。 */
    @Test
    fun parseJson_isActiveOmitted_parsesSuccessfullyWithoutCancellation() {
        val pointCount = 10_000
        val json = buildRecordsJson(pointCount)

        val track = TimelineJsonParser.parseJson(json.byteInputStream())

        assertEquals(pointCount, track.pointCount)
    }

    private fun buildRecordsJson(pointCount: Int): String {
        val locations = (0 until pointCount).joinToString(",") { i ->
            val lat = 350000000L + i
            val timestamp = BASE_TIMESTAMP_MILLIS + i * 1000L
            "{\"latitudeE7\": $lat, \"longitudeE7\": 1390000000, \"timestamp\": \"$timestamp\"}"
        }
        return "{\"locations\": [$locations]}"
    }

    private fun buildZip(entries: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
