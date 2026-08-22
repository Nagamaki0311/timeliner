package com.nagamaki0311.timeliner.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nagamaki0311.timeliner.process.CleanOptions
import com.nagamaki0311.timeliner.ui.MapConfig

/**
 * 画面表示（[com.nagamaki0311.timeliner.render.RouteOverlayView]）と動画書き出し（T-008）で共用する、
 * ルート線・現在位置マーカー・日時・地図帰属表示の描画ロジック（docs/tasks.md T-006）。
 *
 * `android.graphics.Canvas`に依存するため、`android.util.JsonReader`と同様の理由（D-003）でJVM単体テストの
 * 対象外。ワールド座標→画面座標のアフィン変換部分は依存のない[ScreenProjection]へ切り出し済みで、
 * そちらはJVM単体テストで検証する。
 */
object RouteFrameRenderer {

    /**
     * 描画スタイル（色・線太さ等）。呼び出し側でテーマに応じて差し替え可能にする。
     *
     * [Paint]/[Path]は呼び出しのたび（毎フレーム）に生成せず、この[Style]インスタンス自身に
     * `by lazy`で保持して使い回す（T-006レビュー指摘・D-007決定3。T-008の動画書き出しで同じ
     * レンダラーを数百〜数千フレーム分連続描画する際のアロケーション削減が目的）。
     * 同一の[Style]インスタンスを呼び出し元が使い回すことで初めて効果があるため、
     * デフォルト引数の解決先は[DEFAULT_STYLE]（シングルトン）とする。
     */
    data class Style(
        /** 直近ウィンドウ内（[RECENT_WINDOW_MILLIS]、T-020）の目立つ線色。既存の見た目を維持するため既定値は変更しない。 */
        val routeColor: Int = Color.parseColor("#1976D2"),
        val routeStrokeWidthPx: Float = 6f,
        /** 直近ウィンドウより前（過去）の控えめな線色。既定は[routeColor]と同色相・半透明・やや細め（T-020）。 */
        val pastRouteColor: Int = Color.parseColor("#801976D2"),
        val pastRouteStrokeWidthPx: Float = 4f,
        val markerRadiusPx: Float = 8f,
        val markerColor: Int = Color.parseColor("#D32F2F"),
        val markerStrokeColor: Int = Color.WHITE,
        val markerStrokeWidthPx: Float = 2f,
        val dateTimeTextSizePx: Float = 40f,
        val dateTimeTextColor: Int = Color.BLACK,
        val attributionTextSizePx: Float = 22f,
        val attributionTextColor: Int = Color.DKGRAY,
        val paddingPx: Float = 16f
    ) {
        val routePaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeColor
                strokeWidth = routeStrokeWidthPx
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
        val pastRoutePaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pastRouteColor
                strokeWidth = pastRouteStrokeWidthPx
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
        val markerFillPaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor
                style = Paint.Style.FILL
            }
        }
        val markerStrokePaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerStrokeColor
                style = Paint.Style.STROKE
                strokeWidth = markerStrokeWidthPx
            }
        }
        val dateTimeTextPaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dateTimeTextColor
                textSize = dateTimeTextSizePx
                textAlign = Paint.Align.LEFT
            }
        }
        val attributionPaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = attributionTextColor
                textSize = attributionTextSizePx
                textAlign = Paint.Align.RIGHT
            }
        }

        /** 直近区間の描画用に使い回す[Path]。[drawRoute]内で毎回[Path.reset]してから再構築する。 */
        val routePath: Path by lazy { Path() }

        /** 過去区間の描画用に使い回す[Path]。[routePath]とは別インスタンス（同一フレーム内で両方使うため）。 */
        val pastRoutePath: Path by lazy { Path() }
    }

    /** [draw]のデフォルト引数として使うシングルトンの[Style]。Paint/Pathキャッシュを有効にするため、呼び出しごとに新規生成しない。 */
    private val DEFAULT_STYLE = Style()

    /** ギャップ分断の閾値（既定[CleanOptions.segmentGapMillis]と同じ6時間、T-020）。 */
    private val GAP_BREAK_MILLIS = CleanOptions().segmentGapMillis

    /**
     * 「直近」とみなすウィンドウ長（T-020）。停留・小休止（数十分程度）は直近扱いのまま維持しつつ、
     * 「今まさに通っている軌跡」を過去全体から視覚的に切り出すには短すぎない値として2時間を既定値とする
     * （数十分では停留のたびに過去/直近の描き分けが頻繁に切り替わり煩雑になり、逆に半日規模だと
     * 「直近」が実質「その日全体」になり過去との描き分けが機能しなくなるため）。
     */
    private const val RECENT_WINDOW_MILLIS = 2L * 60 * 60 * 1000

    /** 画面上の1点（ピクセル）。[RouteOverlayView]・T-008双方から使う最小限の値保持のみのデータクラス。 */
    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * @param canvas 描画先。
     * @param screenCoordinates 画面座標へ変換済みの点列（`x0, y0, x1, y1, ...`、時刻昇順）。
     * @param timestampsMillis [screenCoordinates]と対応する時刻昇順の配列（点数は`screenCoordinates.size / 2`と
     *   一致すること、T-020）。ギャップ分断（[GAP_BREAK_MILLIS]超で線を分断）と過去/直近の描き分け
     *   （[RECENT_WINDOW_MILLIS]）に使う。点数が一致しない場合は防御的フォールバックとして分断・描き分け無しの
     *   単一スタイル（[Style.routePaint]）で描画する。
     * @param progress 描画する区間の進捗（0.0=先頭点のみ、1.0=全区間）。T-007で再生中の現在時刻に応じた
     *   値を渡す（[com.nagamaki0311.timeliner.render.RouteOverlayView]）。
     * @param dateTimeText 画面下部に表示する日時テキスト。nullなら描画しない。
     */
    fun draw(
        canvas: Canvas,
        screenCoordinates: FloatArray,
        timestampsMillis: LongArray,
        progress: Float,
        dateTimeText: String?,
        style: Style = DEFAULT_STYLE
    ) {
        // trimByProgressの結果（FloatArray確保＋arraycopy、最大3000点分）をルート線描画と現在位置マーカーの
        // 両方で使い回す。以前はcurrentPositionAtProgressとdrawRouteが独立に計算し毎フレーム2回無駄が発生していた
        // （docs/decisions.md D-008決定2）。
        val trimmed = trimByProgress(screenCoordinates, timestampsMillis, progress.coerceIn(0f, 1f))
        drawRoute(canvas, trimmed.screenCoordinates, trimmed.timestampsMillis, style)
        markerPosition(trimmed.screenCoordinates)?.let { drawMarker(canvas, it, style) }
        if (dateTimeText != null) {
            drawDateTimeText(canvas, dateTimeText, style)
        }
        drawAttribution(canvas, style)
    }

    /**
     * [timestampsMillis]（時刻昇順、[draw]へ渡す`screenCoordinates`と対応する点列の時刻）上で
     * [dataTimeMillis]が占める位置を、[draw]の`progress`引数（0.0〜1.0）へ変換する。
     * 二分探索＋線形補間で「時刻→点インデックスの連続的な位置」を求める。
     *
     * 画面再生（[com.nagamaki0311.timeliner.render.RouteOverlayView]）と動画書き出し
     * （[com.nagamaki0311.timeliner.export.RouteBitmapOverlay]、docs/tasks.md T-008）で共用する。
     * `Canvas`に依存しない純Kotlin関数のため、[draw]自体とは異なりJVM単体テストで検証できる
     * （旧`RouteOverlayView.currentProgress`にあった重複実装をこちらへ集約した）。
     */
    fun progressAtDataTime(timestampsMillis: LongArray, dataTimeMillis: Long): Float {
        val pointCount = timestampsMillis.size
        if (pointCount <= 1) return 1f
        if (dataTimeMillis <= timestampsMillis[0]) return 0f
        if (dataTimeMillis >= timestampsMillis[pointCount - 1]) return 1f

        val searchResult = timestampsMillis.binarySearch(dataTimeMillis)
        val exactIndex = if (searchResult >= 0) {
            searchResult.toDouble()
        } else {
            val hi = (-searchResult - 1).coerceIn(1, pointCount - 1)
            val lo = hi - 1
            val tLo = timestampsMillis[lo]
            val tHi = timestampsMillis[hi]
            val fraction = if (tHi == tLo) 0.0 else (dataTimeMillis - tLo).toDouble() / (tHi - tLo).toDouble()
            lo + fraction
        }
        return (exactIndex / (pointCount - 1)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * [timestampsMillis]（時刻昇順）上で、隣接点間の経過時間が[gapMillis]（既定[GAP_BREAK_MILLIS]、
     * [com.nagamaki0311.timeliner.process.TrackCleaner]の分断閾値と同じ6時間）を超える箇所のインデックス
     * 一覧を昇順で返す（T-020）。返されるインデックスの点を描画する際は`lineTo`ではなく`moveTo`することで、
     * 実際には移動していない長時間欠損区間が直線で結ばれることを防ぐ（[drawRoute]用）。
     *
     * [com.nagamaki0311.timeliner.store.RouteOverview.computeBreakIndices]と同種の考え方だが、あちらは
     * 日境界も含めた概観点列全体のインデックスを扱うのに対し、こちらは[draw]の表示区間（[trimByProgress]後）に
     * 限定したローカルインデックスのみを扱う。用途（概観の日境界描画 vs 再生・書き出しのルート線分断）が
     * 異なり無理に共有すると結合が強くなるため、独立実装とする。
     */
    fun computeGapBreakIndices(timestampsMillis: LongArray, gapMillis: Long = GAP_BREAK_MILLIS): IntArray {
        val breaks = ArrayList<Int>()
        for (i in 1 until timestampsMillis.size) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > gapMillis) breaks.add(i)
        }
        return breaks.toIntArray()
    }

    /**
     * [timestampsMillis]（時刻昇順）の末尾時刻（現在データ時刻に相当）から遡って[windowMillis]
     * （既定[RECENT_WINDOW_MILLIS]）以内に収まる最初のインデックスを返す（T-020）。このインデックス以降が
     * 「直近」（[Style.routePaint]で描画）、それより前が「過去」（[Style.pastRoutePaint]で描画）となる
     * 境界を表す（[drawRoute]用）。配列が空の場合は0を返す。
     *
     * 時刻昇順であることを前提に二分探索（下限探索）で求める。
     */
    fun recentWindowStartIndex(timestampsMillis: LongArray, windowMillis: Long = RECENT_WINDOW_MILLIS): Int {
        val pointCount = timestampsMillis.size
        if (pointCount == 0) return 0
        val threshold = timestampsMillis[pointCount - 1] - windowMillis
        var lowIndex = 0
        var highIndex = pointCount - 1
        while (lowIndex < highIndex) {
            val midIndex = (lowIndex + highIndex) / 2
            if (timestampsMillis[midIndex] >= threshold) {
                highIndex = midIndex
            } else {
                lowIndex = midIndex + 1
            }
        }
        return lowIndex
    }

    /** [trimByProgress]で切り詰め済みの点列の末尾点を現在位置マーカーの画面座標として返す。点が無い場合はnull。 */
    private fun markerPosition(trimmedScreenCoordinates: FloatArray): ScreenPoint? {
        if (trimmedScreenCoordinates.size < 2) return null
        return ScreenPoint(
            trimmedScreenCoordinates[trimmedScreenCoordinates.size - 2],
            trimmedScreenCoordinates[trimmedScreenCoordinates.size - 1]
        )
    }

    /** [trimByProgress]の結果一式。[screenCoordinates]と[timestampsMillis]は同じ点数へ揃える（点数が一致しない入力を受けた場合は空の[LongArray]を返す）。 */
    private data class TrimResult(val screenCoordinates: FloatArray, val timestampsMillis: LongArray)

    /**
     * [progress]（0.0〜1.0）に応じて描画対象の点列を先頭から切り詰める。区間の途中で切れる場合、
     * 直前・直後の点を線形補間して滑らかな終端を作る（T-007のアニメーション用）。[timestampsMillis]も
     * 同じ切り詰め・補間を行い[screenCoordinates]と対応させる（T-020）。
     */
    private fun trimByProgress(screenCoordinates: FloatArray, timestampsMillis: LongArray, progress: Float): TrimResult {
        val pointCount = screenCoordinates.size / 2
        val hasTimestamps = timestampsMillis.size == pointCount
        val safeTimestamps = if (hasTimestamps) timestampsMillis else LongArray(0)
        if (pointCount <= 1 || progress >= 1f) return TrimResult(screenCoordinates, safeTimestamps)

        val exactIndex = progress * (pointCount - 1)
        val wholeIndex = exactIndex.toInt()
        val fraction = exactIndex - wholeIndex
        val keptPointCount = wholeIndex + 1
        val hasInterpolatedTail = fraction > 0f && wholeIndex + 1 < pointCount
        val trimmedCount = keptPointCount + if (hasInterpolatedTail) 1 else 0

        val resultCoordinates = FloatArray(trimmedCount * 2)
        System.arraycopy(screenCoordinates, 0, resultCoordinates, 0, keptPointCount * 2)

        val resultTimestamps = if (hasTimestamps) LongArray(trimmedCount) else LongArray(0)
        if (hasTimestamps) System.arraycopy(timestampsMillis, 0, resultTimestamps, 0, keptPointCount)

        if (hasInterpolatedTail) {
            val ax = screenCoordinates[wholeIndex * 2]
            val ay = screenCoordinates[wholeIndex * 2 + 1]
            val bx = screenCoordinates[(wholeIndex + 1) * 2]
            val by = screenCoordinates[(wholeIndex + 1) * 2 + 1]
            resultCoordinates[keptPointCount * 2] = ax + (bx - ax) * fraction
            resultCoordinates[keptPointCount * 2 + 1] = ay + (by - ay) * fraction
            if (hasTimestamps) {
                val tA = timestampsMillis[wholeIndex]
                val tB = timestampsMillis[wholeIndex + 1]
                resultTimestamps[keptPointCount] = tA + ((tB - tA) * fraction).toLong()
            }
        }
        return TrimResult(resultCoordinates, resultTimestamps)
    }

    /**
     * ルート線を描画する。[timestampsMillis]の点数が[screenCoordinates]の点数と一致する場合のみ、
     * ギャップ分断（[computeGapBreakIndices]）と過去/直近の描き分け（[recentWindowStartIndex]）を行う。
     * 一致しない場合（防御的フォールバック）は、分断・描き分け無しの単一スタイル（[Style.routePaint]）で
     * T-020導入前と同じ見た目で描画する。
     */
    // 引数名を`frameStyle`とするのは、パラメータ型`Style`と紛らわしい小文字名を避けるため。
    private fun drawRoute(canvas: Canvas, screenCoordinates: FloatArray, timestampsMillis: LongArray, frameStyle: Style) {
        val pointCount = screenCoordinates.size / 2
        if (pointCount < 2) return

        if (timestampsMillis.size != pointCount) {
            drawPathSegment(canvas, screenCoordinates, 0, pointCount - 1, IntArray(0), frameStyle.routePath, frameStyle.routePaint)
            return
        }

        val breakIndices = computeGapBreakIndices(timestampsMillis)
        val recentStartIndex = recentWindowStartIndex(timestampsMillis)

        if (recentStartIndex > 0) {
            drawPathSegment(canvas, screenCoordinates, 0, recentStartIndex, breakIndices, frameStyle.pastRoutePath, frameStyle.pastRoutePaint)
        }
        drawPathSegment(canvas, screenCoordinates, recentStartIndex, pointCount - 1, breakIndices, frameStyle.routePath, frameStyle.routePaint)
    }

    /**
     * [screenCoordinates]の`[startIndex, endIndexInclusive]`区間を[path]へ構築し[paint]で描画する。
     * [breakIndices]に含まれるインデックスの点は`lineTo`ではなく`moveTo`で打ち直し、ギャップで分断する。
     * 区間の点数が1以下（線を引けない）の場合は何もしない。
     */
    private fun drawPathSegment(
        canvas: Canvas,
        screenCoordinates: FloatArray,
        startIndex: Int,
        endIndexInclusive: Int,
        breakIndices: IntArray,
        path: Path,
        paint: Paint
    ) {
        if (endIndexInclusive <= startIndex) return
        path.reset()
        path.moveTo(screenCoordinates[startIndex * 2], screenCoordinates[startIndex * 2 + 1])
        for (i in startIndex + 1..endIndexInclusive) {
            if (breakIndices.binarySearch(i) >= 0) {
                path.moveTo(screenCoordinates[i * 2], screenCoordinates[i * 2 + 1])
            } else {
                path.lineTo(screenCoordinates[i * 2], screenCoordinates[i * 2 + 1])
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMarker(canvas: Canvas, position: ScreenPoint, frameStyle: Style) {
        canvas.drawCircle(position.x, position.y, frameStyle.markerRadiusPx, frameStyle.markerFillPaint)
        canvas.drawCircle(position.x, position.y, frameStyle.markerRadiusPx, frameStyle.markerStrokePaint)
    }

    private fun drawDateTimeText(canvas: Canvas, text: String, frameStyle: Style) {
        canvas.drawText(
            text,
            frameStyle.paddingPx,
            frameStyle.paddingPx + frameStyle.dateTimeTextSizePx,
            frameStyle.dateTimeTextPaint
        )
    }

    private fun drawAttribution(canvas: Canvas, frameStyle: Style) {
        canvas.drawText(
            MapConfig.ATTRIBUTION_TEXT,
            canvas.width - frameStyle.paddingPx,
            canvas.height - frameStyle.paddingPx,
            frameStyle.attributionPaint
        )
    }
}
