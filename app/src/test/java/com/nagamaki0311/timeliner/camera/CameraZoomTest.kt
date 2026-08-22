package com.nagamaki0311.timeliner.camera

import com.nagamaki0311.timeliner.process.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomTest {

    @Test
    fun zoomToFitBounds_worldSpanningLongitudeAtTileSizeViewport_zoomIsZero() {
        // 経度全体(360度)、緯度は0付近に潰した(fraction=0、latZoom=MAX_ZOOMにフォールバック)ため、
        // 経度側のみが効く。viewport=256px（タイル1枚分）なら定義上ズーム0になる。
        val bounds = GeoBounds.Bounds(minLatitude = 0.0, maxLatitude = 0.0, minLongitude = -180.0, maxLongitude = 180.0)

        val zoom = CameraZoom.zoomToFitBounds(bounds, viewportWidthPx = 256, viewportHeightPx = 256)

        assertEquals(0.0, zoom, 1e-9)
    }

    @Test
    fun zoomToFitBounds_halfWorldLongitude_zoomIsOne() {
        // 経度180度分(全体の半分)。viewport=256pxなら定義上ズーム1になる（1タイルが半分の経度をカバー）。
        val bounds = GeoBounds.Bounds(minLatitude = 0.0, maxLatitude = 0.0, minLongitude = -90.0, maxLongitude = 90.0)

        val zoom = CameraZoom.zoomToFitBounds(bounds, viewportWidthPx = 256, viewportHeightPx = 256)

        assertEquals(1.0, zoom, 1e-9)
    }

    @Test
    fun zoomToFitBounds_degenerateSinglePoint_returnsMaxZoom() {
        val bounds =
            GeoBounds.Bounds(minLatitude = 35.0, maxLatitude = 35.0, minLongitude = 139.0, maxLongitude = 139.0)

        val zoom = CameraZoom.zoomToFitBounds(bounds, viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertEquals(CameraZoom.MAX_ZOOM, zoom, 1e-9)
    }

    @Test
    fun zoomToFitBounds_widerBoundsYieldsLowerZoomThanNarrowerBounds() {
        val narrow =
            GeoBounds.Bounds(minLatitude = 35.000, maxLatitude = 35.001, minLongitude = 139.000, maxLongitude = 139.001)
        val wide = GeoBounds.Bounds(minLatitude = 30.0, maxLatitude = 40.0, minLongitude = 130.0, maxLongitude = 145.0)

        val narrowZoom = CameraZoom.zoomToFitBounds(narrow, viewportWidthPx = 1080, viewportHeightPx = 1080)
        val wideZoom = CameraZoom.zoomToFitBounds(wide, viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertEquals(CameraZoom.MAX_ZOOM, narrowZoom, 1e-9)
        assertEquals(true, wideZoom < narrowZoom)
    }

    @Test
    fun zoomToFitBounds_neverExceedsMaxZoomOrGoesBelowZero() {
        val extremelyNarrow = GeoBounds.Bounds(
            minLatitude = 35.00000001,
            maxLatitude = 35.00000002,
            minLongitude = 139.00000001,
            maxLongitude = 139.00000002
        )
        val worldwide = GeoBounds.Bounds(minLatitude = -85.0, maxLatitude = 85.0, minLongitude = -180.0, maxLongitude = 180.0)

        val zoomNarrow = CameraZoom.zoomToFitBounds(extremelyNarrow, viewportWidthPx = 64, viewportHeightPx = 64)
        val zoomWide = CameraZoom.zoomToFitBounds(worldwide, viewportWidthPx = 4096, viewportHeightPx = 4096)

        assertEquals(true, zoomNarrow <= CameraZoom.MAX_ZOOM)
        assertEquals(true, zoomWide >= 0.0)
    }
}
