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
        /**
         * 関心度の時間経過項の重み（関心度 = α×頭打ち後経過ミリ秒 + β×頭打ち後移動距離メートル + γ×区間数）
         * の既定値。頭打ち後の値は[saturate]により、実際の値が0に近いうちはほぼ線形（従来通り）、
         * [DEFAULT_STATIONARY_SATURATION_MILLIS]等に対して大きくなるほど増分が滑らかに減衰する。
         */
        private const val DEFAULT_ALPHA = 1.0

        /**
         * 関心度の移動距離項の重み（頭打ち後1メートル相当の移動を何ミリ秒の「時間経過」相当として
         * 扱うか）の既定値。大きいほど移動区間が（滞在区間に対して相対的に）ゆっくり再生される。
         */
        private const val DEFAULT_BETA = 1000.0

        /**
         * 滞在時間項（α×dt）の頭打ち閾値（ミリ秒、T-019）。指数飽和関数
         * `cap × (1 - exp(-dt / cap))` のcapとして使う。dtがこの値に対して十分小さいうちはほぼ線形
         * （dt自身に近い値）だが、大きくなるほどこの値に漸近する。深夜の睡眠等、長時間の静止に
         * 実時間比例で再生時間を割り当て続けないための頭打ち（既定30分）。
         */
        private const val DEFAULT_STATIONARY_SATURATION_MILLIS = 30.0 * 60.0 * 1000.0

        /**
         * 移動距離項（β×distance）の頭打ち閾値（メートル、T-019）。[DEFAULT_STATIONARY_SATURATION_MILLIS]
         * と同じ指数飽和関数を距離に対して適用する。市街地の移動等、通常の区間距離ではほぼ線形のまま、
         * 飛行機移動等の一度の非常に長い移動区間が再生時間予算を過剰占有しないための頭打ち（既定5km）。
         */
        private const val DEFAULT_MOVEMENT_SATURATION_METERS = 5_000.0

        /**
         * イベント密度項の重み（T-019）。隣接点1区間ごとに定数として関心度へ加算する
         * （dt・distanceに依存しない）。同じ実時間・距離でも記録点が密な区間（区間数が多い区間）ほど
         * 「見せるべき情報が多い」とみなし、より多くの再生時間を配分するための項。既定値は
         * 典型的な区間のα×dt寄与（数秒〜数十秒相当）に対して十分小さく、通常の挙動を大きく変えない。
         */
        private const val DEFAULT_DENSITY_WEIGHT_MILLIS = 300.0

        /**
         * 指数飽和関数 `cap × (1 - exp(-value / cap))`。[value]が[cap]に対して十分小さいうちは
         * ほぼ[value]自身（線形）だが、[value]が大きくなるほど[cap]に漸近し、増分の寄与が滑らかに
         * 減衰する（T-019、滞在時間・移動距離の頭打りに共通利用）。
         */
        private fun saturate(value: Double, cap: Double): Double = cap * (1.0 - Math.exp(-value / cap))

        /**
         * 自動モード: 「関心度」（時間経過＋移動距離＋イベント密度の重み付き積分）の累積に対して
         * 再生時刻が等速に進むよう写像を構築する。滞在中・夜間は関心度がほぼ時間経過分（α×頭打ち後dt）
         * しか積み上がらないため自動的に早送りされ、移動中は距離項（β×頭打ち後distance）が加わり
         * 自然な速度に近づく。時間経過項・距離項は[saturate]により頭打ちがあり（T-019）、長時間の静止
         * ・一度の非常に長い移動が再生時間予算を過剰占有しない。加えて区間ごとに一定の密度項（γ）を
         * 加算し、記録点が密な区間ほど関心度が高くなるようにする。
         * 総再生時間は[targetDurationMillis]に正規化する（丸め誤差を吸収するため最終点は必ず一致させる）。
         *
         * 密度項（γ、[densityWeightMillis]）は区間数に比例して加算されるため、[timestampsMillis]等が
         * Douglas-Peucker簡略化等で間引かれていない生の記録点列であることを前提とする。簡略化後の
         * 点列を渡すと元々点が密だった区間の「密度」が正しく反映されなくなる。
         *
         * @param timestampsMillis 時刻昇順の点列（[latitudes]/[longitudes]と同じ長さ）。
         */
        fun buildAuto(
            timestampsMillis: LongArray,
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            targetDurationMillis: Long,
            alpha: Double = DEFAULT_ALPHA,
            beta: Double = DEFAULT_BETA,
            stationarySaturationMillis: Double = DEFAULT_STATIONARY_SATURATION_MILLIS,
            movementSaturationMeters: Double = DEFAULT_MOVEMENT_SATURATION_METERS,
            densityWeightMillis: Double = DEFAULT_DENSITY_WEIGHT_MILLIS
        ): PlaybackTimeline {
            require(timestampsMillis.isNotEmpty()) { "timestampsMillisは1点以上である必要があります" }
            require(latitudes.size == timestampsMillis.size && longitudes.size == timestampsMillis.size) {
                "緯度・経度・時刻の配列長が一致しません: " +
                    "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
            }
            require(targetDurationMillis > 0) { "targetDurationMillisは正の値である必要があります: $targetDurationMillis" }
            require(stationarySaturationMillis > 0.0) {
                "stationarySaturationMillisは正の値である必要があります: $stationarySaturationMillis"
            }
            require(movementSaturationMeters > 0.0) {
                "movementSaturationMetersは正の値である必要があります: $movementSaturationMeters"
            }
            require(densityWeightMillis >= 0.0) {
                "densityWeightMillisは0以上である必要があります: $densityWeightMillis"
            }

            val size = timestampsMillis.size
            val cumulativeInterest = DoubleArray(size)
            for (i in 1 until size) {
                val dt = (timestampsMillis[i] - timestampsMillis[i - 1]).coerceAtLeast(0L).toDouble()
                val distanceMeters = Mercator.haversineDistanceMeters(
                    latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i]
                )
                val saturatedDt = saturate(dt, stationarySaturationMillis)
                val saturatedDistance = saturate(distanceMeters, movementSaturationMeters)
                cumulativeInterest[i] = cumulativeInterest[i - 1] +
                    alpha * saturatedDt + beta * saturatedDistance + densityWeightMillis
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
