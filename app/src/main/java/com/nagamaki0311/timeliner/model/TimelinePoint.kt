package com.nagamaki0311.timeliner.model

/**
 * 緯度経度と時刻を持つ最小の測位点データ。
 *
 * [RawTrack]内部の大量点列は、GC負荷を避けるためDoubleArray/LongArrayで保持する（本クラスの配列は使わない）。
 * 本クラスは、少数点（[RawTrack.point]によるアクセスや、テストでの点の記述）を扱う場面で使う。
 */
data class TimelinePoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long
)
