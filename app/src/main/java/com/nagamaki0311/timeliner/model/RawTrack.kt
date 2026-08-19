package com.nagamaki0311.timeliner.model

/**
 * 4形式（端末内Timeline Android/iOS、Takeout Semantic Location History、Takeout Records）の
 * タイムラインJSONをすべて正規化した共通中間モデル。
 *
 * 大量点でのGC負荷を避けるため、点列はDoubleArray/LongArrayのプリミティブ配列で保持する
 * （オブジェクト配列にはしない）。以降の処理（ノイズ除去・簡略化・描画・再生・書き出し）は、
 * 元のJSON形式を一切意識せずこのモデルだけを扱う設計とする。
 *
 * 3配列は同じインデックスで対応する点の緯度・経度・時刻（エポックミリ秒）を表し、常に同じ長さを持つ。
 */
class RawTrack(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val timestampsMillis: LongArray,
    val segments: List<TimelineSegment>
) {
    init {
        require(latitudes.size == longitudes.size && latitudes.size == timestampsMillis.size) {
            "緯度・経度・時刻の配列長が一致しません: " +
                "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
        }
    }

    val pointCount: Int get() = latitudes.size

    /** 単一点への読みやすいアクセス。大量点を走査するホットパスでは使わないこと（配列を直接読むこと）。 */
    fun point(index: Int): TimelinePoint =
        TimelinePoint(latitudes[index], longitudes[index], timestampsMillis[index])
}
