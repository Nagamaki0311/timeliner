package com.nagamaki0311.timeliner.process

import com.nagamaki0311.timeliner.model.RawTrack

/**
 * [TrackCleaner.clean]の閾値パラメータ。既定値はdocs/tasks.md T-004の仕様に基づく。
 */
data class CleanOptions(
    /** この速度（km/h）を超え、かつ直前・直後の点とは整合しない単発のスパイクとみなす閾値。 */
    val maxSpeedKmh: Double = 300.0,
    /** 直前の採用点からこの距離(m)未満の点は停留ジッタ候補とする。 */
    val stationaryDistanceMeters: Double = 15.0,
    /** 直前の採用点からこの経過時間(ms)未満の点は停留ジッタ候補とする。 */
    val stationaryTimeMillis: Long = 60_000L,
    /** 連続点間の経過時間がこれを超える箇所でポリラインを分割する（既定6時間）。 */
    val segmentGapMillis: Long = 6L * 60 * 60 * 1000
)

/**
 * [TrackCleaner.clean]の出力。ノイズ除去済みの点列（時刻昇順・重複除去済み）と、
 * 長時間欠損（既定6時間超）で分割したセグメントの開始インデックス一覧を保持する。
 *
 * セグメント`i`の範囲は[segmentRange]で取得できる。描画側はセグメント間を直線で結ばないこと。
 */
class CleanedTrack(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val timestampsMillis: LongArray,
    val segmentStartIndices: IntArray
) {
    init {
        require(latitudes.size == longitudes.size && latitudes.size == timestampsMillis.size) {
            "緯度・経度・時刻の配列長が一致しません: " +
                "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
        }
    }

    val pointCount: Int get() = latitudes.size
    val segmentCount: Int get() = segmentStartIndices.size

    /** セグメント[segmentIndex]が対応する点のインデックス範囲（`[start, end)`）を返す。 */
    fun segmentRange(segmentIndex: Int): IntRange {
        val start = segmentStartIndices[segmentIndex]
        val end = if (segmentIndex + 1 < segmentStartIndices.size) segmentStartIndices[segmentIndex + 1] else pointCount
        return start until end
    }
}

/**
 * [RawTrack]を表示・アニメーション向けに整形するパイプライン（docs/tasks.md T-004）。
 *
 * 正規化 → 速度スパイク除去 → 停留ジッタ抑制 → 長時間欠損での分断判定、の4段で処理する。
 * 簡略化（Douglas-Peucker）は[Simplifier]が別途担当する。大量点でのGC負荷を抑えるため、
 * 各段は[RawTrack]同様にDoubleArray/LongArrayベースで処理する。
 */
object TrackCleaner {

    fun clean(track: RawTrack, options: CleanOptions = CleanOptions()): CleanedTrack {
        val normalized = normalize(track)
        val despiked = removeSpeedSpikes(normalized, options.maxSpeedKmh)
        val stabilized = suppressStationaryJitter(despiked, options.stationaryDistanceMeters, options.stationaryTimeMillis)
        val segmentStartIndices = computeSegmentStartIndices(stabilized, options.segmentGapMillis)
        return CleanedTrack(stabilized.latitudes, stabilized.longitudes, stabilized.timestampsMillis, segmentStartIndices)
    }

    /**
     * 時刻昇順ソート・同一時刻の重複除去・範囲外座標（|lat|>90, |lon|>180）と(0,0)の破棄を行う。
     * [RawTrackBuilder.build]で既にソート済みの入力を前提としないよう、このモジュール単体でも
     * 昇順であることを保証する（未ソートの場合のみ安定ソートし直す）。
     */
    internal fun normalize(track: RawTrack): PointSeries {
        val size = track.pointCount
        if (size == 0) return PointSeries(DoubleArray(0), DoubleArray(0), LongArray(0))

        val order = sortedIndices(track.timestampsMillis, size)
        val buffer = PointBuffer(size)
        var previousTimestamp: Long? = null
        for (idx in order) {
            val lat = track.latitudes[idx]
            val lon = track.longitudes[idx]
            val time = track.timestampsMillis[idx]
            if (previousTimestamp == time) continue
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue
            if (lat == 0.0 && lon == 0.0) continue
            buffer.addPoint(lat, lon, time)
            previousTimestamp = time
        }
        return buffer.trim()
    }

    /** 既に昇順ならボクシングを避けた恒等インデックス列を、そうでなければ安定ソート済みインデックス列を返す。 */
    private fun sortedIndices(timestamps: LongArray, size: Int): IntArray {
        for (i in 1 until size) {
            if (timestamps[i] < timestamps[i - 1]) {
                return (0 until size).sortedBy { timestamps[it] }.toIntArray()
            }
        }
        return IntArray(size) { it }
    }

    /**
     * 連続点間の速度が[maxSpeedKmh]を超え、かつ直前・直後の点とは整合する「1点だけ飛んで戻る」
     * パターンをスパイクとして除去する。単純な速度上限だけで判定すると航空機区間等の正当な高速移動を
     * 丸ごと落とすため、スキップ後（直前の採用点→次点）の速度が閾値内に収まることも確認する。
     */
    internal fun removeSpeedSpikes(series: PointSeries, maxSpeedKmh: Double): PointSeries {
        val size = series.size
        if (size < 3) return series

        val maxSpeedMetersPerSecond = maxSpeedKmh * 1000.0 / 3600.0
        val lat = series.latitudes
        val lon = series.longitudes
        val time = series.timestampsMillis

        val buffer = PointBuffer(size)
        buffer.addPoint(lat[0], lon[0], time[0])
        var lastKeptIdx = 0
        var i = 1
        while (i < size - 1) {
            val speedPrev = speedMetersPerSecond(lat[lastKeptIdx], lon[lastKeptIdx], time[lastKeptIdx], lat[i], lon[i], time[i])
            val speedNext = speedMetersPerSecond(lat[i], lon[i], time[i], lat[i + 1], lon[i + 1], time[i + 1])
            if (speedPrev > maxSpeedMetersPerSecond && speedNext > maxSpeedMetersPerSecond) {
                val speedSkip = speedMetersPerSecond(
                    lat[lastKeptIdx], lon[lastKeptIdx], time[lastKeptIdx],
                    lat[i + 1], lon[i + 1], time[i + 1]
                )
                if (speedSkip <= maxSpeedMetersPerSecond) {
                    // スパイク: 直前点と直後点は整合するため、この1点だけを除去する。
                    i++
                    continue
                }
            }
            buffer.addPoint(lat[i], lon[i], time[i])
            lastKeptIdx = i
            i++
        }
        buffer.addPoint(lat[size - 1], lon[size - 1], time[size - 1])
        return buffer.trim()
    }

    /** 直前の採用点から距離[distanceMeters]未満かつ経過時間[timeMillis]未満の点はGPSの揺れとして捨てる。 */
    internal fun suppressStationaryJitter(series: PointSeries, distanceMeters: Double, timeMillis: Long): PointSeries {
        val size = series.size
        if (size == 0) return series

        val lat = series.latitudes
        val lon = series.longitudes
        val time = series.timestampsMillis

        val buffer = PointBuffer(size)
        buffer.addPoint(lat[0], lon[0], time[0])
        var lastLat = lat[0]
        var lastLon = lon[0]
        var lastTime = time[0]
        for (i in 1 until size) {
            val elapsed = time[i] - lastTime
            val distance = Mercator.haversineDistanceMeters(lastLat, lastLon, lat[i], lon[i])
            if (distance < distanceMeters && elapsed < timeMillis) {
                continue
            }
            buffer.addPoint(lat[i], lon[i], time[i])
            lastLat = lat[i]
            lastLon = lon[i]
            lastTime = time[i]
        }
        return buffer.trim()
    }

    /** 連続点間の経過時間が[gapMillis]を超える箇所をポリラインの分断点として、セグメント開始インデックス一覧を返す。 */
    internal fun computeSegmentStartIndices(series: PointSeries, gapMillis: Long): IntArray {
        val size = series.size
        if (size == 0) return IntArray(0)

        val starts = ArrayList<Int>()
        starts.add(0)
        val time = series.timestampsMillis
        for (i in 1 until size) {
            if (time[i] - time[i - 1] > gapMillis) {
                starts.add(i)
            }
        }
        return starts.toIntArray()
    }

    private fun speedMetersPerSecond(lat1: Double, lon1: Double, t1: Long, lat2: Double, lon2: Double, t2: Long): Double {
        val elapsedMillis = t2 - t1
        if (elapsedMillis <= 0) return Double.MAX_VALUE
        val distanceMeters = Mercator.haversineDistanceMeters(lat1, lon1, lat2, lon2)
        return distanceMeters / (elapsedMillis / 1000.0)
    }
}

/** [TrackCleaner]の各段の入出力に使う、DoubleArray/LongArrayベースの軽量な点列。 */
internal class PointSeries(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val timestampsMillis: LongArray
) {
    val size: Int get() = latitudes.size
}

/**
 * [PointSeries]構築用の内部ビルダー。[com.nagamaki0311.timeliner.data.parser.TimelineJsonParser]内の
 * `RawTrackBuilder`と同様、倍々に拡張するDoubleArray/LongArrayで蓄積しボクシングを避ける。
 */
private class PointBuffer(initialCapacity: Int) {
    private var latitudes = DoubleArray(initialCapacity.coerceAtLeast(1))
    private var longitudes = DoubleArray(initialCapacity.coerceAtLeast(1))
    private var timestamps = LongArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun addPoint(latitude: Double, longitude: Double, timestampMillis: Long) {
        if (size == latitudes.size) {
            grow()
        }
        latitudes[size] = latitude
        longitudes[size] = longitude
        timestamps[size] = timestampMillis
        size++
    }

    private fun grow() {
        val newCapacity = latitudes.size * 2
        latitudes = latitudes.copyOf(newCapacity)
        longitudes = longitudes.copyOf(newCapacity)
        timestamps = timestamps.copyOf(newCapacity)
    }

    fun trim(): PointSeries = PointSeries(
        latitudes.copyOf(size),
        longitudes.copyOf(size),
        timestamps.copyOf(size)
    )
}
