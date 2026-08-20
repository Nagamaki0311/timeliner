package com.nagamaki0311.timeliner.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        val routeColor: Int = Color.parseColor("#1976D2"),
        val routeStrokeWidthPx: Float = 6f,
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

        /** ルート線描画用に使い回す[Path]。[drawRoute]内で毎回[Path.reset]してから再構築する。 */
        val routePath: Path by lazy { Path() }
    }

    /** [draw]のデフォルト引数として使うシングルトンの[Style]。Paint/Pathキャッシュを有効にするため、呼び出しごとに新規生成しない。 */
    private val DEFAULT_STYLE = Style()

    /** 画面上の1点（ピクセル）。[RouteOverlayView]・T-008双方から使う最小限の値保持のみのデータクラス。 */
    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * @param canvas 描画先。
     * @param screenCoordinates 画面座標へ変換済みの点列（`x0, y0, x1, y1, ...`、時刻昇順）。
     * @param progress 描画する区間の進捗（0.0=先頭点のみ、1.0=全区間）。T-007で再生中の現在時刻に応じた
     *   値を渡す（[com.nagamaki0311.timeliner.render.RouteOverlayView]）。
     * @param dateTimeText 画面下部に表示する日時テキスト。nullなら描画しない。
     */
    fun draw(
        canvas: Canvas,
        screenCoordinates: FloatArray,
        progress: Float,
        dateTimeText: String?,
        style: Style = DEFAULT_STYLE
    ) {
        // trimByProgressの結果（FloatArray確保＋arraycopy、最大3000点分）をルート線描画と現在位置マーカーの
        // 両方で使い回す。以前はcurrentPositionAtProgressとdrawRouteが独立に計算し毎フレーム2回無駄が発生していた
        // （docs/decisions.md D-008決定2）。
        val trimmed = trimByProgress(screenCoordinates, progress.coerceIn(0f, 1f))
        drawRoute(canvas, trimmed, style)
        markerPosition(trimmed)?.let { drawMarker(canvas, it, style) }
        if (dateTimeText != null) {
            drawDateTimeText(canvas, dateTimeText, style)
        }
        drawAttribution(canvas, style)
    }

    /** [trimByProgress]で切り詰め済みの点列の末尾点を現在位置マーカーの画面座標として返す。点が無い場合はnull。 */
    private fun markerPosition(trimmedScreenCoordinates: FloatArray): ScreenPoint? {
        if (trimmedScreenCoordinates.size < 2) return null
        return ScreenPoint(
            trimmedScreenCoordinates[trimmedScreenCoordinates.size - 2],
            trimmedScreenCoordinates[trimmedScreenCoordinates.size - 1]
        )
    }

    /**
     * [progress]（0.0〜1.0）に応じて描画対象の点列を先頭から切り詰める。区間の途中で切れる場合、
     * 直前・直後の点を線形補間して滑らかな終端を作る（T-007のアニメーション用）。
     */
    private fun trimByProgress(screenCoordinates: FloatArray, progress: Float): FloatArray {
        val pointCount = screenCoordinates.size / 2
        if (pointCount <= 1 || progress >= 1f) return screenCoordinates

        val exactIndex = progress * (pointCount - 1)
        val wholeIndex = exactIndex.toInt()
        val fraction = exactIndex - wholeIndex
        val keptPointCount = wholeIndex + 1
        val hasInterpolatedTail = fraction > 0f && wholeIndex + 1 < pointCount

        val result = FloatArray((keptPointCount + if (hasInterpolatedTail) 1 else 0) * 2)
        System.arraycopy(screenCoordinates, 0, result, 0, keptPointCount * 2)
        if (hasInterpolatedTail) {
            val ax = screenCoordinates[wholeIndex * 2]
            val ay = screenCoordinates[wholeIndex * 2 + 1]
            val bx = screenCoordinates[(wholeIndex + 1) * 2]
            val by = screenCoordinates[(wholeIndex + 1) * 2 + 1]
            result[keptPointCount * 2] = ax + (bx - ax) * fraction
            result[keptPointCount * 2 + 1] = ay + (by - ay) * fraction
        }
        return result
    }

    // 引数名を`frameStyle`とするのは、パラメータ型`Style`と紛らわしい小文字名を避けるため。
    private fun drawRoute(canvas: Canvas, screenCoordinates: FloatArray, frameStyle: Style) {
        val pointCount = screenCoordinates.size / 2
        if (pointCount < 2) return

        val path = frameStyle.routePath
        path.reset()
        path.moveTo(screenCoordinates[0], screenCoordinates[1])
        for (i in 1 until pointCount) {
            path.lineTo(screenCoordinates[i * 2], screenCoordinates[i * 2 + 1])
        }
        canvas.drawPath(path, frameStyle.routePaint)
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
