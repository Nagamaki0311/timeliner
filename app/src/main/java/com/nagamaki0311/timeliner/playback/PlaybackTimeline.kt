package com.nagamaki0311.timeliner.playback

import com.nagamaki0311.timeliner.process.Mercator

/**
 * データ時刻（実際のGPS計測時刻、epochミリ秒）↔ 再生時刻（アニメーション経過時間、0起点ミリ秒）の
 * 単調写像（docs/tasks.md T-007、docs/decisions.md D-002）。
 *
 * 画面再生（[PlaybackController]）とT-008の動画書き出し（フレームタイムスタンプ→データ時刻の変換）で
 * この同じ写像を共有する設計とする（D-002）。純Kotlinのみに依存し`android.*`のAPIは使わない
 * （D-003と同様の理由でJVM単体テストから直接検証できるようにするため）。
 *
 * 内部表現は「区切り点の配列（[dataMillis]/[playbackMillis]、共に非減少）＋二分探索＋線形補間」。
 * 点数が数万〜十万規模でも、任意の再生時刻→データ時刻、および任意のデータ時刻→再生時刻の変換が
 * O(log n)で済む（毎フレーム呼ばれても重くならない、タスク指示の性能要件）。
 */
class PlaybackTimeline private constructor(
    private val dataMillis: LongArray,
    private val playbackMillis: LongArray
) {
    init {
        require(dataMillis.isNotEmpty()) { "dataMillisは1点以上である必要があります" }
        require(dataMillis.size == playbackMillis.size) {
            "dataMillis/playbackMillisの配列長が一致しません: data=${dataMillis.size}, playback=${playbackMillis.size}"
        }
        // 二分探索の前提（両配列とも非減少）が壊れていないかの最小限の確認（AGENTS.md原則6）。
        for (i in 1 until dataMillis.size) {
            require(dataMillis[i] >= dataMillis[i - 1]) { "dataMillisは昇順である必要があります: index=$i" }
            require(playbackMillis[i] >= playbackMillis[i - 1]) { "playbackMillisは単調非減少である必要があります: index=$i" }
        }
    }

    /** 再生開始（0ミリ秒）から再生終了までの総再生時間（ミリ秒）。 */
    fun totalPlaybackMillis(): Long = playbackMillis[playbackMillis.size - 1]

    /** 再生経過時間（ミリ秒、範囲外は[0, totalPlaybackMillis()]へクランプ）に対応するデータ時刻を返す。 */
    fun dataTimeAtPlaybackMillis(playbackMillisValue: Long): Long {
        val clamped = playbackMillisValue.coerceIn(0L, totalPlaybackMillis())
        return interpolate(playbackMillis, dataMillis, clamped)
    }

    /** データ時刻（ミリ秒、範囲外は[dataMillis.first(), dataMillis.last()]へクランプ）に対応する再生経過時間を返す。 */
    fun playbackMillisAtDataTime(dataTimeMillis: Long): Long {
        val clamped = dataTimeMillis.coerceIn(dataMillis[0], dataMillis[dataMillis.size - 1])
        return interpolate(dataMillis, playbackMillis, clamped)
    }

    /** [fromAxis]上の[value]（[fromAxis]の範囲内であること）に対応する[toAxis]の値を、二分探索＋線形補間で求める。 */
    private fun interpolate(fromAxis: LongArray, toAxis: LongArray, value: Long): Long {
        val index = fromAxis.binarySearch(value)
        if (index >= 0) return toAxis[index]

        val insertionPoint = -index - 1
        val hi = insertionPoint.coerceIn(1, fromAxis.size - 1)
        val lo = hi - 1
        val fromLo = fromAxis[lo]
        val fromHi = fromAxis[hi]
        if (fromHi == fromLo) return toAxis[lo]
        val fraction = (value - fromLo).toDouble() / (fromHi - fromLo).toDouble()
        return toAxis[lo] + Math.round((toAxis[hi] - toAxis[lo]) * fraction)
    }

    companion object {
        /** 関心度の時間経過項の重み（関心度 = α×経過ミリ秒 + β×移動距離メートル）の既定値。 */
        private const val DEFAULT_ALPHA = 1.0

        /**
         * 関心度の移動距離項の重み（1メートルの移動を何ミリ秒の「時間経過」相当として扱うか）の既定値。
         * 大きいほど移動区間が（滞在区間に対して相対的に）ゆっくり再生される。
         */
        private const val DEFAULT_BETA = 1000.0

        /**
         * 自動モード: 「関心度」（時間経過＋移動距離の重み付き積分）の累積に対して再生時刻が等速に進むよう
         * 写像を構築する。滞在中・夜間は関心度がほぼ時間経過分（α×dt）しか積み上がらないため自動的に
         * 早送りされ、移動中は距離項（β×distance）が加わり自然な速度に近づく。
         * 総再生時間は[targetDurationMillis]に正規化する（丸め誤差を吸収するため最終点は必ず一致させる）。
         *
         * @param timestampsMillis 時刻昇順の点列（[latitudes]/[longitudes]と同じ長さ）。
         */
        fun buildAuto(
            timestampsMillis: LongArray,
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            targetDurationMillis: Long,
            alpha: Double = DEFAULT_ALPHA,
            beta: Double = DEFAULT_BETA
        ): PlaybackTimeline {
            require(timestampsMillis.isNotEmpty()) { "timestampsMillisは1点以上である必要があります" }
            require(latitudes.size == timestampsMillis.size && longitudes.size == timestampsMillis.size) {
                "緯度・経度・時刻の配列長が一致しません: " +
                    "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
            }
            require(targetDurationMillis > 0) { "targetDurationMillisは正の値である必要があります: $targetDurationMillis" }

            val size = timestampsMillis.size
            val cumulativeInterest = DoubleArray(size)
            for (i in 1 until size) {
                val dt = (timestampsMillis[i] - timestampsMillis[i - 1]).coerceAtLeast(0L).toDouble()
                val distanceMeters = Mercator.haversineDistanceMeters(
                    latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i]
                )
                cumulativeInterest[i] = cumulativeInterest[i - 1] + alpha * dt + beta * distanceMeters
            }

            val totalInterest = cumulativeInterest[size - 1]
            val playback = LongArray(size)
            if (totalInterest <= 0.0) {
                // 全点が同一時刻・同一地点等、関心度が積み上がらない退化ケース。等間隔に割り当てる。
                for (i in 0 until size) {
                    playback[i] = if (size == 1) 0L else Math.round(targetDurationMillis * i.toDouble() / (size - 1))
                }
            } else {
                for (i in 0 until size) {
                    playback[i] = Math.round(targetDurationMillis * cumulativeInterest[i] / totalInterest)
                }
                // 丸め誤差により最終点が目標値からずれないよう、末尾のみ強制的に一致させる
                // （このelse節はtotalInterest > 0、すなわちsize >= 2の場合のみ到達する）。
                playback[size - 1] = targetDurationMillis
            }

            return PlaybackTimeline(timestampsMillis.copyOf(), playback)
        }

        /**
         * 手動モード: 最初の点からの経過データ時間を[speedMultiplier]で割った、一定倍率の写像を構築する
         * （例: `speedMultiplier=3600.0`でx3600、実時間1時間がデータ1秒として再生される）。
         */
        fun buildManual(timestampsMillis: LongArray, speedMultiplier: Double): PlaybackTimeline {
            require(timestampsMillis.isNotEmpty()) { "timestampsMillisは1点以上である必要があります" }
            require(speedMultiplier > 0.0) { "speedMultiplierは正の値である必要があります: $speedMultiplier" }

            val origin = timestampsMillis[0]
            val playback = LongArray(timestampsMillis.size) { i ->
                Math.round((timestampsMillis[i] - origin).toDouble() / speedMultiplier)
            }
            return PlaybackTimeline(timestampsMillis.copyOf(), playback)
        }
    }
}
