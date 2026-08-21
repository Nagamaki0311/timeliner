package com.nagamaki0311.timeliner.process

/**
 * Douglas-Peuckerによる経路の簡略化（docs/tasks.md T-004、大規模退化対応はT-012）。
 *
 * Webメルカトル投影後のメートル空間で実行し、**明示スタック**（[RangeStack]、ヒープ上のLongArray）で
 * 処理範囲を管理する（数十万点規模で再帰実装だと`StackOverflowError`になりうるため）。
 *
 * 時間ガード: 連続点間の経過時間が[simplify]の`timeGuardMillis`を超える点は、
 * 幾何的な偏差が`epsilonMeters`未満でも必ず残す（高速移動中の点が間引かれて
 * アニメーションの現在位置がワープするのを防ぐため）。時間ガード対象点はDP本体に
 * 距離比較として混ぜ込まず、あらかじめ点列を分割する区切り点として扱う（各区間は互いに
 * 独立にDPを適用し結果を結合する）。これにより計算量が時間ガード対象点の密度に依存して
 * 退化することを避ける（T-012、区切り点の間隔をsとすると全体でO(Σ m_j log-ish m_j)、
 * m_jは各区間長でΣm_j = 全点数）。分断済みの複数セグメントを跨いで呼び出さないこと。
 */
object Simplifier {

    /**
     * @param latitudes/longitudes/timestampsMillis 単一の連続した点列（[TrackCleaner]が分断した
     *   1セグメント分を想定。分断済みの複数セグメントを跨いで呼び出さないこと）。
     * @param epsilonMeters Douglas-Peuckerの許容偏差（メートル）。
     * @param timeGuardMillis この時間を超えて隣接する点は距離に関わらず必ず残す。
     * @param maxPointCount 指定した場合、DP適用後もこの点数を超えるならepsilonを倍々にして再実行する。
     *   それでも上限を超える場合は、時間ガードで保護された点（区切り点）を優先的に残したまま
     *   均等間引きし、必ず上限以下に収める（ハードキャップ）。保護点数自体が上限を超える場合のみ、
     *   保護点も間引き対象になる（データが密すぎて上限内に収まらない原理的な限界のため許容する）。
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
        val breakpoints = computeBreakpoints(timestampsMillis, timeGuardMillis)

        var epsilon = epsilonMeters
        var result = runDouglasPeucker(xs, ys, breakpoints, epsilon)
        if (maxPointCount != null) {
            // 点数はepsilonの単調非増加関数だが、単純な「1回変化しなければ打ち切り」は
            // epsilonが初期値近辺でしばらく無効化のまま停滞するケース（点数が変わらない区間が
            // 一時的に続く）を早期に取り違えて打ち切ってしまう。反復回数の上限のみで安全に打ち切る。
            // epsilonMeters<=0で渡された場合は倍々にしても増えないため、倍化専用の最小値から始める。
            if (epsilon <= 0.0) epsilon = MIN_DOUBLING_EPSILON_METERS
            var iterations = 0
            while (result.size > maxPointCount && iterations < MAX_EPSILON_DOUBLINGS) {
                epsilon *= 2
                result = runDouglasPeucker(xs, ys, breakpoints, epsilon)
                iterations++
            }
            // epsilon倍化ではそれ以上減らせない場合に備え、時間ガード保護点（breakpoints）を
            // 優先的に残したまま均等間引きして必ず上限以下に落とす（D-018）。
            if (result.size > maxPointCount) {
                result = decimateToLimit(result, breakpoints, maxPointCount)
            }
        }
        return result
    }

    /**
     * 時間ガード対象の点（隣接点との経過時間が[timeGuardMillis]を超える箇所の両端）と、
     * 点列の先頭・末尾を、DPが跨いではいけない区切り点として返す（昇順・重複なし）。
     */
    private fun computeBreakpoints(timestampsMillis: LongArray, timeGuardMillis: Long): IntArray {
        val size = timestampsMillis.size
        val isBreakpoint = BooleanArray(size)
        isBreakpoint[0] = true
        isBreakpoint[size - 1] = true
        for (i in 1 until size) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > timeGuardMillis) {
                isBreakpoint[i - 1] = true
                isBreakpoint[i] = true
            }
        }
        var count = 0
        for (i in 0 until size) if (isBreakpoint[i]) count++
        val breakpoints = IntArray(count)
        var writeIndex = 0
        for (i in 0 until size) {
            if (isBreakpoint[i]) {
                breakpoints[writeIndex] = i
                writeIndex++
            }
        }
        return breakpoints
    }

    /**
     * [breakpoints]で区切られた各区間へ独立にDouglas-Peuckerを適用する。区間の内部点は
     * 定義上すべて非区切り点（区切り点は区間の境界そのものになるため）であり、時間ガードの
     * 特別扱いは不要になる（通常の垂線距離比較のみで済む）。
     */
    private fun runDouglasPeucker(
        xs: DoubleArray,
        ys: DoubleArray,
        breakpoints: IntArray,
        epsilonMeters: Double
    ): IntArray {
        val size = xs.size
        val keep = BooleanArray(size)
        for (bp in breakpoints) keep[bp] = true

        val stack = RangeStack(INITIAL_STACK_CAPACITY)
        for (j in 0 until breakpoints.size - 1) {
            stack.push(breakpoints[j], breakpoints[j + 1])
        }
        while (stack.isNotEmpty()) {
            val range = stack.pop()
            val start = rangeStart(range)
            val end = rangeEnd(range)
            if (end - start < 2) continue // 範囲内に内部点なし

            var maxDistance = -1.0
            var farIndex = -1
            for (i in start + 1 until end) {
                val distance = perpendicularDistanceMeters(xs, ys, start, end, i)
                if (distance > maxDistance) {
                    maxDistance = distance
                    farIndex = i
                }
            }
            if (farIndex == -1) continue

            if (maxDistance > epsilonMeters) {
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

    /**
     * [result]（昇順・重複なしのインデックス列）を高々[maxPointCount]件に間引く（D-018）。
     * [breakpoints]（時間ガード保護点。[result]は常にその上位集合）由来の点は、その数が
     * [maxPointCount]以下である限り全て残し、残り枠を非保護点の均等間引きに充てる
     * （先頭・末尾は常に保護点なので自動的に保たれる）。保護点数自体が[maxPointCount]を
     * 超える場合のみ、保護点も含めて全体を均等間引きする。
     */
    private fun decimateToLimit(result: IntArray, breakpoints: IntArray, maxPointCount: Int): IntArray {
        val limit = maxPointCount.coerceAtLeast(2)
        val k = result.size
        if (k <= limit) return result

        val isProtected = markProtected(result, breakpoints)
        var protectedCount = 0
        for (p in isProtected) if (p) protectedCount++

        return if (protectedCount <= limit) {
            decimateNonProtected(result, isProtected, protectedCount, limit)
        } else {
            decimateEvenly(result, limit)
        }
    }

    /**
     * [breakpoints]（昇順・[result]の上位集合）由来の点が[result]のどの位置にあるかを
     * 二本指走査（O(result.size + breakpoints.size)）で求める。
     */
    private fun markProtected(result: IntArray, breakpoints: IntArray): BooleanArray {
        val isProtected = BooleanArray(result.size)
        var bi = 0
        for (ri in result.indices) {
            while (bi < breakpoints.size && breakpoints[bi] < result[ri]) bi++
            if (bi < breakpoints.size && breakpoints[bi] == result[ri]) {
                isProtected[ri] = true
                bi++
            }
        }
        return isProtected
    }

    /**
     * [result]のうち[isProtected]な点（[protectedCount]件、`<= limit`）を全て残し、
     * 残り枠（`limit - protectedCount`件）を非保護点から均等間引きで選ぶ。
     */
    private fun decimateNonProtected(
        result: IntArray,
        isProtected: BooleanArray,
        protectedCount: Int,
        limit: Int
    ): IntArray {
        val remainingSlots = (limit - protectedCount).coerceAtLeast(0)
        val nonProtectedCount = result.size - protectedCount
        val keepNonProtected = BooleanArray(nonProtectedCount)
        if (remainingSlots > 0 && nonProtectedCount > 0) {
            val slotsToKeep = remainingSlots.coerceAtMost(nonProtectedCount)
            if (slotsToKeep >= nonProtectedCount) {
                for (i in 0 until nonProtectedCount) keepNonProtected[i] = true
            } else {
                val denom = (slotsToKeep - 1).coerceAtLeast(1)
                for (j in 0 until slotsToKeep) {
                    val idx = if (slotsToKeep == 1) 0 else ((j.toLong() * (nonProtectedCount - 1)) / denom).toInt()
                    keepNonProtected[idx] = true
                }
            }
        }

        var keptNonProtectedCount = 0
        for (v in keepNonProtected) if (v) keptNonProtectedCount++
        val output = IntArray(protectedCount + keptNonProtectedCount)
        var outputIndex = 0
        var nonProtectedPointer = 0
        for (i in result.indices) {
            if (isProtected[i]) {
                output[outputIndex++] = result[i]
            } else {
                if (keepNonProtected[nonProtectedPointer]) output[outputIndex++] = result[i]
                nonProtectedPointer++
            }
        }
        return output
    }

    /** [result]を先頭・末尾を保ったまま均等な間隔で間引き、高々[limit]件に収める。 */
    private fun decimateEvenly(result: IntArray, limit: Int): IntArray {
        val k = result.size
        val denom = limit - 1
        val kept = LinkedHashSet<Int>(limit)
        for (j in 0 until limit) {
            val idx = ((j.toLong() * (k - 1)) / denom).toInt()
            kept.add(result[idx])
        }
        return kept.toIntArray()
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

    /**
     * `maxPointCount`を満たすためのepsilon倍化の最大反復回数（無限ループ防止）。
     * 8回でepsilonは256倍になり、それ以上倍化しても現実的な軌跡データでは点数削減に
     * ほぼ寄与しない（それでも上限を超える分は[decimateToLimit]のハードキャップで吸収する）。
     */
    private const val MAX_EPSILON_DOUBLINGS = 8

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
