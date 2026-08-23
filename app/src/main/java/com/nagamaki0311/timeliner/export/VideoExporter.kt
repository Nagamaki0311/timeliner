package com.nagamaki0311.timeliner.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.nagamaki0311.timeliner.camera.CameraDirector
import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.process.Simplifier
import com.nagamaki0311.timeliner.ui.MapConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * アニメーションの動画書き出し（docs/tasks.md T-008/T-025、docs/decisions.md D-002・D-009・D-017・D-028）。
 *
 * [export]はMain dispatcher上のコルーチンから呼ぶこと。`Transformer`は構築されたスレッドの
 * `Looper`上でのみ操作できる制約があり（未指定時は構築スレッドのLooper、無ければメインスレッドのLooperを使う、
 * Media3のTransformerのJavadocによる）、内部で`Transformer`の構築・start・進捗取得・cancelは
 * すべて呼び出し元のディスパッチャ上でそのまま行う（別スレッドへ切り替えない）。同様に[MapSnapshotter]も
 * `@UiThread`制約を持つ（docs/decisions.md D-028決定4）ため、キーフレームごとのスナップショット取得も
 * 呼び出し元のディスパッチャ（Main）上でそのまま行う。地図に依存しない簡略化・座標変換・ファイルI/Oのみ
 * [Dispatchers.Default]へ逃がしUIをブロックしない。
 *
 * D-028（T-022スパイク検証）の結論により、動画のカメラは[MapLibreMap]のライブスナップショット（1回だけ）ではなく、
 * [CameraDirector.computeKeyframes]が計算する有限個のキーフレームそれぞれについて[MapSnapshotter]で
 * ヘッドレスに静止画を取得し、[RouteBitmapOverlay]が毎フレームそれらをクロスフェードしながら合成する設計
 * （「地図を下地からBitmapOverlay内部へ移す」、docs/tasks.md T-025）を採る。
 */
class VideoExporter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param map 動画の出力解像度（[computeOutputResolution]、D-002決定6）を決めるための現在の地図ビュー
     *   サイズ（[MapLibreMap.width]/[MapLibreMap.height]）を読むためだけに使う。実際の地図スナップショットの
     *   取得には[MapSnapshotter]（ヘッドレスAPI）を使うため、[map]自体のスタイル・カメラ位置は参照しない
     *   （D-017決定5: UIの一貫性のため「動画として保存」ボタンの`enabled`条件は維持するが、書き出し処理自体は
     *   画面表示中の地図に依存しない）。
     * @param latitudes/longitudes/timestampsMillis 書き出し対象のルート点列（クリーニング済み・未簡略化、時刻昇順）。
     * @param timeline latitudes/longitudes/timestampsMillisと対応するデータ時刻↔再生時刻の写像
     *   （画面再生と同一の設計、D-002）。総再生時間（[PlaybackTimeline.totalPlaybackMillis]）が動画の長さになる。
     * @param outputFile 書き込み先ファイル（呼び出し元が用意する一時ファイル。失敗・キャンセル時の削除も
     *   呼び出し元の責務。呼び出し元がこのファイルを作成する以上、その後始末も呼び出し元に一元化する方が
     *   責務が明確なため、本関数は自ら作った中間ファイル（キーフレーム1枚目のPNG）のみ後始末する）。
     * @param onProgress 0.0〜1.0の進捗を都度通知する（呼び出し元のディスパッチャ上で呼ばれる）。
     *   キーフレームのスナップショット取得フェーズに[SNAPSHOT_PHASE_PROGRESS_WEIGHT]、Transformerの
     *   エンコードフェーズに残りを割り当てる（D-002当時は1回のsnapshotで一瞬だったが、T-025では
     *   キーフレーム数分のシーケンシャルなsnapshot取得が発生し無視できない時間がかかりうるため、
     *   0%のまま長時間止まって見えるUXの後退を避ける）。
     * @throws ExportFailedException 書き出しに失敗した場合。
     */
    suspend fun export(
        map: MapLibreMap,
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        timestampsMillis: LongArray,
        timeline: PlaybackTimeline,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        require(latitudes.isNotEmpty()) { "latitudesは1点以上である必要があります" }
        val durationMs = timeline.totalPlaybackMillis()
        require(durationMs > 0) { "durationMsは正の値である必要があります: $durationMs" }

        val (outputWidthPx, outputHeightPx) =
            computeOutputResolution(map.width.roundToInt(), map.height.roundToInt())

        val keyframes = withContext(Dispatchers.Default) {
            CameraDirector.computeKeyframes(
                timestampsMillis, latitudes, longitudes, timeline,
                viewportWidthPx = outputWidthPx, viewportHeightPx = outputHeightPx
            )
        }
        // latitudes.isNotEmpty()（上のrequire）を渡している以上、computeKeyframesは1個以上返す
        // （KDoc「空の場合は空リスト、それ以外は1個以上のキーフレームを返す」、壊れたら失敗する最小限の確認）。
        check(keyframes.isNotEmpty()) { "keyframesが空です（latitudesが空でないにもかかわらず）" }

        val keyframeBitmaps = captureKeyframeSnapshots(keyframes, outputWidthPx, outputHeightPx, onProgress)

        var baseImageFile: File? = null
        try {
            val routeGeometry = withContext(Dispatchers.Default) {
                buildRouteWorldGeometry(latitudes, longitudes, timestampsMillis, keyframes)
            }
            baseImageFile = writeBaseImageFile(keyframeBitmaps.first())

            runTransformer(
                baseImageFile = baseImageFile,
                outputWidthPx = outputWidthPx,
                outputHeightPx = outputHeightPx,
                keyframes = keyframes,
                keyframeBitmaps = keyframeBitmaps,
                routeGeometry = routeGeometry,
                timeline = timeline,
                durationMs = durationMs,
                outputFile = outputFile,
                onProgress = onProgress
            )
        } finally {
            // Transformerはrun完了（成功/失敗/キャンセル、runTransformer内で待機・cancel済み）まで
            // オーバーレイからBitmapを参照し続けるため、recycleはrunTransformerの完了後に行う
            // （D-010以来の「1枚のsnapshot Bitmapをfinallyでrecycleする」パターンをそのまま踏襲）。
            keyframeBitmaps.forEach { it.recycle() }
            baseImageFile?.delete()
        }
    }

    /**
     * [keyframes]（[CameraDirector.computeKeyframes]の戻り値）それぞれについて、[MapSnapshotter]で
     * ヘッドレスに地図スナップショットを1枚ずつ取得する。1つの[MapSnapshotter]インスタンスを使い回し、
     * 前回の`start()`完了を待った上で[MapSnapshotter.setCameraPosition]を呼んでから次の`start()`を呼ぶ
     * 逐次ループとする（D-028決定5: 同時並行実行は`IllegalStateException`になるため不可）。
     * [MapSnapshotter.Options]の width/height は[outputWidthPx]/[outputHeightPx]（動画の出力解像度そのもの）を
     * 直接指定するため、`pixelRatio`既定値(1.0f)のもとで取得したBitmapはそのまま動画のフレームサイズになり、
     * 追加のスケーリングが不要（旧D-002実装が行っていた`Bitmap.createScaledBitmap`は不要になった）。
     * [RouteFrameRenderer][com.nagamaki0311.timeliner.render.RouteFrameRenderer]が地図帰属表示を自前で
     * 焼き込む（T-008）ため、[MapSnapshotter]自身のロゴ・帰属表示描画は無効化する。
     */
    private suspend fun captureKeyframeSnapshots(
        keyframes: List<CameraDirector.CameraKeyframe>,
        outputWidthPx: Int,
        outputHeightPx: Int,
        onProgress: (Float) -> Unit
    ): List<Bitmap> {
        val options = MapSnapshotter.Options(outputWidthPx, outputHeightPx)
            .withStyleBuilder(Style.Builder().fromUri(MapConfig.STYLE_URL))
            .withCameraPosition(toCameraPosition(keyframes.first()))
            .withLogo(false)
            .withAttribution(false)
        val snapshotter = MapSnapshotter(appContext, options)

        val bitmaps = ArrayList<Bitmap>(keyframes.size)
        try {
            keyframes.forEachIndexed { index, keyframe ->
                if (index > 0) snapshotter.setCameraPosition(toCameraPosition(keyframe))
                bitmaps.add(awaitMapSnapshot(snapshotter))
                onProgress((index + 1).toFloat() / keyframes.size * SNAPSHOT_PHASE_PROGRESS_WEIGHT)
            }
        } catch (e: Throwable) {
            snapshotter.cancel()
            bitmaps.forEach { it.recycle() }
            throw e
        }
        return bitmaps
    }

    private fun toCameraPosition(keyframe: CameraDirector.CameraKeyframe): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(keyframe.centerLatitude, keyframe.centerLongitude))
            .zoom(keyframe.zoom)
            .build()

    /**
     * [MapSnapshotter.start]のコールバックを待つ。SDK側にタイムアウト機構は無い（D-028決定3、
     * `MapLibreMap.snapshot()`についてD-010が確立したのと同じ制約）ため、[SNAPSHOT_TIMEOUT_MILLIS]で
     * タイムアウトする。タイムアウトは`TimeoutCancellationException`（`CancellationException`のサブクラス）で
     * 発生するが、そのまま伝播させると呼び出し元で「ユーザーによるキャンセル」と区別が付かなくなるため、
     * [ExportFailedException]へ変換する。
     */
    private suspend fun awaitMapSnapshot(snapshotter: MapSnapshotter): Bitmap {
        val deferred = CompletableDeferred<Bitmap>()
        snapshotter.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    deferred.complete(snapshot.bitmap)
                }
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    deferred.completeExceptionally(
                        ExportFailedException("地図のスナップショット取得に失敗しました: $error", RuntimeException(error))
                    )
                }
            }
        )
        return try {
            withTimeout(SNAPSHOT_TIMEOUT_MILLIS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw ExportFailedException("地図のスナップショット取得がタイムアウトしました", e)
        }
    }

    private fun writeBaseImageFile(bitmap: Bitmap): File {
        val file = File.createTempFile("timeliner_export_base_", ".png", appContext.cacheDir)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    private suspend fun runTransformer(
        baseImageFile: File,
        outputWidthPx: Int,
        outputHeightPx: Int,
        keyframes: List<CameraDirector.CameraKeyframe>,
        keyframeBitmaps: List<Bitmap>,
        routeGeometry: RouteWorldGeometry,
        timeline: PlaybackTimeline,
        durationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val overlay = RouteBitmapOverlay(
            outputWidthPx = outputWidthPx,
            outputHeightPx = outputHeightPx,
            keyframes = keyframes,
            keyframeBitmaps = keyframeBitmaps,
            routeWorldXs = routeGeometry.worldXs,
            routeWorldYs = routeGeometry.worldYs,
            routeTimestampsMillis = routeGeometry.timestampsMillis,
            timeline = timeline
        )
        val overlayEffect = OverlayEffect(listOf(overlay))

        // MediaItemに渡す画像はフレーム数・動画長を確定させるための入力に過ぎず、内容自体は
        // overlay（RouteBitmapOverlay）に完全に覆われるため重要ではない（Media3のImageAssetLoaderパイプラインの
        // 制約上、画像入力が必須、D-009・docs/tasks.md T-025）。キーフレーム1枚目のスナップショットを流用する。
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(baseImageFile))
            .setMimeType(MimeTypes.IMAGE_PNG)
            .setImageDurationMs(durationMs)
            .build()
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setFrameRate(FRAME_RATE)
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(editedMediaItem)
            .build()
        val composition = Composition.Builder(sequence).build()

        val result = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(appContext)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    result.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    result.completeExceptionally(
                        ExportFailedException(exportException.message ?: exportException.errorCodeName, exportException)
                    )
                }
            })
            .build()

        if (outputFile.exists()) outputFile.delete()
        transformer.start(composition, outputFile.absolutePath)

        try {
            while (!result.isCompleted) {
                val progressHolder = ProgressHolder()
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val transformerFraction = (progressHolder.progress / 100f).coerceIn(0f, 1f)
                    onProgress(
                        SNAPSHOT_PHASE_PROGRESS_WEIGHT +
                            transformerFraction * (1f - SNAPSHOT_PHASE_PROGRESS_WEIGHT)
                    )
                }
                delay(PROGRESS_POLL_INTERVAL_MILLIS)
            }
            result.await()
            onProgress(1f)
        } catch (e: CancellationException) {
            // Transformerのリソース(コーデック等)をキャンセル時点で即座に解放するため、result完了を待たず呼ぶ。
            transformer.cancel()
            throw e
        }
    }

    companion object {
        private const val FRAME_RATE = 30
        private const val PROGRESS_POLL_INTERVAL_MILLIS = 200L
        private const val SNAPSHOT_TIMEOUT_MILLIS = 10_000L
        private const val SIMPLIFY_EPSILON_SCREEN_PIXELS = 2.0
        private const val MIN_EPSILON_METERS = 0.01
        private const val SIMPLIFY_MAX_POINT_COUNT = 3000

        /**
         * 進捗（0.0〜1.0）のうち、キーフレームのスナップショット取得フェーズに割り当てる割合。
         * 残り(`1f - この値`)はTransformerのエンコードフェーズに割り当てる。D-028決定6が「実測のミリ秒値までは
         * 確認不可」としている既知の制約下での見積もりのため、両フェーズが概ね同程度の比重という
         * 保守的な仮定を置く（正確な計測は実機入手後の課題として残る）。
         */
        private const val SNAPSHOT_PHASE_PROGRESS_WEIGHT = 0.5f

        /** 動画の出力解像度が短辺何pxを上限とするか（D-002決定6、拡大はしない）。 */
        internal const val MAX_OUTPUT_SHORT_SIDE_PX = 1080

        /**
         * [RouteBitmapOverlay]が毎フレーム参照する、ルート点列のワールド座標（Webメルカトル投影空間、メートル）と
         * 対応する時刻。カメラ（キーフレーム）ごとの画面座標へは、フレーム描画時に[RouteBitmapOverlay]が
         * 都度[com.nagamaki0311.timeliner.render.ScreenProjection]で変換する（[buildRouteWorldGeometry]参照）。
         */
        internal data class RouteWorldGeometry(
            val worldXs: DoubleArray,
            val worldYs: DoubleArray,
            val timestampsMillis: LongArray
        )

        /**
         * ルート点列を1回だけ簡略化し、ワールド座標へ変換する。[keyframes]はズームレベルが大きく異なりうる
         * （狭い滞在は高ズーム、都市間移動は低ズーム）ため、簡略化のepsilon（許容誤差、画面ピクセル基準）は
         * [keyframes]中で最もズームが高い（＝1ピクセルあたりの実距離が最も小さい＝最も細かい表現が必要な）
         * キーフレームの[Mercator.metersPerPixelAtZoom]を基準に決める。低ズームのキーフレームではこの
         * epsilonより点が密になり必要以上に点が残るが、[Simplifier.simplify]の`maxPointCount`で上限を
         * 設けているため実害はない（過度に複雑にしないシンプルな設計、docs/decisions.md D-017決定4）。
         * カメラごとに個別に簡略化しない（ズームごとに異なる点集合を持たない）ことで、
         * [RouteFrameRenderer][com.nagamaki0311.timeliner.render.RouteFrameRenderer]の
         * `progress`（0.0〜1.0）がどのキーフレーム区間でも同じ点集合を基準にした一貫した値になる。
         * `android.*`に依存しないためJVM単体テストから直接検証できる（D-003/D-004と同様の理由でコンパニオン
         * オブジェクトの関数とし、インスタンス生成（`Context`必須）無しで呼べるようにした）。
         */
        internal fun buildRouteWorldGeometry(
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            timestampsMillis: LongArray,
            keyframes: List<CameraDirector.CameraKeyframe>
        ): RouteWorldGeometry {
            val finestMetersPerPixel = keyframes.minOf { Mercator.metersPerPixelAtZoom(it.zoom) }
            val epsilonMeters = (finestMetersPerPixel * SIMPLIFY_EPSILON_SCREEN_PIXELS).coerceAtLeast(MIN_EPSILON_METERS)
            val keptIndices = Simplifier.simplify(
                latitudes, longitudes, timestampsMillis, epsilonMeters, maxPointCount = SIMPLIFY_MAX_POINT_COUNT
            )
            val worldXs = DoubleArray(keptIndices.size) { Mercator.longitudeToX(longitudes[keptIndices[it]]) }
            val worldYs = DoubleArray(keptIndices.size) { Mercator.latitudeToY(latitudes[keptIndices[it]]) }
            val simplifiedTimestamps = LongArray(keptIndices.size) { timestampsMillis[keptIndices[it]] }
            return RouteWorldGeometry(worldXs, worldYs, simplifiedTimestamps)
        }

        /**
         * 出力解像度を、[mapWidthPx]/[mapHeightPx]のアスペクト比のまま、短辺が
         * [MAX_OUTPUT_SHORT_SIDE_PX]を超えないよう決める（D-002決定6、固定アスペクト比を決め打ちしない・拡大はしない）。
         * H.264エンコーダの制約（幅・高さが偶数であること）に合わせ、結果は必ず偶数へ丸める。
         */
        internal fun computeOutputResolution(mapWidthPx: Int, mapHeightPx: Int): Pair<Int, Int> {
            require(mapWidthPx > 0 && mapHeightPx > 0) {
                "mapWidthPx/mapHeightPxは正の値である必要があります: $mapWidthPx x $mapHeightPx"
            }
            val shortSide = min(mapWidthPx, mapHeightPx)
            val scale = if (shortSide > MAX_OUTPUT_SHORT_SIDE_PX) MAX_OUTPUT_SHORT_SIDE_PX.toDouble() / shortSide else 1.0
            val width = roundToEven((mapWidthPx * scale).roundToInt())
            val height = roundToEven((mapHeightPx * scale).roundToInt())
            return width to height
        }

        private fun roundToEven(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
    }
}

/** [VideoExporter.export]が失敗した際にスローされる例外。[cause]にMedia3の[ExportException]等を保持する。 */
class ExportFailedException(message: String, cause: Throwable) : Exception(message, cause)
