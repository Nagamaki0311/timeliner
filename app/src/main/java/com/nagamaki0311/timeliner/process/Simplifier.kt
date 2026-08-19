package com.nagamaki0311.timeliner.process

/**
 * Douglas-Peuckerによる経路の簡略化（docs/tasks.md T-004）。
 *
 * Webメルカトル投影後のメートル空間で実行し、**明示スタック**（[RangeStack]、ヒープ上のLongArray）で
 * 処理範囲を管理する（数十万点規模で再帰実装だと`StackOverflowError`になりうるため）。
 *
 * 時間ガード: 連続点間の経過時間が[simplify]の`timeGuardMillis`を超える点は、
 * 幾何的な偏差が`epsilonMeters`未満でも必ず残す（高速移動中の点が間引かれて
 * アニメーションの現在位置がワープするのを防ぐため）。
 */
object Simplifier {

    /**
     * @param latitudes/longitudes/timestampsMillis 単一の連続した点列（[TrackCleaner]が分断した
     *   1セグメント分を想定。分断済みの複数セグメントを跨いで呼び出さないこと）。
     * @param epsilonMeters Douglas-Peuckerの許容偏差（メートル）。
     * @param timeGuardMillis この時間を超えて隣接する点は距離に関わらず必ず残す。
     * @param maxPointCount 指定した場合、DP適用後もこの点数を超えるならepsilonを倍々にして再実行する
     *   （時間ガードで保護された点だけで上限を超える場合はそれ以上減らせないため、そこで打ち切る）。
     * @return 残す点の元配列上でのインデックス一覧（昇順、重複なし。常に先頭と末尾を含む）。
     */
    fun simplify(
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        timestampsMillis: LongArray,
        epsilonMeters: Double,
        timeGuardMillis: Long = 5L * 60 * 1000,
        maxPointCount: Int? = null
    ): IntArray {
        require(latitudes.size == longitudes.size && latitudes.size == timestampsMillis.size) {
            "緯度・経度・時刻の配列長が一致しません: " +
                "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
        }
        val size = latitudes.size
        if (size <= 2) return IntArray(size) { it }

        val xs = DoubleArray(size) { Mercator.longitudeToX(longitudes[it]) }
        val ys = DoubleArray(size) { Mercator.latitudeToY(latitudes[it]) }
        val protectedByTimeGuard = computeTimeGuardProtection(timestampsMillis, timeGuardMillis)

        var epsilon = epsilonMeters
        var result = runDouglasPeucker(xs, ys, protectedByTimeGuard, epsilon)
        if (maxPointCount != null) {
            // 点数はepsilonの単調非増加関数だが、単純な「1回変化しなければ打ち切り」は
            // epsilonが初期値近辺でしばらく無効化のまま停滞するケース（点数が変わらない区間が
            // 一時的に続く）を早期に取り違えて打ち切ってしまう。反復回数の上限のみで安全に打ち切る
            // （時間ガードで保護された点だけで上限を超える場合は、上限反復後もその点数のまま返る）。
            // epsilonMeters<=0で渡された場合は倍々にしても増えないため、倍化専用の最小値から始める。
            if (epsilon <= 0.0) epsilon = MIN_DOUBLING_EPSILON_METERS
            var iterations = 0
            while (result.size > maxPointCount && iterations < MAX_EPSILON_DOUBLINGS) {
                epsilon *= 2
                result = runDouglasPeucker(xs, ys, protectedByTimeGuard, epsilon)
                iterations++
            }
        }
        return result
    }

    /** 隣接点との経過時間が[timeGuardMillis]を超える箇所の両端を、DPで除去してはいけない点として印付ける。 */
    private fun computeTimeGuardProtection(timestampsMillis: LongArray, timeGuardMillis: Long): BooleanArray {
        val size = timestampsMillis.size
        val protectedFlags = BooleanArray(size)
        for (i in 1 until size) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > timeGuardMillis) {
                protectedFlags[i - 1] = true
                protectedFlags[i] = true
            }
        }
        return protectedFlags
    }

    private fun runDouglasPeucker(
        xs: DoubleArray,
        ys: DoubleArray,
        protectedByTimeGuard: BooleanArray,
        epsilonMeters: Double
    ): IntArray {
        val size = xs.size
        val keep = BooleanArray(size)
        keep[0] = true
        keep[size - 1] = true

        val stack = RangeStack(INITIAL_STACK_CAPACITY)
        stack.push(0, size - 1)
        while (stack.isNotEmpty()) {
            val range = stack.pop()
            val start = rangeStart(range)
            val end = rangeEnd(range)
            if (end - start < 2) continue // 範囲内に内部点なし

            var maxEffectiveDistance = -1.0
            var maxActualDistance = -1.0
            var farIndex = -1
            for (i in start + 1 until end) {
                val actualDistance = perpendicularDistanceMeters(xs, ys, start, end, i)
                // 時間ガード対象点は距離によらず必ず分割点として選ばれるよう、実効距離を最大化する。
                val effectiveDistance = if (protectedByTimeGuard[i]) Double.MAX_VALUE else actualDistance
                if (effectiveDistance > maxEffectiveDistance) {
                    maxEffectiveDistance = effectiveDistance
                    maxActualDistance = actualDistance
                    farIndex = i
                }
            }
            if (farIndex == -1) continue

            if (protectedByTimeGuard[farIndex] || maxActualDistance > epsilonMeters) {
                keep[farIndex] = true
                stack.push(start, farIndex)
                stack.push(farIndex, end)
            }
        }

        var keptCount = 0
        for (i in 0 until size) if (keep[i]) keptCount++
        val result = IntArray(keptCount)
        var writeIndex = 0
        for (i in 0 until size) {
            if (keep[i]) {
                result[writeIndex] = i
                writeIndex++
            }
        }
        return result
    }

    /** 点[pointIndex]から、点[startIndex]・[endIndex]を通る直線までの垂直距離（メートル）。 */
    private fun perpendicularDistanceMeters(
        xs: DoubleArray,
        ys: DoubleArray,
        startIndex: Int,
        endIndex: Int,
        pointIndex: Int
    ): Double {
        val ax = xs[startIndex]
        val ay = ys[startIndex]
        val bx = xs[endIndex]
        val by = ys[endIndex]
        val px = xs[pointIndex]
        val py = ys[pointIndex]
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            return Math.hypot(px - ax, py - ay)
        }
        val cross = dx * (py - ay) - dy * (px - ax)
        return Math.abs(cross) / Math.hypot(dx, dy)
    }

    private const val INITIAL_STACK_CAPACITY = 64

    /** epsilonが0以下で渡された場合、倍々にして成長させる起点として使う最小値。 */
    private const val MIN_DOUBLING_EPSILON_METERS = 0.001

    /** `maxPointCount`を満たすためのepsilon倍化の最大反復回数（無限ループ防止）。 */
    private const val MAX_EPSILON_DOUBLINGS = 60

    private fun rangeStart(range: Long): Int = (range shr 32).toInt()
    private fun rangeEnd(range: Long): Int = range.toInt()
}

/**
 * Douglas-Peuckerの処理待ち区間`[start, end]`を保持する明示スタック。
 * ヒープ上のLongArray（start/endを1個のLongへビットパックして保持）で管理するため、
 * 再帰呼び出しによる`StackOverflowError`が発生しない。
 */
private class RangeStack(initialCapacity: Int) {
    private var ranges = LongArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun push(start: Int, end: Int) {
        if (size == ranges.size) {
            ranges = ranges.copyOf(ranges.size * 2)
        }
        ranges[size] = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
        size++
    }

    fun isNotEmpty(): Boolean = size > 0

    fun pop(): Long {
        size--
        return ranges[size]
    }
}
