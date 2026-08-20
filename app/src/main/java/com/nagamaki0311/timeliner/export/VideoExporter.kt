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
import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.process.Mercator
import com.nagamaki0311.timeliner.process.Simplifier
import com.nagamaki0311.timeliner.render.ScreenProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * アニメーションの動画書き出し（docs/tasks.md T-008、docs/decisions.md D-002・D-009）。
 *
 * [export]はMain dispatcher上のコルーチンから呼ぶこと。`Transformer`は構築されたスレッドの
 * `Looper`上でのみ操作できる制約があり（未指定時は構築スレッドのLooper、無ければメインスレッドのLooperを使う、
 * Media3のTransformerのJavadocによる）、内部で`Transformer`の構築・start・進捗取得・cancelは
 * すべて呼び出し元のディスパッチャ上でそのまま行う（別スレッドへ切り替えない）。
 * 地図スナップショットの簡略化・座標変換・ファイルI/Oは[Dispatchers.Default]へ逃がしUIをブロックしない。
 */
class VideoExporter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param map 再生開始時点の地図。カメラ位置（中心座標・ズーム）は呼び出し時点のものをそのまま使う
     *   （D-002決定「再生開始時のカメラ位置でsnapshotを1回だけ呼ぶ」）。
     * @param latitudes/longitudes/timestampsMillis 書き出し対象のルート点列（クリーニング済み・未簡略化、時刻昇順）。
     * @param timeline latitudes/longitudes/timestampsMillisと対応するデータ時刻↔再生時刻の写像
     *   （画面再生と同一の設計、D-002）。総再生時間（[PlaybackTimeline.totalPlaybackMillis]）が動画の長さになる。
     * @param outputFile 書き込み先ファイル（呼び出し元が用意する一時ファイル。失敗・キャンセル時の削除も
     *   呼び出し元の責務。呼び出し元がこのファイルを作成する以上、その後始末も呼び出し元に一元化する方が
     *   責務が明確なため、本関数は自ら作った中間ファイル（地図スナップショットのPNG）のみ後始末する）。
     * @param onProgress 0.0〜1.0の進捗を都度通知する（呼び出し元のディスパッチャ上で呼ばれる）。
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

        // カメラ位置はsnapshot要求と同時に読む（再生開始時のカメラ位置を使うというD-002決定に対し、
        // snapshotのコールバックが返るまでの間にカメラが動く余地を作らないため）。
        val cameraPosition = map.cameraPosition
        val target = requireNotNull(cameraPosition.target) { "カメラ位置(target)が未設定です" }
        val metersPerPixelAtSnapshot = map.projection.getMetersPerPixelAtLatitude(0.0)
        val snapshotBitmap = awaitSnapshot(map)

        var baseImageFile: File? = null
        try {
            val (outputWidthPx, outputHeightPx) =
                computeOutputResolution(snapshotBitmap.width, snapshotBitmap.height)

            val geometry = withContext(Dispatchers.Default) {
                buildExportGeometry(
                    snapshotBitmap = snapshotBitmap,
                    outputWidthPx = outputWidthPx,
                    outputHeightPx = outputHeightPx,
                    metersPerPixelAtSnapshot = metersPerPixelAtSnapshot,
                    centerLatitude = target.latitude,
                    centerLongitude = target.longitude,
                    latitudes = latitudes,
                    longitudes = longitudes,
                    timestampsMillis = timestampsMillis
                )
            }
            baseImageFile = geometry.baseImageFile

            runTransformer(
                baseImageFile = geometry.baseImageFile,
                outputWidthPx = outputWidthPx,
                outputHeightPx = outputHeightPx,
                screenCoordinates = geometry.screenCoordinates,
                routeTimestampsMillis = geometry.simplifiedTimestampsMillis,
                timeline = timeline,
                durationMs = durationMs,
                outputFile = outputFile,
                onProgress = onProgress
            )
        } finally {
            snapshotBitmap.recycle()
            baseImageFile?.delete()
        }
    }

    private suspend fun awaitSnapshot(map: MapLibreMap): Bitmap {
        val deferred = CompletableDeferred<Bitmap>()
        map.snapshot { bitmap -> deferred.complete(bitmap) }
        return deferred.await()
    }

    /** [buildExportGeometry]の戻り値。動画の下地画像（PNG一時ファイル）と、その画像に対応する画面座標・時刻配列。 */
    private data class ExportGeometry(
        val baseImageFile: File,
        val screenCoordinates: FloatArray,
        val simplifiedTimestampsMillis: LongArray
    )

    /**
     * 地図スナップショットを[outputWidthPx]×[outputHeightPx]へ縮小してPNG一時ファイルへ書き出し、
     * ルート点列を同じ解像度・カメラ位置基準の画面座標へ変換する（[RouteOverlayView]の
     * `recomputeAndInvalidate`と同じ考え方: DP簡略化→ワールド座標→画面座標）。
     */
    private fun buildExportGeometry(
        snapshotBitmap: Bitmap,
        outputWidthPx: Int,
        outputHeightPx: Int,
        metersPerPixelAtSnapshot: Double,
        centerLatitude: Double,
        centerLongitude: Double,
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        timestampsMillis: LongArray
    ): ExportGeometry {
        // 出力解像度はsnapshotの短辺を1080pxへ収める形で縮小するのみ（拡大はしない、D-002決定6）。
        // 縮小した分だけ「1ピクセルあたりのメートル数」も増える（同じ実距離をより少ないピクセルで表す）ため、
        // ルート点列の画面座標も縮小後の解像度・metersPerPixelを基準に計算し直す必要がある。
        val resolutionScale = outputWidthPx.toDouble() / snapshotBitmap.width
        val metersPerPixel = metersPerPixelAtSnapshot / resolutionScale

        val scaledSnapshot = Bitmap.createScaledBitmap(snapshotBitmap, outputWidthPx, outputHeightPx, true)
        val baseImageFile = writeBaseImageFile(scaledSnapshot)

        val epsilonMeters = (metersPerPixel * SIMPLIFY_EPSILON_SCREEN_PIXELS).coerceAtLeast(MIN_EPSILON_METERS)
        val keptIndices = Simplifier.simplify(
            latitudes, longitudes, timestampsMillis, epsilonMeters, maxPointCount = SIMPLIFY_MAX_POINT_COUNT
        )
        val worldXs = DoubleArray(keptIndices.size) { Mercator.longitudeToX(longitudes[keptIndices[it]]) }
        val worldYs = DoubleArray(keptIndices.size) { Mercator.latitudeToY(latitudes[keptIndices[it]]) }
        val simplifiedTimestamps = LongArray(keptIndices.size) { timestampsMillis[keptIndices[it]] }
        val screenCoordinates = ScreenProjection.toScreenCoordinates(
            worldXs = worldXs,
            worldYs = worldYs,
            centerWorldX = Mercator.longitudeToX(centerLongitude),
            centerWorldY = Mercator.latitudeToY(centerLatitude),
            metersPerPixel = metersPerPixel,
            screenWidthPx = outputWidthPx.toFloat(),
            screenHeightPx = outputHeightPx.toFloat()
        )
        return ExportGeometry(baseImageFile, screenCoordinates, simplifiedTimestamps)
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
        screenCoordinates: FloatArray,
        routeTimestampsMillis: LongArray,
        timeline: PlaybackTimeline,
        durationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val overlay = RouteBitmapOverlay(outputWidthPx, outputHeightPx, screenCoordinates, routeTimestampsMillis, timeline)
        val overlayEffect = OverlayEffect(listOf(overlay))

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
                    onProgress((progressHolder.progress / 100f).coerceIn(0f, 1f))
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
        private const val SIMPLIFY_EPSILON_SCREEN_PIXELS = 2.0
        private const val MIN_EPSILON_METERS = 0.01
        private const val SIMPLIFY_MAX_POINT_COUNT = 3000

        /** 動画の出力解像度が短辺何pxを上限とするか（D-002決定6、拡大はしない）。 */
        internal const val MAX_OUTPUT_SHORT_SIDE_PX = 1080

        /**
         * 出力解像度を、[snapshotWidthPx]/[snapshotHeightPx]のアスペクト比のまま、短辺が
         * [MAX_OUTPUT_SHORT_SIDE_PX]を超えないよう決める（D-002決定6、固定アスペクト比を決め打ちしない・拡大はしない）。
         * H.264エンコーダの制約（幅・高さが偶数であること）に合わせ、結果は必ず偶数へ丸める。
         */
        internal fun computeOutputResolution(snapshotWidthPx: Int, snapshotHeightPx: Int): Pair<Int, Int> {
            require(snapshotWidthPx > 0 && snapshotHeightPx > 0) {
                "snapshotWidthPx/snapshotHeightPxは正の値である必要があります: $snapshotWidthPx x $snapshotHeightPx"
            }
            val shortSide = min(snapshotWidthPx, snapshotHeightPx)
            val scale = if (shortSide > MAX_OUTPUT_SHORT_SIDE_PX) MAX_OUTPUT_SHORT_SIDE_PX.toDouble() / shortSide else 1.0
            val width = roundToEven((snapshotWidthPx * scale).roundToInt())
            val height = roundToEven((snapshotHeightPx * scale).roundToInt())
            return width to height
        }

        private fun roundToEven(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
    }
}

/** [VideoExporter.export]が失敗した際にスローされる例外。[cause]にMedia3の[ExportException]を保持する。 */
class ExportFailedException(message: String, cause: Throwable) : Exception(message, cause)
