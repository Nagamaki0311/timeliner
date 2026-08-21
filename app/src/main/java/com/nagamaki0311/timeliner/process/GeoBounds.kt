package com.nagamaki0311.timeliner.process

/**
 * 緯度・経度の点列全体を包含する矩形（bounding box）をmin/max走査のみ（O(n)、オブジェクト生成なし）で計算する
 * （docs/tasks.md T-013）。[com.nagamaki0311.timeliner.ui.TimelineScreen]の`fitBounds`は従来、点数分の
 * `org.maplibre.android.geometry.LatLng`を`LatLngBounds.Builder.include`へ投入していたが、560日規模
 * （数十万点）では不要なオブジェクト生成コストがかかるため、こちらで求めたbboxの対角2点のみを渡す方式に置き換える。
 * `android.*`に一切依存しない純Kotlinのため、JVM単体テストで直接検証できる（D-003/D-004と同様の理由）。
 */
object GeoBounds {

    /** [compute]の結果。矩形の対角（南西・北東）を表す。 */
    data class Bounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double
    )

    /** [latitudes]/[longitudes]（同じ長さ、1点以上）を包含する矩形を返す。 */
    fun compute(latitudes: DoubleArray, longitudes: DoubleArray): Bounds {
        require(latitudes.isNotEmpty()) { "latitudesは1点以上である必要があります" }
        require(latitudes.size == longitudes.size) {
            "緯度・経度の配列長が一致しません: lat=${latitudes.size}, lon=${longitudes.size}"
        }
        var minLat = latitudes[0]
        var maxLat = latitudes[0]
        var minLon = longitudes[0]
        var maxLon = longitudes[0]
        for (i in 1 until latitudes.size) {
            val lat = latitudes[i]
            val lon = longitudes[i]
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }
        return Bounds(minLat, maxLat, minLon, maxLon)
    }
}
