package com.nagamaki0311.timeliner.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    /** 描画スタイル（色・線太さ等）。呼び出し側でテーマに応じて差し替え可能にする。 */
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
    )

    /** 画面上の1点（ピクセル）。[RouteOverlayView]・T-008双方から使う最小限の値保持のみのデータクラス。 */
    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * @param canvas 描画先。
     * @param screenCoordinates 画面座標へ変換済みの点列（`x0, y0, x1, y1, ...`、時刻昇順）。
     * @param progress 描画する区間の進捗（0.0=先頭点のみ、1.0=全区間）。今回（T-006）は常に1.0で呼ぶ想定。
     *   T-007でアニメーションの現在時刻に応じた値を渡す。
     * @param currentPositionScreen 現在位置マーカーを描画する画面座標。nullなら描画しない。
     * @param dateTimeText 画面下部に表示する日時テキスト。nullなら描画しない。
     */
    fun draw(
        canvas: Canvas,
        screenCoordinates: FloatArray,
        progress: Float,
        currentPositionScreen: ScreenPoint?,
        dateTimeText: String?,
        style: Style = Style()
    ) {
        drawRoute(canvas, trimByProgress(screenCoordinates, progress.coerceIn(0f, 1f)), style)
        if (currentPositionScreen != null) {
            drawMarker(canvas, currentPositionScreen, style)
        }
        if (dateTimeText != null) {
            drawDateTimeText(canvas, dateTimeText, style)
        }
        drawAttribution(canvas, style)
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

    // ヘルパー内では`Paint.style`（塗り/線種）との名前衝突を避けるため、引数名を`frameStyle`とする。
    private fun drawRoute(canvas: Canvas, screenCoordinates: FloatArray, frameStyle: Style) {
        val pointCount = screenCoordinates.size / 2
        if (pointCount < 2) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameStyle.routeColor
            strokeWidth = frameStyle.routeStrokeWidthPx
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        path.moveTo(screenCoordinates[0], screenCoordinates[1])
        for (i in 1 until pointCount) {
            path.lineTo(screenCoordinates[i * 2], screenCoordinates[i * 2 + 1])
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMarker(canvas: Canvas, position: ScreenPoint, frameStyle: Style) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameStyle.markerColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameStyle.markerStrokeColor
            style = Paint.Style.STROKE
            strokeWidth = frameStyle.markerStrokeWidthPx
        }
        canvas.drawCircle(position.x, position.y, frameStyle.markerRadiusPx, fillPaint)
        canvas.drawCircle(position.x, position.y, frameStyle.markerRadiusPx, strokePaint)
    }

    private fun drawDateTimeText(canvas: Canvas, text: String, frameStyle: Style) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameStyle.dateTimeTextColor
            textSize = frameStyle.dateTimeTextSizePx
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(text, frameStyle.paddingPx, frameStyle.paddingPx + frameStyle.dateTimeTextSizePx, paint)
    }

    private fun drawAttribution(canvas: Canvas, frameStyle: Style) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameStyle.attributionTextColor
            textSize = frameStyle.attributionTextSizePx
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            MapConfig.ATTRIBUTION_TEXT,
            canvas.width - frameStyle.paddingPx,
            canvas.height - frameStyle.paddingPx,
            paint
        )
    }
}
