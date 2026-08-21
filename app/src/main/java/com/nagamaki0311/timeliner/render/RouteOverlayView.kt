package com.nagamaki0311.timeliner.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.process.Simplifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * MapLibreの`MapView`の上に重ねてルート線を描画するカスタムView（docs/tasks.md T-006）。
 *
 * [attachMap]で渡された[MapLibreMap]のカメラ位置（中心座標・ズームレベル）から、
 * ワールド座標（[Mercator]）→画面座標への変換を[ScreenProjection]で計算し、[RouteFrameRenderer]へ渡す。
 * 簡略化（[Simplifier]）のepsilonは表示ズームから決まる「画面上2px相当のメートル数」を使う。
 * ズームレベルを整数へ丸めたバケットが変化した時だけDouglas-Peuckerを再実行し（毎フレーム再計算しない）、
 * それ以外のカメラ変化（パン・同一ズームバケット内の微小ズーム）は変換の再計算のみで済ませる。
 *
 * [Simplifier.simplify]自体は560日規模（数十万点）では数十〜数百ms程度かかりうるため、
 * `OnCameraMoveListener`コールバック（UIスレッド）から直接呼ばず、[SIMPLIFY_DEBOUNCE_MILLIS]でデバウンスした上で
 * [Dispatchers.Default]上で実行する（docs/tasks.md T-013）。計算完了までは直前の簡略化結果（未計算時は空）で
 * 描画を継続し、完了時に[cachedZoomBucket]等のキャッシュを更新して再描画する。
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

    /** [scheduleSimplify]で起動した進行中のジョブ。新しい要求が来たらキャンセルする（デバウンス、T-013）。 */
    private var recomputeJob: Job? = null

    /**
     * [findViewTreeLifecycleOwner]の`lifecycleScope`が使えない場合（Viewがまだツリーに接続されていない等）の
     * フォールバック。[onDetachedFromWindow]でキャンセルする。
     */
    private var ownScope: CoroutineScope? = null

    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener { recomputeAndInvalidate() }

    /** 表示するルートの点列（未簡略化、[com.nagamaki0311.timeliner.process.TrackCleaner]適用済み）。 */
    private data class Route(val latitudes: DoubleArray, val longitudes: DoubleArray, val timestampsMillis: LongArray)

    /** [Simplifier.simplify]完了後の結果一式（ワールド座標＋時刻）。 */
    private data class SimplifiedResult(val worldXs: DoubleArray, val worldYs: DoubleArray, val timestamps: LongArray)

    /** カメラに連動させる[MapLibreMap]を設定する。呼び出し元（[com.nagamaki0311.timeliner.ui.TimelineScreen]）が地図準備完了後に呼ぶ。 */
    fun attachMap(map: MapLibreMap) {
        this.map?.removeOnCameraMoveListener(cameraMoveListener)
        this.map = map
        map.addOnCameraMoveListener(cameraMoveListener)
        recomputeAndInvalidate()
    }

    /** 表示するルートの点列を設定する（時刻昇順、[com.nagamaki0311.timeliner.store.PointBlobCodec.DecodedPoints]相当）。 */
    fun setRoute(latitudes: DoubleArray, longitudes: DoubleArray, timestampsMillis: LongArray) {
        // 進行中の簡略化ジョブは古いルートに対するものなので、結果が出ても新しい状態を上書きしないようキャンセルする（T-013）。
        recomputeJob?.cancel()
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
        recomputeJob?.cancel()
        recomputeJob = null
        ownScope?.cancel()
        ownScope = null
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
     * 変換する。未設定時は全区間表示（1.0）。実体は[RouteFrameRenderer.progressAtDataTime]（動画書き出し
     * T-008とも共用する共通ロジック、D-008/T-008で重複実装を解消済み）。
     */
    private fun currentProgress(): Float {
        val dataTime = playbackDataTimeMillis ?: return 1f
        return RouteFrameRenderer.progressAtDataTime(cachedSimplifiedTimestamps, dataTime)
    }

    /**
     * カメラ・ルート・サイズの変化を受けて再描画する。ズームバケットが変わった場合のみ
     * [scheduleSimplify]で簡略化を（デバウンス後、非同期に）再実行し、それ以外は既存の
     * [cachedSimplifiedWorldXs]/[cachedSimplifiedWorldYs]を使った画面座標変換のみを行う（軽量、同期のまま）。
     */
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
            scheduleSimplify(currentRoute, zoomBucket, metersPerPixel)
        }
        updateProjectionAndInvalidate(target, metersPerPixel)
    }

    /** [cachedSimplifiedWorldXs]/[cachedSimplifiedWorldYs]から画面座標を計算し再描画する（軽量、O(n)だがオブジェクト生成なし）。 */
    private fun updateProjectionAndInvalidate(target: LatLng, metersPerPixel: Double) {
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

    /**
     * [SIMPLIFY_DEBOUNCE_MILLIS]待ってから[Simplifier.simplify]を[Dispatchers.Default]上で実行し、
     * 完了したら[cachedSimplifiedWorldXs]等を更新して再描画する（T-013）。連続したカメラ移動で
     * 呼ばれるたびに[recomputeJob]を差し替える（＝進行中のジョブをキャンセルする）ことでデバウンスする。
     */
    private fun scheduleSimplify(currentRoute: Route, zoomBucket: Int, metersPerPixel: Double) {
        recomputeJob?.cancel()
        recomputeJob = viewScope().launch(Dispatchers.Main.immediate) {
            delay(SIMPLIFY_DEBOUNCE_MILLIS)
            ensureActive()

            val epsilonMeters = (metersPerPixel * SIMPLIFY_EPSILON_SCREEN_PIXELS).coerceAtLeast(MIN_EPSILON_METERS)
            val result = withContext(Dispatchers.Default) {
                val keptIndices = Simplifier.simplify(
                    currentRoute.latitudes,
                    currentRoute.longitudes,
                    currentRoute.timestampsMillis,
                    epsilonMeters,
                    maxPointCount = SIMPLIFY_MAX_POINT_COUNT
                )
                SimplifiedResult(
                    worldXs = DoubleArray(keptIndices.size) { Mercator.longitudeToX(currentRoute.longitudes[keptIndices[it]]) },
                    worldYs = DoubleArray(keptIndices.size) { Mercator.latitudeToY(currentRoute.latitudes[keptIndices[it]]) },
                    timestamps = LongArray(keptIndices.size) { currentRoute.timestampsMillis[keptIndices[it]] }
                )
            }
            ensureActive()

            // 計算完了までの間にsetRouteで別のルートへ切り替わっていた場合、古い結果で新しい状態を上書きしない
            // （recomputeJobのキャンセルと二重の安全策、T-013タスク4）。
            if (route !== currentRoute) return@launch

            // ズームバケットがA→B→Aと往復した場合（デバウンス窓内、cachedZoomBucketは計算完了後にしか
            // 更新されないため2回目のA復帰時点で新しいscheduleSimplifyが呼ばれない）、このジョブが対象と
            // していたzoomBucketが計算完了時点の実際のカメラのズームバケットと一致するかを再確認する
            // （route !== currentRouteと同じ「確定直前の二重チェック」パターン、D-019決定1）。
            val latestMap = map ?: return@launch
            val latestTarget = latestMap.cameraPosition.target ?: return@launch
            val latestZoomBucket = Math.round(latestMap.cameraPosition.zoom).toInt()
            if (latestZoomBucket != zoomBucket) return@launch

            cachedSimplifiedWorldXs = result.worldXs
            cachedSimplifiedWorldYs = result.worldYs
            cachedSimplifiedTimestamps = result.timestamps
            cachedZoomBucket = zoomBucket

            updateProjectionAndInvalidate(latestTarget, latestMap.projection.getMetersPerPixelAtLatitude(0.0))
        }
    }

    /**
     * ジョブ起動に使う[CoroutineScope]を返す。[findViewTreeLifecycleOwner]が取得できればその`lifecycleScope`
     * （Viewの生存期間に連動して自動キャンセルされる）を優先し、取得できない場合は自前のスコープへフォールバックする
     * （[onDetachedFromWindow]で明示的にキャンセルする）。
     */
    private fun viewScope(): CoroutineScope =
        findViewTreeLifecycleOwner()?.lifecycleScope
            ?: ownScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { ownScope = it }

    companion object {
        /** 簡略化epsilonに使う「画面上何ピクセル相当か」（docs/tasks.md T-006決定4）。 */
        private const val SIMPLIFY_EPSILON_SCREEN_PIXELS = 2.0

        /** epsilonMetersが0にならないための下限（極端な拡大時の保険）。 */
        private const val MIN_EPSILON_METERS = 0.01

        /**
         * Douglas-Peucker簡略化後に残す点数の上限（画面幅ピクセル数のオーダー、D-007決定2）。
         * [Dispatchers.Default]上での実行対象を、未簡略化の期間全体点列（数万〜十万点規模）に対してではなく、
         * この上限を超えない範囲に抑えるための安全弁。
         */
        private const val SIMPLIFY_MAX_POINT_COUNT = 3000

        /**
         * `OnCameraMoveListener`からの再計算要求のデバウンス間隔（ミリ秒、T-013）。
         * 連続したズーム操作のたびに[Simplifier.simplify]を都度実行しないよう、この間隔だけ待ってから実行する。
         */
        private const val SIMPLIFY_DEBOUNCE_MILLIS = 100L
    }
}
