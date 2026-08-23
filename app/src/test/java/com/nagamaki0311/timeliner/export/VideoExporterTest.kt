package com.nagamaki0311.timeliner.export

import com.nagamaki0311.timeliner.camera.CameraDirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoExporter.computeOutputResolution]・[VideoExporter.buildRouteWorldGeometry]（いずれも純Kotlin、
 * `android.*`非依存、コンパニオンオブジェクトの関数）のみを対象とする。
 * [VideoExporter.export]本体は[org.maplibre.android.maps.MapLibreMap]・`androidx.media3.transformer.Transformer`
 * ・[org.maplibre.android.snapshotter.MapSnapshotter]等の実機依存APIを呼ぶためJVM単体テスト不可
 * （D-003と同様の制約、docs/progress.mdに明記）。
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

    @Test
    fun buildRouteWorldGeometry_pointCountNeverExceedsInput() {
        val timestamps = LongArray(50) { it * 1_000L }
        val latitudes = DoubleArray(50) { 35.0 + it * 0.001 }
        val longitudes = DoubleArray(50) { 139.0 + it * 0.001 }
        val keyframes = listOf(CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 12.0))

        val geometry = VideoExporter.buildRouteWorldGeometry(latitudes, longitudes, timestamps, keyframes)

        assertTrue(geometry.worldXs.size in 1..50)
        assertEquals(geometry.worldXs.size, geometry.worldYs.size)
        assertEquals(geometry.worldXs.size, geometry.timestampsMillis.size)
    }

    @Test
    fun buildRouteWorldGeometry_timestampsRemainAscending() {
        val timestamps = LongArray(30) { it * 1_000L }
        val latitudes = DoubleArray(30) { 35.0 + it * 0.01 }
        val longitudes = DoubleArray(30) { 139.0 + it * 0.01 }
        val keyframes = listOf(
            CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 15.0),
            CameraDirector.CameraKeyframe(5_000L, 35.15, 139.15, 5.0)
        )

        val geometry = VideoExporter.buildRouteWorldGeometry(latitudes, longitudes, timestamps, keyframes)

        for (i in 1 until geometry.timestampsMillis.size) {
            assertTrue(geometry.timestampsMillis[i] > geometry.timestampsMillis[i - 1])
        }
    }

    @Test
    fun buildRouteWorldGeometry_firstAndLastPointsAreAlwaysKept() {
        // Simplifierのepsilonがどのズームレベルから決まっても、先頭・末尾点は間引かれないことを確認する
        // （最も低いズーム＝最も粗いepsilonになりうるキーフレームのみを渡すケース）。
        val timestamps = LongArray(20) { it * 1_000L }
        val latitudes = DoubleArray(20) { 35.0 + it * 0.0001 }
        val longitudes = DoubleArray(20) { 139.0 + it * 0.0001 }
        val keyframes = listOf(CameraDirector.CameraKeyframe(0L, 35.0, 139.0, 0.0))

        val geometry = VideoExporter.buildRouteWorldGeometry(latitudes, longitudes, timestamps, keyframes)

        assertEquals(timestamps.first(), geometry.timestampsMillis.first())
        assertEquals(timestamps.last(), geometry.timestampsMillis.last())
    }
}
