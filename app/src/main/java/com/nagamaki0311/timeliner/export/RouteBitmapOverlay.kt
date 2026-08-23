package com.nagamaki0311.timeliner.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.media3.effect.BitmapOverlay
import com.nagamaki0311.timeliner.camera.CameraDirector
import com.nagamaki0311.timeliner.playback.PlaybackTimeFormat
import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.render.RouteFrameRenderer
import com.nagamaki0311.timeliner.render.ScreenProjection
import kotlin.math.roundToInt

/**
 * 動画書き出し（docs/tasks.md T-008/T-025、docs/decisions.md D-002・D-009・D-017・D-028）用の[BitmapOverlay]実装。
 *
 * [Transformer][androidx.media3.transformer.Transformer]が画像入力（[VideoExporter]がMediaItemとして渡す、
 * 内容自体は本オーバーレイに完全に覆われるため重要ではない）を
 * [frameRate][androidx.media3.transformer.EditedMediaItem.frameRate]×
 * [durationMs][androidx.media3.common.MediaItem.LocalConfiguration.imageDurationMs]分のフレームへ展開する際、
 * 各フレームの`presentationTimeUs`ごとに[getBitmap]が呼ばれる（Media3ソース確認済み、D-009）。
 *
 * T-025（「地図を下地からBitmapOverlay内部へ移す」）により、地図背景も本クラスが毎フレーム合成する設計に
 * 変更した。[keyframeBitmaps]（[keyframes]と同じ数・同じ順序、[VideoExporter]が[MapSnapshotter]で
 * 事前取得済み）のうち、現在の再生位置に対応する隣接2枚を[CameraDirector.resolveKeyframeBlend]の割合で
 * クロスフェード（`Paint.alpha`＋`drawBitmap`2回）して背景として描き、その上に
 * [RouteFrameRenderer.draw]でルート線・現在位置マーカー・日時・地図帰属表示を焼き込む。
 * ルート線の画面座標は、[CameraDirector.currentKeyframeIndex]が示す「現在のキーフレーム」のカメラ位置
 * （中心・ズーム）を基準に、フレームごとに[ScreenProjection]で計算し直す（カメラが動く前提、
 * D-017決定4「控えめな演出」の範囲内で、投影自体の滑らかな補間は行わない単純な設計）。
 *
 * 1枚の[Bitmap]/[Canvas]を使い回し（[outputWidthPx]×[outputHeightPx]、数百〜数千フレーム分の
 * アロケーションを避ける、D-007のPaint/Pathキャッシュと同じ考え方）、毎フレーム`eraseColor`で
 * 透明にリセットしてから描画する。[BitmapOverlay.getTextureId]はBitmapの参照一致と
 * [Bitmap.getGenerationId]の変化でGLテクスチャの再アップロード要否を判定するため（D-009でMedia3ソースを
 * 確認済み）、フレームごとの更新は確実にエンコード結果へ反映される。
 *
 * @param outputWidthPx/outputHeightPx 動画の出力解像度（[VideoExporter]が決定）。
 * @param keyframes カメラキーフレーム列（[CameraDirector.computeKeyframes]の戻り値、再生時刻昇順）。
 * @param keyframeBitmaps [keyframes]と同じ数・同じ順序の地図スナップショット（[outputWidthPx]×[outputHeightPx]）。
 * @param routeWorldXs/routeWorldYs ルート点列のワールド座標（Webメルカトル投影空間、メートル、時刻昇順）。
 *   [VideoExporter.buildRouteWorldGeometry]が1回だけ簡略化・変換したもの（[keyframeBitmaps]の各カメラで
 *   使い回す、カメラごとに簡略化点集合を変えない設計、[VideoExporter.buildRouteWorldGeometry]のKDoc参照）。
 * @param routeTimestampsMillis [routeWorldXs]/[routeWorldYs]と対応する時刻配列（同じ間引き後の点列、時刻昇順）。
 * @param timeline 画面再生（[com.nagamaki0311.timeliner.playback.PlaybackController]）と共有する、
 *   データ時刻↔再生時刻の単調写像（D-002）。総再生時間が動画の長さと一致する。
 */
class RouteBitmapOverlay(
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
    private val keyframes: List<CameraDirector.CameraKeyframe>,
    private val keyframeBitmaps: List<Bitmap>,
    private val routeWorldXs: DoubleArray,
    private val routeWorldYs: DoubleArray,
    private val routeTimestampsMillis: LongArray,
    private val timeline: PlaybackTimeline
) : BitmapOverlay() {

    init {
        require(keyframes.isNotEmpty()) { "keyframesは1個以上である必要があります" }
        require(keyframes.size == keyframeBitmaps.size) {
            "keyframesとkeyframeBitmapsの数が一致しません: ${keyframes.size}, ${keyframeBitmaps.size}"
        }
    }

    private val workingBitmap: Bitmap = Bitmap.createBitmap(outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(workingBitmap)

    /** 背景の地図スナップショット2枚をクロスフェード描画するために使い回す[Paint]（`alpha`のみ毎回更新）。 */
    private val backgroundPaint = Paint()

    /** [screenCoordinatesForKeyframe]のキャッシュ。同じキーフレームが続く間（多くのフレーム）は再計算しない。 */
    private var cachedKeyframeIndex = -1
    private var cachedScreenCoordinates = FloatArray(0)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val playbackMillis = presentationTimeUs / 1000L
        val dataTimeMillis = timeline.dataTimeAtPlaybackMillis(playbackMillis)

        workingBitmap.eraseColor(Color.TRANSPARENT)
        drawBackground(playbackMillis)

        val keyframeIndex = CameraDirector.currentKeyframeIndex(keyframes, playbackMillis)
        val screenCoordinates = screenCoordinatesForKeyframe(keyframeIndex)
        val progress = RouteFrameRenderer.progressAtDataTime(routeTimestampsMillis, dataTimeMillis)
        RouteFrameRenderer.draw(
            canvas = canvas,
            screenCoordinates = screenCoordinates,
            timestampsMillis = routeTimestampsMillis,
            progress = progress,
            dateTimeText = PlaybackTimeFormat.format(dataTimeMillis)
        )
        return workingBitmap
    }

    /** [CameraDirector.resolveKeyframeBlend]の割合で、隣接する2枚の地図スナップショットをクロスフェード描画する。 */
    private fun drawBackground(playbackMillis: Long) {
        val blend = CameraDirector.resolveKeyframeBlend(keyframes, playbackMillis)
        backgroundPaint.alpha = 255
        canvas.drawBitmap(keyframeBitmaps[blend.fromIndex], 0f, 0f, backgroundPaint)
        if (blend.toIndex != blend.fromIndex && blend.toAlpha > 0f) {
            backgroundPaint.alpha = (blend.toAlpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(keyframeBitmaps[blend.toIndex], 0f, 0f, backgroundPaint)
        }
    }

    /**
     * [keyframeIndex]（[CameraDirector.currentKeyframeIndex]の戻り値）のカメラ位置（中心・ズーム）を基準に、
     * [routeWorldXs]/[routeWorldYs]を画面座標へ変換する。直前と同じ[keyframeIndex]なら再計算しない
     * （キーフレーム間隔は再生時刻で数秒あり、多くの連続フレームが同じキーフレームを指すため、
     * 毎フレームの再投影を避けてアロケーションを削減する、D-007と同じキャッシュの考え方）。
     */
    private fun screenCoordinatesForKeyframe(keyframeIndex: Int): FloatArray {
        if (keyframeIndex == cachedKeyframeIndex) return cachedScreenCoordinates
        val keyframe = keyframes[keyframeIndex]
        val coordinates = ScreenProjection.toScreenCoordinates(
            worldXs = routeWorldXs,
            worldYs = routeWorldYs,
            centerWorldX = Mercator.longitudeToX(keyframe.centerLongitude),
            centerWorldY = Mercator.latitudeToY(keyframe.centerLatitude),
            metersPerPixel = Mercator.metersPerPixelAtZoom(keyframe.zoom),
            screenWidthPx = outputWidthPx.toFloat(),
            screenHeightPx = outputHeightPx.toFloat()
        )
        cachedKeyframeIndex = keyframeIndex
        cachedScreenCoordinates = coordinates
        return coordinates
    }
}
