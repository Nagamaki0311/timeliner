package com.nagamaki0311.timeliner.data.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimelineJsonParser]のテスト。
 *
 * 既知の制約: `TimelineJsonParser`の本体（`parseJson`/`parseZip`とその内部の形式別パース処理）は
 * `android.util.JsonReader`に依存しており、Android実機/エミュレータ、またはRobolectric等の
 * テストフレームワーク無しにはプレーンなJVM単体テスト（`app/src/test`、`./gradlew testDebugUnitTest`）から
 * 実行できない（`java.lang.RuntimeException: Method ... not mocked`で失敗することを実機確認済み）。
 * Robolectric等の追加依存は導入しない方針（AGENTS.md判定ラダー）のため、
 * ここでは`TimelineJsonParser`内の純Kotlinロジック（zipエントリのパス判定）のみを検証する。
 *
 * 形式A〜D（端末内Timeline Android/iOS、Takeout Semantic Location History、Takeout Records）の
 * 合成JSONに対する実際のパース結果（点数・座標・時刻）は、この制約により自動テストでは検証できていない。
 * 実機またはAndroid実行環境が利用可能になった時点でandroidTestとして追加検証することが望ましい
 * （docs/progress.mdに既知のリスクとして記録する）。
 */
class TimelineJsonParserTest {

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
}
