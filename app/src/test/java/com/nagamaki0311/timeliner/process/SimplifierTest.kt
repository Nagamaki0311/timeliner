package com.nagamaki0311.timeliner.process

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifierTest {

    // ---- 既知の単純な形状 ----

    @Test
    fun simplify_rectanglePath_keepsOnlyTheFourCorners() {
        // A(0,0.000) -> B(0,0.020) -> C(0.020,0.020) -> D(0.020,0.000)の3辺からなる経路。
        // 各辺上の中間点は厳密に一直線上にあるため、コーナー(A,B,C,D)以外は簡略化で除去されるはず。
        val latitudes = doubleArrayOf(
            0.000, 0.000, 0.000, 0.000, 0.000,
            0.005, 0.010, 0.015, 0.020,
            0.020, 0.020, 0.020, 0.020
        )
        val longitudes = doubleArrayOf(
            0.000, 0.005, 0.010, 0.015, 0.020,
            0.020, 0.020, 0.020, 0.020,
            0.015, 0.010, 0.005, 0.000
        )
        val timestamps = LongArray(latitudes.size) { it * 1000L }

        val result = Simplifier.simplify(latitudes, longitudes, timestamps, epsilonMeters = 5.0)

        assertArrayEquals(intArrayOf(0, 4, 8, 12), result)
    }

    @Test
    fun simplify_straightLineWithOneOutlier_keepsEndpointsAndOutlier() {
        // 緯度0固定、経度を等間隔に並べた直線上に、中央(index5)だけ大きく北へ外れた点を挿入する。
        val size = 11
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size) { it * 0.005 }
        latitudes[5] = 0.001 // 赤道付近で約111m北へ逸脱
        val timestamps = LongArray(size) { it * 1000L }

        // 出力点(index4,6)は再帰的な副産物で約89m逸脱するため、外れ値自体(111m)より小さく
        // かつ副産物より大きいepsilonを指定し、[0, 5, 10]だけに簡略化されることを確認する。
        val result = Simplifier.simplify(latitudes, longitudes, timestamps, epsilonMeters = 100.0)

        assertArrayEquals(intArrayOf(0, 5, 10), result)
    }

    // ---- 時間ガード ----

    @Test
    fun simplify_timeGuard_keepsAllPointsWhenGapsExceedThreshold() {
        // 一直線上（epsilonを大きくすれば通常は両端だけに簡略化される）だが、
        // 各点の間隔が時間ガード閾値(既定5分)を超えるため、全点が残るはず。
        val size = 6
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size) { it * 0.01 }
        val timestamps = LongArray(size) { it * 600_000L } // 10分間隔

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 1000.0,
            timeGuardMillis = 5L * 60 * 1000
        )

        assertEquals(size, result.size)
    }

    @Test
    fun simplify_withoutTimeGuardConcern_collapsesStraightLineToEndpoints() {
        // 時間ガード閾値内(1分間隔)の直線は、大きなepsilonで両端だけに簡略化されるべき。
        val size = 6
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size) { it * 0.01 }
        val timestamps = LongArray(size) { it * 60_000L }

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 1000.0,
            timeGuardMillis = 5L * 60 * 1000
        )

        assertArrayEquals(intArrayOf(0, size - 1), result)
    }

    // ---- 点数上限 ----

    @Test
    fun simplify_maxPointCount_doublesEpsilonUntilUnderLimit() {
        val size = 1000
        val random = Random(7)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.0005
            lon += (random.nextDouble() - 0.5) * 0.0005
            latitudes[i] = lat
            longitudes[i] = lon
            timestamps[i] = i * 1000L
        }

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 0.001,
            maxPointCount = 100
        )

        assertTrue(result.size <= 100)
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    // ---- 明示スタックでの大規模データ ----

    @Test
    fun simplify_largeSyntheticTrack_completesWithoutStackOverflow() {
        // 明示スタック実装のため、再帰実装のような呼び出しスタック深度の制約を受けない
        // （数万〜数十万点規模でも`StackOverflowError`が起きないことの実測確認）。
        val size = 100_000
        val random = Random(42)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.00005
            lon += (random.nextDouble() - 0.5) * 0.00005
            latitudes[i] = lat
            longitudes[i] = lon
            timestamps[i] = i * 1000L
        }

        val result = Simplifier.simplify(latitudes, longitudes, timestamps, epsilonMeters = 5.0)

        assertTrue(result.isNotEmpty())
        assertTrue(result.size < size)
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    // ---- 大規模データでの計算量退化・上限キャップ（T-012） ----

    @Test
    fun simplify_halfPointsProtectedByTimeGuard_completesWithinOneSecond() {
        // 全点の50%が時間ガード保護点（1点おきに5分超のギャップ）という、保護点密度が
        // 極端に高い10万点の入力。修正前は保護点間隔が狭いほどO(n^2/s)へ退化し、
        // 実データ相当の間隔ではUIスレッドが数分〜数十分単位で固まっていた（docs/decisions.md D-017参照）。
        val size = 100_000
        val random = Random(11)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        var time = 0L
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.0001
            lon += (random.nextDouble() - 0.5) * 0.0001
            latitudes[i] = lat
            longitudes[i] = lon
            // 偶数インデックスごとに5分超のギャップを挿入し、点の半分を時間ガード保護点にする。
            time += if (i % 2 == 0) 6L * 60 * 1000 else 1000L
            timestamps[i] = time
        }

        val startNanos = System.nanoTime()
        val result = Simplifier.simplify(latitudes, longitudes, timestamps, epsilonMeters = 5.0)
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        assertTrue("1秒以内に完了すべきだが${elapsedMillis}msかかった", elapsedMillis < 1000)
        assertTrue(result.isNotEmpty())
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    @Test
    fun simplify_maxPointCount_resultNeverExceedsLimit() {
        // 保護点なし（時間ガードにかからない間隔）の入力でも、epsilon倍化後の結果が
        // 常にmaxPointCount以下になることを確認する。
        val size = 20_000
        val random = Random(13)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.0005
            lon += (random.nextDouble() - 0.5) * 0.0005
            latitudes[i] = lat
            longitudes[i] = lon
            timestamps[i] = i * 1000L
        }

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 0.001,
            maxPointCount = 3000
        )

        assertTrue(result.size <= 3000)
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    @Test
    fun simplify_protectedPointsExceedMaxPointCount_stillRespectsLimit() {
        // 時間ガード保護点だけでmaxPointCountを超える入力（1点おきに5分超のギャップ、
        // 1万点中約1万点弱が保護点候補）。修正前はepsilon倍化では保護点を一切減らせず、
        // 上限を守れなかった（打ち切りのみで超過したまま返っていた）。修正後は均等間引きで
        // 保護点も対象にして必ず上限以下へ収める。
        val size = 10_000
        val latitudes = DoubleArray(size) { it * 0.00001 }
        val longitudes = DoubleArray(size) { it * 0.00001 }
        val timestamps = LongArray(size) { i -> if (i % 2 == 0) i / 2 * 6L * 60 * 1000 else (i / 2 * 6L * 60 * 1000) + 1000L }

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 5.0,
            maxPointCount = 3000
        )

        assertTrue("保護点だけで上限を超える入力でも${3000}点以下に収まるべき", result.size <= 3000)
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    // ---- 保護点優先の間引き（T-012b、D-018） ----

    @Test
    fun simplify_protectedPointsWithinMaxPointCount_allSurviveDecimation() {
        // 全体3000点・保護点候補約500点（12点おきに5分超のギャップ）・maxPointCount=2000。
        // 修正前は均等間引きで保護点も無差別に間引かれていた（実測560日規模で約14%しか残らない）。
        // 修正後は保護点数(約500) <= maxPointCount(2000)なので、保護点は全て最終出力に残るはず。
        val size = 3000
        val protectedIntervalPoints = 12
        val random = Random(500)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        var time = 0L
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.0005
            lon += (random.nextDouble() - 0.5) * 0.0005
            latitudes[i] = lat
            longitudes[i] = lon
            time += if (i > 0 && i % protectedIntervalPoints == 0) 6L * 60 * 1000 else 1000L
            timestamps[i] = time
        }

        val timeGuardMillis = 5L * 60 * 1000
        val maxPointCount = 2000
        val protectedIndices = computeProtectedIndices(timestamps, timeGuardMillis)
        assertTrue(
            "テスト前提が崩れている: 保護点候補${protectedIndices.size}件がmaxPointCount${maxPointCount}件を超えている",
            protectedIndices.size <= maxPointCount
        )

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 0.001,
            timeGuardMillis = timeGuardMillis,
            maxPointCount = maxPointCount
        )

        assertTrue(result.size <= maxPointCount)
        val resultSet = result.toHashSet()
        val survivedCount = protectedIndices.count { resultSet.contains(it) }
        assertTrue(
            "保護点は${protectedIndices.size}件中${survivedCount}件しか残っていない" +
                "（保護点数<=maxPointCountなら全件残るはず）",
            survivedCount == protectedIndices.size
        )
    }

    @Test
    fun simplify_protectedPointCountExceedsMaxPointCount_stillRespectsLimitWithoutCrashing() {
        // 保護点候補約5000点（4点おきに5分超のギャップ）が、maxPointCount=3000を上回るケース。
        // この場合のみ保護点も間引き対象になるのはD-018で許容された原理的な限界だが、
        // クラッシュせず必ず上限以下に収まることを確認する。
        val size = 10_000
        val protectedIntervalPoints = 4
        val latitudes = DoubleArray(size) { it * 0.00001 }
        val longitudes = DoubleArray(size) { it * 0.00001 }
        val timestamps = LongArray(size)
        var time = 0L
        for (i in 0 until size) {
            time += if (i > 0 && i % protectedIntervalPoints == 0) 6L * 60 * 1000 else 1000L
            timestamps[i] = time
        }

        val timeGuardMillis = 5L * 60 * 1000
        val maxPointCount = 3000
        val protectedIndices = computeProtectedIndices(timestamps, timeGuardMillis)
        assertTrue(
            "テスト前提が崩れている: 保護点候補${protectedIndices.size}件がmaxPointCount${maxPointCount}件以下になっている",
            protectedIndices.size > maxPointCount
        )

        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 5.0,
            timeGuardMillis = timeGuardMillis,
            maxPointCount = maxPointCount
        )

        assertTrue("保護点数が上限を超える入力でも${maxPointCount}点以下に収まるべき", result.size <= maxPointCount)
        assertEquals(0, result.first())
        assertEquals(size - 1, result.last())
    }

    @Test
    fun simplify_largeScaleBenchmark_protectedSurvivalRateImprovesToFullWhenWithinLimit() {
        // D-017/D-018が報告した560日規模ANRデータを模した大規模合成データ。修正前の実装は
        // 保護点候補22,076点中3,058点（約14%）しか最終出力に残らなかった。ここでは保護点候補数を
        // maxPointCount(3000)以下（約2857件）に収まるよう間隔を調整し、修正後は生存率が
        // 100%へ改善することを検証する（保護点候補数がmaxPointCountを超える密度の場合の限界は
        // 別テストで検証済み）。
        val size = 200_000
        val protectedIntervalPoints = 140
        val random = Random(560)
        val latitudes = DoubleArray(size)
        val longitudes = DoubleArray(size)
        val timestamps = LongArray(size)
        var lat = 35.0
        var lon = 139.0
        var time = 0L
        for (i in 0 until size) {
            lat += (random.nextDouble() - 0.5) * 0.0005
            lon += (random.nextDouble() - 0.5) * 0.0005
            latitudes[i] = lat
            longitudes[i] = lon
            time += if (i > 0 && i % protectedIntervalPoints == 0) 6L * 60 * 1000 else 1000L
            timestamps[i] = time
        }

        val timeGuardMillis = 5L * 60 * 1000
        val maxPointCount = 3000
        val protectedIndices = computeProtectedIndices(timestamps, timeGuardMillis)
        assertTrue(
            "テスト前提が崩れている: 保護点候補${protectedIndices.size}件がmaxPointCount${maxPointCount}件を超えている",
            protectedIndices.size <= maxPointCount
        )

        val startNanos = System.nanoTime()
        val result = Simplifier.simplify(
            latitudes,
            longitudes,
            timestamps,
            epsilonMeters = 0.001,
            timeGuardMillis = timeGuardMillis,
            maxPointCount = maxPointCount
        )
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        assertTrue("560日規模でも数秒以内に完了すべきだが${elapsedMillis}msかかった", elapsedMillis < 5000)
        assertTrue(result.size <= maxPointCount)
        val resultSet = result.toHashSet()
        val survivedCount = protectedIndices.count { resultSet.contains(it) }
        val survivalRate = survivedCount.toDouble() / protectedIndices.size
        assertTrue(
            "保護点候補${protectedIndices.size}件中${survivedCount}件（生存率${survivalRate * 100}%）しか" +
                "残っていない（修正前は約14%、修正後は保護点数<=maxPointCountなら100%のはず）",
            survivedCount == protectedIndices.size
        )
    }

    /** [Simplifier]内部の`computeBreakpoints`と同じ判定で、時間ガード保護点の元インデックスを求める。 */
    private fun computeProtectedIndices(timestampsMillis: LongArray, timeGuardMillis: Long): IntArray {
        val size = timestampsMillis.size
        val isProtected = BooleanArray(size)
        isProtected[0] = true
        isProtected[size - 1] = true
        for (i in 1 until size) {
            if (timestampsMillis[i] - timestampsMillis[i - 1] > timeGuardMillis) {
                isProtected[i - 1] = true
                isProtected[i] = true
            }
        }
        var count = 0
        for (v in isProtected) if (v) count++
        val indices = IntArray(count)
        var writeIndex = 0
        for (i in 0 until size) {
            if (isProtected[i]) {
                indices[writeIndex] = i
                writeIndex++
            }
        }
        return indices
    }
}
