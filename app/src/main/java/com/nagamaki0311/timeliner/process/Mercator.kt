package com.nagamaki0311.timeliner.process

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * 緯度経度とWebメルカトル（EPSG:3857相当）ワールド座標との変換、および2点間のメートル距離計算。
 *
 * [Simplifier]（Douglas-Peucker簡略化）はユークリッド距離を前提とするアルゴリズムのため、
 * 球面座標（緯度経度）のまま扱わず、いったんメートル単位の平面座標へ投影してから適用する
 * （docs/tasks.md T-004: 「Webメルカトル投影後のメートル空間で実行する」）。
 */
object Mercator {

    /** 地球の半径（メートル）。Web メルカトル（EPSG:3857）が採用する球体近似値。 */
    private const val EARTH_RADIUS_METERS = 6378137.0

    /** 緯度をWebメルカトルのY座標（メートル）へ変換する。 */
    fun latitudeToY(latitude: Double): Double {
        val clampedLatitude = latitude.coerceIn(-85.05112878, 85.05112878)
        val latitudeRadians = Math.toRadians(clampedLatitude)
        return EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + latitudeRadians / 2.0))
    }

    /** 経度をWebメルカトルのX座標（メートル）へ変換する。 */
    fun longitudeToX(longitude: Double): Double = EARTH_RADIUS_METERS * Math.toRadians(longitude)

    /** 2点間（緯度経度）の直線距離をメートルで返す（メルカトル投影平面上のユークリッド距離）。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x1 = longitudeToX(lon1)
        val y1 = latitudeToY(lat1)
        val x2 = longitudeToX(lon2)
        val y2 = latitudeToY(lat2)
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.sqrt(dx * dx + dy * dy)
    }
}
