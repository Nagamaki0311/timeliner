package com.nagamaki0311.timeliner.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenProjectionTest {

    @Test
    fun toScreenCoordinates_centerWorldPoint_mapsToScreenCenter() {
        val result = ScreenProjection.toScreenCoordinates(
            worldXs = doubleArrayOf(1000.0),
            worldYs = doubleArrayOf(2000.0),
            centerWorldX = 1000.0,
            centerWorldY = 2000.0,
            metersPerPixel = 10.0,
            screenWidthPx = 800f,
            screenHeightPx = 600f
        )

        assertEquals(400f, result[0], 1e-6f)
        assertEquals(300f, result[1], 1e-6f)
    }

    @Test
    fun toScreenCoordinates_offsetPoints_scaleByMetersPerPixelAndFlipY() {
        val result = ScreenProjection.toScreenCoordinates(
            worldXs = doubleArrayOf(0.0, 100.0, -50.0),
            worldYs = doubleArrayOf(0.0, 0.0, 20.0),
            centerWorldX = 0.0,
            centerWorldY = 0.0,
            metersPerPixel = 5.0,
            screenWidthPx = 400f,
            screenHeightPx = 400f
        )

        // 東(+X)に100mは画面右へ100/5=20px、中心200pxなので220px。Yは変化なし。
        assertEquals(220f, result[2], 1e-6f)
        assertEquals(200f, result[3], 1e-6f)

        // 西(-X)に50mは画面左へ50/5=10px、中心200pxなので190px。北(+Y、ワールド座標)に20mは画面では上方向(Y減少)へ20/5=4px。
        assertEquals(190f, result[4], 1e-6f)
        assertEquals(196f, result[5], 1e-6f)
    }

    @Test
    fun toScreenCoordinates_mismatchedArrayLengths_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenProjection.toScreenCoordinates(
                worldXs = doubleArrayOf(0.0, 1.0),
                worldYs = doubleArrayOf(0.0),
                centerWorldX = 0.0,
                centerWorldY = 0.0,
                metersPerPixel = 1.0,
                screenWidthPx = 100f,
                screenHeightPx = 100f
            )
        }
    }

    @Test
    fun toScreenCoordinates_nonPositiveMetersPerPixel_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenProjection.toScreenCoordinates(
                worldXs = doubleArrayOf(0.0),
                worldYs = doubleArrayOf(0.0),
                centerWorldX = 0.0,
                centerWorldY = 0.0,
                metersPerPixel = 0.0,
                screenWidthPx = 100f,
                screenHeightPx = 100f
            )
        }
    }
}
