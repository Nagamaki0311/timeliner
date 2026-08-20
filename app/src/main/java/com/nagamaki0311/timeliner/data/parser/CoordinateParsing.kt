package com.nagamaki0311.timeliner.data.parser

/**
 * 座標表現のパース（純Kotlin、Android API非依存）。JVM単体テストで検証する。
 *
 * 対応形式:
 * - 度記号付き10進文字列: `"35.6812°, 139.7671°"`（端末内Timeline Android）
 * - geo:URI: `"geo:35.6812,139.7671"`（端末内Timeline iOS）
 * - E7整数: 10^7倍された緯度・経度整数（Takeout Semantic Location History / Records）
 */

private const val E7_DIVISOR = 1e7
private const val E7_OVERFLOW_THRESHOLD = 1_800_000_000L
private const val UINT32_MODULUS = 4_294_967_296L // 2^32

/**
 * `"35.6812°, 139.7671°"` や `"geo:35.6812,139.7671"` のような文字列座標を
 * (緯度, 経度) のペアへ変換する。`geo:`接頭辞（大文字小文字は区別しない）と`°`記号を除去してパースする。
 */
fun parseCoordinateString(raw: String): Pair<Double, Double> {
    val trimmed = raw.trim()
    val withoutPrefix = if (trimmed.startsWith("geo:", ignoreCase = true)) {
        trimmed.substring(4)
    } else {
        trimmed
    }
    val cleaned = withoutPrefix.replace("°", "")
    val parts = cleaned.split(",")
    require(parts.size == 2) { "座標文字列の形式が不正です: $raw" }
    val latitude = parts[0].trim().toDouble()
    val longitude = parts[1].trim().toDouble()
    return latitude to longitude
}

/**
 * GoogleタイムラインのE7整数（緯度or経度を10^7倍した整数）を10進の度数へ変換する。
 *
 * 既知の不具合対応: 一部のTakeoutエクスポートでは、符号付き32bit整数が符号なし扱いで
 * オーバーフローし、本来負値（南半球/西半球）になるべき値が2^32だけ大きい正の値として
 * 記録されることがある。妥当な緯度経度の絶対値の最大（180 * 1e7 = 1,800,000,000）を
 * 超える場合は2^32を引いて補正する。
 */
fun parseE7(rawValue: Long): Double {
    var value = rawValue
    if (value > E7_OVERFLOW_THRESHOLD) {
        value -= UINT32_MODULUS
    }
    return value / E7_DIVISOR
}
