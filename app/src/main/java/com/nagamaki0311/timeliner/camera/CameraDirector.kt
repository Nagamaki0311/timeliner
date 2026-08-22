package com.nagamaki0311.timeliner.camera

import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.process.GeoBounds
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * ルート点列と[PlaybackTimeline]から、動画/画面再生のカメラキーフレーム列を計算する（docs/tasks.md T-023、
 * docs/decisions.md D-017フェーズ5・D-028）。
 *
 * D-028（T-022カメラ制御スパイク検証）の結論により、動画のカメラは連続軌道ではなく「有限個のキーフレーム
 * （カメラ位置のリスト）＋キーフレーム間はクロスフェード」方式に限定されている（`MapSnapshotter`は
 * 1シーケンス内で逐次再利用可能・同時並行不可のため、フレームごとの連続追従は現実的でない）。
 * このクラスは純Kotlinのみに依存し`android.*`のAPIは使わない（D-003/D-004/T-019等と同様の理由で
 * JVM単体テストから直接検証できるようにするため）。実際にキーフレームからスナップショットを取得する部分
 * （T-025）・画面再生でのカメラ追従（T-024）は別タスクであり、本クラスはまだどこからも呼び出されない
 * （先行実装、AGENTS.md原則2判定ラダー1の判断根拠はdocs/decisions.md該当コミットを参照）。
 *
 * 設計方針:
 * 1. **キーフレームの時間配置**: 新しい区間分割アルゴリズムを作らず、既存の[PlaybackTimeline]
 *    （T-019で改善済みの関心度モデルを内包し、滞在・夜間は圧縮、移動は関心度に応じて時間を割く）を
 *    再利用する。再生時刻（0〜総再生時間）を[keyframeIntervalMillis]間隔でサンプリングし、対応する
 *    データ時刻をキーフレームの基準時刻とすることで、「イベントが多い期間はキーフレームが密に、
 *    退屈な期間は疎に」という性質を追加のロジック無しで得る。
 * 2. **キーフレームごとのカメラ位置・ズーム**: 隣接キーフレーム間の中点で区切った時間窓に対応するデータ
 *    時刻範囲にあるルート点から[GeoBounds]でbboxを求め、その中心をカメラ中心、[CameraZoom]でそのbboxが
 *    収まる最小ズームレベルを求める。狭い範囲（滞在）は自動的に高いズーム、広い範囲（移動）は低いズームに
 *    なる。隣接する2つの窓は開始側のみ含む片側開区間（`[dataStart, dataEnd)`）として区切ることで、
 *    共有境界（窓iの終端＝窓i+1の始端の同じデータ時刻）にちょうど一致する点が両方の窓に二重に含まれない
 *    ようにする（D-029）。ただし最後の窓のみ`dataEnd`自身（再生時間全体の最終点）を含む閉区間として扱い、
 *    最後の点が取りこぼされないようにする。
 */
object CameraDirector {

    /** 1つのカメラキーフレーム。[playbackMillis]時点でのカメラ中心座標・ズームレベルを表す。 */
    data class CameraKeyframe(
        val playbackMillis: Long,
        val centerLatitude: Double,
        val centerLongitude: Double,
        val zoom: Double
    )

    /**
     * キーフレーム間隔の既定値（ミリ秒）。「長時間の静止画面凝視を避ける」という要件から数秒〜十秒程度
     * とし、5秒を採用した（目標再生時間60秒なら既定で13個程度のキーフレームになる）。
     */
    private const val DEFAULT_KEYFRAME_INTERVAL_MILLIS = 5_000L

    /**
     * ズーム計算に使うビューポートの既定サイズ（px）。D-002決定6「短辺1080pxを上限に解像度を決定」に
     * 合わせた正方形近似（実際のアスペクト比はT-025で書き出し時の値を渡す想定）。
     */
    private const val DEFAULT_VIEWPORT_WIDTH_PX = 1080
    private const val DEFAULT_VIEWPORT_HEIGHT_PX = 1080

    /**
     * [timestampsMillis]/[latitudes]/[longitudes]（時刻昇順、同じ長さ）と、それに対応する[timeline]
     * （[PlaybackTimeline.buildAuto]または[PlaybackTimeline.buildManual]で構築済みのもの）から、
     * カメラキーフレーム列を再生時刻昇順で返す。
     *
     * 極端なケース（[timestampsMillis]が空、点が1つのみ、全点が同一地点、総再生時間が非常に短い等）でも
     * クラッシュせず、妥当な結果（空の場合は空リスト、それ以外は1個以上のキーフレーム）を返す。
     *
     * @param keyframeIntervalMillis キーフレームの再生時刻上の間隔（ミリ秒、正の値）。
     * @param viewportWidthPx ズーム計算に使うビューポート幅（px、正の値）。
     * @param viewportHeightPx ズーム計算に使うビューポート高さ（px、正の値）。
     */
    fun computeKeyframes(
        timestampsMillis: LongArray,
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        timeline: PlaybackTimeline,
        keyframeIntervalMillis: Long = DEFAULT_KEYFRAME_INTERVAL_MILLIS,
        viewportWidthPx: Int = DEFAULT_VIEWPORT_WIDTH_PX,
        viewportHeightPx: Int = DEFAULT_VIEWPORT_HEIGHT_PX
    ): List<CameraKeyframe> {
        require(latitudes.size == timestampsMillis.size && longitudes.size == timestampsMillis.size) {
            "緯度・経度・時刻の配列長が一致しません: " +
                "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
        }
        require(keyframeIntervalMillis > 0) {
            "keyframeIntervalMillisは正の値である必要があります: $keyframeIntervalMillis"
        }
        require(viewportWidthPx > 0 && viewportHeightPx > 0) {
            "viewportWidthPx/viewportHeightPxは正の値である必要があります: " +
                "$viewportWidthPx x $viewportHeightPx"
        }

        if (timestampsMillis.isEmpty()) return emptyList()

        val totalPlaybackMillis = timeline.totalPlaybackMillis()
        val keyframeTimes = buildKeyframeTimes(totalPlaybackMillis, keyframeIntervalMillis)

        return keyframeTimes.indices.map { index ->
            val t = keyframeTimes[index]
            val isLastWindow = index == keyframeTimes.size - 1
            val windowStart = if (index == 0) 0L else (keyframeTimes[index - 1] + t) / 2
            val windowEnd = if (isLastWindow) totalPlaybackMillis else (t + keyframeTimes[index + 1]) / 2
            buildKeyframe(
                playbackMillis = t,
                windowStart = windowStart,
                windowEnd = windowEnd,
                isLastWindow = isLastWindow,
                timestampsMillis = timestampsMillis,
                latitudes = latitudes,
                longitudes = longitudes,
                timeline = timeline,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx
            )
        }
    }

    /**
     * 0から[totalPlaybackMillis]まで[intervalMillis]間隔でサンプリングした再生時刻の昇順リストを返す
     * （最後は必ず[totalPlaybackMillis]自身を含む）。[totalPlaybackMillis]が0以下（点が1つのみ等の
     * 退化ケース）の場合は`[0L]`のみを返す。
     */
    private fun buildKeyframeTimes(totalPlaybackMillis: Long, intervalMillis: Long): List<Long> {
        if (totalPlaybackMillis <= 0L) return listOf(0L)
        val times = mutableListOf<Long>()
        var t = 0L
        while (t < totalPlaybackMillis) {
            times.add(t)
            t += intervalMillis
        }
        if (times.last() != totalPlaybackMillis) times.add(totalPlaybackMillis)
        return times
    }

    private fun buildKeyframe(
        playbackMillis: Long,
        windowStart: Long,
        windowEnd: Long,
        isLastWindow: Boolean,
        timestampsMillis: LongArray,
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        timeline: PlaybackTimeline,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): CameraKeyframe {
        val (fromIndex, toIndex) = resolveWindowIndexRange(
            windowStart, windowEnd, isLastWindow, timestampsMillis, timeline
        )

        val bounds = GeoBounds.compute(latitudes, longitudes, fromIndex, toIndex)
        val centerLatitude = (bounds.minLatitude + bounds.maxLatitude) / 2.0
        val centerLongitude = (bounds.minLongitude + bounds.maxLongitude) / 2.0
        val zoom = CameraZoom.zoomToFitBounds(bounds, viewportWidthPx, viewportHeightPx)
        return CameraKeyframe(playbackMillis, centerLatitude, centerLongitude, zoom)
    }

    /**
     * 再生時刻の時間窓[windowStart]〜[windowEnd]に対応するデータ時刻範囲にある[timestampsMillis]
     * （時刻昇順）のインデックス範囲を`[fromIndex, toIndex)`半開区間（[GeoBounds.compute]と同じ規約）
     * のPairで返す。開始側（`dataStart`）は含み、終了側（`dataEnd`）は含まない片側開区間とすることで、
     * 隣接する2つの窓の共有境界（窓iの`windowEnd`＝窓i+1の`windowStart`、変換後は同じデータ時刻になる）
     * にちょうど一致する点が両方の窓に二重に含まれないようにする（D-029）。ただし[isLastWindow]が
     * trueの場合のみ`dataEnd`自身も含む閉区間として扱い、再生時間全体の最後の点を取りこぼさないようにする。
     *
     * 窓の中に厳密に収まる点が1つも無い退化ケース（大きな移動の途中で記録点が疎な区間等）では、
     * 窓の直前・直後の点（移動の両端）にブラケットした範囲を返すことで、その移動全体が見えるbboxにする
     * （単に最も近い1点へフォールバックすると「都市間の自然なカメラ遷移」要件を満たせないため）。
     */
    internal fun resolveWindowIndexRange(
        windowStart: Long,
        windowEnd: Long,
        isLastWindow: Boolean,
        timestampsMillis: LongArray,
        timeline: PlaybackTimeline
    ): Pair<Int, Int> {
        val dataStart = timeline.dataTimeAtPlaybackMillis(windowStart)
        val dataEnd = timeline.dataTimeAtPlaybackMillis(windowEnd)
        val fromIndex = lowerBound(timestampsMillis, dataStart)
        val toIndex = if (isLastWindow) {
            upperBound(timestampsMillis, dataEnd)
        } else {
            lowerBound(timestampsMillis, dataEnd)
        }
        if (fromIndex >= toIndex) {
            val prevIndex = (fromIndex - 1).coerceAtLeast(0)
            val nextIndex = upperBound(timestampsMillis, dataEnd).coerceAtMost(timestampsMillis.size - 1)
            return prevIndex to (nextIndex + 1)
        }
        return fromIndex to toIndex
    }

    /** [array]（昇順ソート済み）中で`array[index] >= value`となる最初のindexを返す（無ければ`array.size`）。 */
    private fun lowerBound(array: LongArray, value: Long): Int {
        var lo = 0
        var hi = array.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (array[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [array]（昇順ソート済み）中で`array[index] > value`となる最初のindexを返す（無ければ`array.size`）。 */
    private fun upperBound(array: LongArray, value: Long): Int {
        var lo = 0
        var hi = array.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (array[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * 緯度経度のbboxが指定したビューポート（px）に収まる最小ズームレベルを、標準的なWebメルカトルの
 * 「1タイル256px、ズームレベルZで1タイルが360/2^Z度をカバーする」という関係式から計算する
 * （Google Maps/Mapbox系ライブラリで広く使われる`getBoundsZoomLevel`と同じ考え方）。
 * 純Kotlinのみに依存し、既存の地図SDK（MapLibre）のAPIは使わない。
 */
internal object CameraZoom {

    private const val TILE_SIZE_PX = 256.0

    /**
     * 返す最大ズームレベル。bboxが1点に潰れている（同一地点への滞在）等の退化ケースの上限値として使う。
     * [com.nagamaki0311.timeliner.ui.TimelineScreen]の`SINGLE_POINT_ZOOM`（単一点表示時のズーム）と
     * 同じ値を採用し、既存の「単一点はズーム15相当」という表示上の慣習と一貫させる。
     */
    internal const val MAX_ZOOM = 15.0

    private const val MIN_ZOOM = 0.0

    private val LN2 = ln(2.0)

    /**
     * [bounds]が[viewportWidthPx] x [viewportHeightPx]のビューポートに収まる最小ズームレベルを返す。
     *
     * `longitudeDiff < 0.0`の分岐は経度180度（日付変更線）をまたぐbboxの補正を意図しているが、
     * [bounds]の元になる[GeoBounds]（新規範囲指定版・既存全点版とも）自体が単純min/maxでラップアラウンドを
     * 考慮しないため`longitudeDiff`が負になることは実際には起こらず、現状この補正は到達しないデッドコードで
     * 日付変更線をまたぐbboxのズームは正しく計算されない（`GeoBounds`の日付変更線非対応というT-006以来の
     * 既知の制約に起因、D-007・D-029参照）。
     */
    fun zoomToFitBounds(bounds: GeoBounds.Bounds, viewportWidthPx: Int, viewportHeightPx: Int): Double {
        val latFraction = (latitudeRadiansOnMercator(bounds.maxLatitude) -
            latitudeRadiansOnMercator(bounds.minLatitude)) / PI
        val longitudeDiff = bounds.maxLongitude - bounds.minLongitude
        val lngFraction = (if (longitudeDiff < 0.0) longitudeDiff + 360.0 else longitudeDiff) / 360.0

        val latZoom = zoomForFraction(viewportHeightPx.toDouble(), latFraction)
        val lngZoom = zoomForFraction(viewportWidthPx.toDouble(), lngFraction)
        return min(latZoom, lngZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** ビューポートの1辺の長さ[sizePx]（px）に対し、bboxが占める割合[fraction]（0〜1）が収まるズームを返す。 */
    private fun zoomForFraction(sizePx: Double, fraction: Double): Double {
        // fractionが0（同一緯度または同一経度、1点に潰れている等）だとlog(∞)相当になるため、
        // 最大ズームへフォールバックする（「狭い範囲ほど高いズーム」という要件を満たす自然な極限）。
        if (fraction <= 0.0) return MAX_ZOOM
        return ln(sizePx / TILE_SIZE_PX / fraction) / LN2
    }

    /** Webメルカトル投影でのYスケールに対応する緯度の変換（度→ラジアン相当、[-π, π]にクランプ）。 */
    private fun latitudeRadiansOnMercator(latitudeDegrees: Double): Double {
        val sinLat = sin(Math.toRadians(latitudeDegrees))
        val projected = ln((1.0 + sinLat) / (1.0 - sinLat)) / 2.0
        return projected.coerceIn(-PI, PI) / 2.0
    }
}
