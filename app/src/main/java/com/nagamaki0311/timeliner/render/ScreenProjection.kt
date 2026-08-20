package com.nagamaki0311.timeliner.render

/**
 * Webメルカトルのワールド座標（メートル、[com.nagamaki0311.timeliner.process.Mercator]）から
 * 画面座標（ピクセル）への平行移動＋等方スケールのアフィン変換（docs/tasks.md T-006）。
 *
 * [com.nagamaki0311.timeliner.render.RouteOverlayView]がMapLibreの`MapView`上に重ねる描画のために使う。
 * 回転・チルトは無効化済み（[com.nagamaki0311.timeliner.ui.MapContainer]）のため、この変換で十分。
 * `android.graphics.Canvas`等のAndroid API に一切依存しない純Kotlin関数として切り出し、JVM単体テスト可能にする
 * （docs/tasks.md T-006のテスト要件、D-003/D-004と同様の制約への対応）。
 */
object ScreenProjection {

    /**
     * @param worldXs/worldYs 変換元のワールド座標（メートル）。同じ長さであること。
     * @param centerWorldX/centerWorldY 画面中心に描画されるワールド座標（通常はカメラ中心）。
     * @param metersPerPixel 1画面ピクセルあたりのメートル数（ズームレベル・緯度から決まる、必ず正の値）。
     * @param screenWidthPx/screenHeightPx 描画先Viewの幅・高さ（ピクセル）。
     * @return 画面座標を`x0, y0, x1, y1, ...`の順にインターリーブしたFloatArray（長さは`worldXs.size * 2`）。
     *   スクリーンY軸は下方向が正だが、ワールドY（[com.nagamaki0311.timeliner.process.Mercator.latitudeToY]）は
     *   北方向が正のため、Y成分は符号を反転する。
     */
    fun toScreenCoordinates(
        worldXs: DoubleArray,
        worldYs: DoubleArray,
        centerWorldX: Double,
        centerWorldY: Double,
        metersPerPixel: Double,
        screenWidthPx: Float,
        screenHeightPx: Float
    ): FloatArray {
        require(worldXs.size == worldYs.size) {
            "worldXs/worldYsの配列長が一致しません: x=${worldXs.size}, y=${worldYs.size}"
        }
        require(metersPerPixel > 0.0) { "metersPerPixelは正の値である必要があります: $metersPerPixel" }

        val centerScreenX = screenWidthPx / 2.0
        val centerScreenY = screenHeightPx / 2.0
        val result = FloatArray(worldXs.size * 2)
        for (i in worldXs.indices) {
            val screenX = centerScreenX + (worldXs[i] - centerWorldX) / metersPerPixel
            val screenY = centerScreenY - (worldYs[i] - centerWorldY) / metersPerPixel
            result[i * 2] = screenX.toFloat()
            result[i * 2 + 1] = screenY.toFloat()
        }
        return result
    }
}
