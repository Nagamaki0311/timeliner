package com.nagamaki0311.timeliner.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.media3.effect.BitmapOverlay
import com.nagamaki0311.timeliner.playback.PlaybackTimeFormat
import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.render.RouteFrameRenderer

/**
 * 動画書き出し（docs/tasks.md T-008、docs/decisions.md D-002・D-009）用の[BitmapOverlay]実装。
 *
 * [Transformer][androidx.media3.transformer.Transformer]が画像入力（地図スナップショット、
 * [VideoExporter]がMediaItemとして渡す）を[frameRate][androidx.media3.transformer.EditedMediaItem.frameRate]
 * ×[durationMs][androidx.media3.common.MediaItem.LocalConfiguration.imageDurationMs]分のフレームへ展開する際、
 * 各フレームの`presentationTimeUs`ごとに[getBitmap]が呼ばれる（Media3ソース確認済み、D-009）。
 * ここで[PlaybackTimeline]によりデータ時刻・進捗（[RouteFrameRenderer.progressAtDataTime]）を求め、
 * [RouteFrameRenderer.draw]でルート線・現在位置マーカー・日時・地図帰属表示（透明背景の上に描く、
 * 地図本体は[VideoExporter]が動画入力として渡す下地画像がそのまま透けて見える）を焼き込んだBitmapを返す。
 *
 * 1枚の[Bitmap]/[Canvas]を使い回し（[outputWidthPx]×[outputHeightPx]、数百〜数千フレーム分の
 * アロケーションを避ける、D-007のPaint/Pathキャッシュと同じ考え方）、毎フレーム`eraseColor`で
 * 透明にリセットしてから描画する。[BitmapOverlay.getTextureId]はBitmapの参照一致と
 * [Bitmap.getGenerationId]の変化でGLテクスチャの再アップロード要否を判定するため（D-009でMedia3ソースを
 * 確認済み）、フレームごとの更新は確実にエンコード結果へ反映される。generationIdは`Canvas`での描画を含む
 * 「Bitmapの変更」で自動的に更新される（[Bitmap.getGenerationId]のJavadoc「changes whenever the bitmap
 * is modified」、Java側で明示的に通知するAPI（旧`notifyPixelsChanged()`）は現行SDKに存在しない）。
 *
 * @param outputWidthPx/outputHeightPx 動画の出力解像度（[VideoExporter]が決定、地図スナップショットと同じ短辺上限）。
 * @param screenCoordinates [outputWidthPx]×[outputHeightPx]・スナップショット時点のカメラ位置を基準に
 *   画面座標へ変換済みのルート点列（`x0, y0, x1, y1, ...`、時刻昇順、[VideoExporter]が計算）。
 * @param routeTimestampsMillis [screenCoordinates]と対応する時刻配列（同じ間引き後の点列、時刻昇順）。
 * @param timeline 画面再生（[com.nagamaki0311.timeliner.playback.PlaybackController]）と共有する、
 *   データ時刻↔再生時刻の単調写像（D-002）。総再生時間が動画の長さと一致する。
 */
class RouteBitmapOverlay(
    outputWidthPx: Int,
    outputHeightPx: Int,
    private val screenCoordinates: FloatArray,
    private val routeTimestampsMillis: LongArray,
    private val timeline: PlaybackTimeline
) : BitmapOverlay() {

    private val workingBitmap: Bitmap = Bitmap.createBitmap(outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(workingBitmap)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val playbackMillis = presentationTimeUs / 1000L
        val dataTimeMillis = timeline.dataTimeAtPlaybackMillis(playbackMillis)
        val progress = RouteFrameRenderer.progressAtDataTime(routeTimestampsMillis, dataTimeMillis)

        workingBitmap.eraseColor(Color.TRANSPARENT)
        RouteFrameRenderer.draw(
            canvas = canvas,
            screenCoordinates = screenCoordinates,
            timestampsMillis = routeTimestampsMillis,
            progress = progress,
            dateTimeText = PlaybackTimeFormat.format(dataTimeMillis)
        )
        return workingBitmap
    }
}
