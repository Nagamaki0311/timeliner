package com.nagamaki0311.timeliner.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.process.Simplifier
import org.maplibre.android.maps.MapLibreMap

/**
 * MapLibreの`MapView`の上に重ねてルート線を描画するカスタムView（docs/tasks.md T-006）。
 *
 * [attachMap]で渡された[MapLibreMap]のカメラ位置（中心座標・ズームレベル）から、
 * ワールド座標（[Mercator]）→画面座標への変換を[ScreenProjection]で計算し、[RouteFrameRenderer]へ渡す。
 * 簡略化（[Simplifier]）のepsilonは表示ズームから決まる「画面上2px相当のメートル数」を使う。
 * ズームレベルを整数へ丸めたバケットが変化した時だけDouglas-Peuckerを再実行し（毎フレーム再計算しない）、
 * それ以外のカメラ変化（パン・同一ズームバケット内の微小ズーム）は変換の再計算のみで済ませる。
 */
class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var map: MapLibreMap? = null
    private var route: Route? = null
    private var dateTimeText: String? = null

    /**
     * 再生中の現在データ時刻（epochミリ秒、[com.nagamaki0311.timeliner.playback.PlaybackController]から設定）。
     * nullの場合は全区間表示（T-006までの挙動、進捗1.0固定）にフォールバックする。
     */
    private var playbackDataTimeMillis: Long? = null

    private var cachedZoomBucket = Int.MIN_VALUE
    private var cachedSimplifiedWorldXs = DoubleArray(0)
    private var cachedSimplifiedWorldYs = DoubleArray(0)

    /** [cachedSimplifiedWorldXs]/[cachedSimplifiedWorldYs]と同じインデックスに対応する時刻（時刻昇順）。 */
    private var cachedSimplifiedTimestamps = LongArray(0)
    private var screenCoordinates = FloatArray(0)

    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener { recomputeAndInvalidate() }

    /** 表示するルートの点列（未簡略化、[com.nagamaki0311.timeliner.process.TrackCleaner]適用済み）。 */
    private data class Route(val latitudes: DoubleArray, val longitudes: DoubleArray, val timestampsMillis: LongArray)

    /** カメラに連動させる[MapLibreMap]を設定する。呼び出し元（[com.nagamaki0311.timeliner.ui.TimelineScreen]）が地図準備完了後に呼ぶ。 */
    fun attachMap(map: MapLibreMap) {
        this.map?.removeOnCameraMoveListener(cameraMoveListener)
        this.map = map
        map.addOnCameraMoveListener(cameraMoveListener)
        recomputeAndInvalidate()
    }

    /** 表示するルートの点列を設定する（時刻昇順、[com.nagamaki0311.timeliner.store.PointBlobCodec.DecodedPoints]相当）。 */
    fun setRoute(latitudes: DoubleArray, longitudes: DoubleArray, timestampsMillis: LongArray) {
        route = if (latitudes.isEmpty()) null else Route(latitudes, longitudes, timestampsMillis)
        cachedZoomBucket = Int.MIN_VALUE // ルートが変わったら簡略化を強制的に再実行する
        recomputeAndInvalidate()
    }

    /** 画面下部に表示する日時テキストを設定する。 */
    fun setDateTimeText(text: String?) {
        dateTimeText = text
        invalidate()
    }

    /**
     * 再生中の現在データ時刻を設定する（T-007）。ルート線をこの時刻まで描画し、現在位置マーカーを
     * その地点へ移動する。nullを渡すと全区間表示（進捗1.0固定、T-006までの挙動）に戻る。
     */
    fun setPlaybackDataTimeMillis(dataTimeMillis: Long?) {
        playbackDataTimeMillis = dataTimeMillis
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeAndInvalidate()
    }

    override fun onDetachedFromWindow() {
        map?.removeOnCameraMoveListener(cameraMoveListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        RouteFrameRenderer.draw(
            canvas = canvas,
            screenCoordinates = screenCoordinates,
            progress = currentProgress(),
            dateTimeText = dateTimeText
        )
    }

    /**
     * [playbackDataTimeMillis]（現在データ時刻）を、[cachedSimplifiedTimestamps]上での位置（0.0〜1.0）へ
     * 二分探索＋線形補間で変換する。未設定時は全区間表示（1.0）。
     */
    private fun currentProgress(): Float {
        val dataTime = playbackDataTimeMillis ?: return 1f
        val timestamps = cachedSimplifiedTimestamps
        val pointCount = timestamps.size
        if (pointCount <= 1) return 1f
        if (dataTime <= timestamps[0]) return 0f
        if (dataTime >= timestamps[pointCount - 1]) return 1f

        val searchResult = timestamps.binarySearch(dataTime)
        val exactIndex = if (searchResult >= 0) {
            searchResult.toDouble()
        } else {
            val hi = (-searchResult - 1).coerceIn(1, pointCount - 1)
            val lo = hi - 1
            val tLo = timestamps[lo]
            val tHi = timestamps[hi]
            val fraction = if (tHi == tLo) 0.0 else (dataTime - tLo).toDouble() / (tHi - tLo).toDouble()
            lo + fraction
        }
        return (exactIndex / (pointCount - 1)).toFloat().coerceIn(0f, 1f)
    }

    private fun recomputeAndInvalidate() {
        val currentRoute = route
        val currentMap = map
        if (currentRoute == null || currentMap == null || width <= 0 || height <= 0) {
            screenCoordinates = FloatArray(0)
            invalidate()
            return
        }

        val cameraPosition = currentMap.cameraPosition
        val target = cameraPosition.target
        if (target == null) {
            screenCoordinates = FloatArray(0)
            invalidate()
            return
        }
        // Mercator.longitudeToX/latitudeToYが返すワールド座標は緯度非依存の一定スケールを持つ投影座標
        // （MapLibre自身の描画空間）であるため、緯度で変動する実世界距離基準のmetersPerPixelと組み合わせると
        // 赤道以外でズレる。緯度0固定で取得し、投影メートル/ピクセルと一致させる（D-007決定1）。
        val metersPerPixel = currentMap.projection.getMetersPerPixelAtLatitude(0.0)
        val zoomBucket = Math.round(cameraPosition.zoom).toInt()
        if (zoomBucket != cachedZoomBucket) {
            val epsilonMeters = (metersPerPixel * SIMPLIFY_EPSILON_SCREEN_PIXELS).coerceAtLeast(MIN_EPSILON_METERS)
            val keptIndices = Simplifier.simplify(
                currentRoute.latitudes,
                currentRoute.longitudes,
                currentRoute.timestampsMillis,
                epsilonMeters,
                maxPointCount = SIMPLIFY_MAX_POINT_COUNT
            )
            cachedSimplifiedWorldXs = DoubleArray(keptIndices.size) { Mercator.longitudeToX(currentRoute.longitudes[keptIndices[it]]) }
            cachedSimplifiedWorldYs = DoubleArray(keptIndices.size) { Mercator.latitudeToY(currentRoute.latitudes[keptIndices[it]]) }
            cachedSimplifiedTimestamps = LongArray(keptIndices.size) { currentRoute.timestampsMillis[keptIndices[it]] }
            cachedZoomBucket = zoomBucket
        }

        val centerWorldX = Mercator.longitudeToX(target.longitude)
        val centerWorldY = Mercator.latitudeToY(target.latitude)
        screenCoordinates = ScreenProjection.toScreenCoordinates(
            worldXs = cachedSimplifiedWorldXs,
            worldYs = cachedSimplifiedWorldYs,
            centerWorldX = centerWorldX,
            centerWorldY = centerWorldY,
            metersPerPixel = metersPerPixel,
            screenWidthPx = width.toFloat(),
            screenHeightPx = height.toFloat()
        )
        invalidate()
    }

    companion object {
        /** 簡略化epsilonに使う「画面上何ピクセル相当か」（docs/tasks.md T-006決定4）。 */
        private const val SIMPLIFY_EPSILON_SCREEN_PIXELS = 2.0

        /** epsilonMetersが0にならないための下限（極端な拡大時の保険）。 */
        private const val MIN_EPSILON_METERS = 0.01

        /**
         * Douglas-Peucker簡略化後に残す点数の上限（画面幅ピクセル数のオーダー、D-007決定2）。
         * UIスレッド（`OnCameraMoveListener`コールバック）上での同期実行を、未簡略化の期間全体点列
         * （数万〜十万点規模）に対してではなく、この上限を超えない範囲に抑えるための安全弁。
         */
        private const val SIMPLIFY_MAX_POINT_COUNT = 3000
    }
}
