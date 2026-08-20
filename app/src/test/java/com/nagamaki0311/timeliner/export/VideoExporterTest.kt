package com.nagamaki0311.timeliner.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [VideoExporter.computeOutputResolution]（純Kotlin、`android.*`非依存）のみを対象とする。
 * [VideoExporter.export]本体は[org.maplibre.android.maps.MapLibreMap]・`androidx.media3.transformer.Transformer`
 * 等の実機依存APIを呼ぶためJVM単体テスト不可（D-003と同様の制約、docs/progress.mdに明記）。
 */
class VideoExporterTest {

    @Test
    fun computeOutputResolution_shortSideBelowCap_keepsOriginalSize() {
        val (width, height) = VideoExporter.computeOutputResolution(800, 600)
        assertEquals(800, width)
        assertEquals(600, height)
    }

    @Test
    fun computeOutputResolution_shortSideAboveCap_scalesDownPreservingAspectRatio() {
        // 短辺(高さ1440)が1080を超えるため、幅2560も同じ比率(0.75)で縮小される想定。
        val (width, height) = VideoExporter.computeOutputResolution(2560, 1440)
        assertEquals(1080, height)
        assertEquals(1920, width)
    }

    @Test
    fun computeOutputResolution_portraitOrientation_scalesByWidth() {
        // 縦長(短辺=幅1440)の場合も同じ比率で縮小され、短辺が1080になる。
        val (width, height) = VideoExporter.computeOutputResolution(1440, 2560)
        assertEquals(1080, width)
        assertEquals(1920, height)
    }

    @Test
    fun computeOutputResolution_alwaysReturnsEvenDimensions() {
        // 1081x721はキャップ後も奇数になりうる入力(H.264エンコーダの偶数制約を満たすため丸められること)。
        val (width, height) = VideoExporter.computeOutputResolution(1081, 721)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun computeOutputResolution_nonPositiveInput_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoExporter.computeOutputResolution(0, 600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExporter.computeOutputResolution(800, -1)
        }
    }
}
