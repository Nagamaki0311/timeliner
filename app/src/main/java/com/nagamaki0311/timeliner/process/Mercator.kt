package com.nagamaki0311.timeliner.process

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 緯度経度とWebメルカトル（EPSG:3857相当）ワールド座標との変換、および2点間の距離計算。
 *
 * [Simplifier]（Douglas-Peucker簡略化）はユークリッド距離を前提とするアルゴリズムのため、
 * 球面座標（緯度経度）のまま扱わず、いったんメートル単位の平面座標へ投影してから適用する
 * （投影方式はWebメルカトルを採用。地図描画自体もWebメルカトルベースであるため一貫性を保てる、
 * というDeveloperの設計判断であり、docs/tasks.mdからの引用ではない）。
 *
 * 一方、実世界の物理量（速度・距離の閾値）と比較する用途では[distanceMeters]ではなく
 * [haversineDistanceMeters]を使うこと（D-005参照）。
 */
object Mercator {

    /** 地球の半径（メートル）。Web メルカトル（EPSG:3857）が採用する球体近似値。 */
    private const val EARTH_RADIUS_METERS = 6378137.0

    /** 大圏距離（Haversine公式）で使う地球の平均半径（メートル）。 */
    private const val EARTH_MEAN_RADIUS_METERS = 6371000.0

    /** 緯度をWebメルカトルのY座標（メートル）へ変換する。 */
    fun latitudeToY(latitude: Double): Double {
        val clampedLatitude = latitude.coerceIn(-85.05112878, 85.05112878)
        val latitudeRadians = Math.toRadians(clampedLatitude)
        return EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + latitudeRadians / 2.0))
    }

    /** 経度をWebメルカトルのX座標（メートル）へ変換する。 */
    fun longitudeToX(longitude: Double): Double = EARTH_RADIUS_METERS * Math.toRadians(longitude)

    /**
     * 2点間（緯度経度）の直線距離をメートルで返す（メルカトル投影平面上のユークリッド距離）。
     *
     * 注意: これは投影空間内の距離であり、実世界の距離とは一致しない。Webメルカトルは
     * `1/cos(緯度)`でスケールが歪むため、赤道以外では実距離より過大に出る（緯度が高いほど乖離が大きい）。
     * [Simplifier]の幾何学的な簡略化（地図上の見た目のズレを測る用途）にのみ使うこと。
     * 速度・距離の閾値など実世界の物理量と比較する場合は[haversineDistanceMeters]を使うこと。
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x1 = longitudeToX(lon1)
        val y1 = latitudeToY(lat1)
        val x2 = longitudeToX(lon2)
        val y2 = latitudeToY(lat2)
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 2点間（緯度経度、度単位）の大圏距離をHaversine公式でメートルとして返す。
     *
     * [distanceMeters]と異なり緯度によるスケール歪みがなく、実世界の距離をそのまま表すため、
     * 速度スパイク除去・停留ジッタ抑制など実世界の物理量と比較する用途はこちらを使うこと。
     */
    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLatRad = Math.toRadians(lat2 - lat1)
        val deltaLonRad = Math.toRadians(lon2 - lon1)
        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return EARTH_MEAN_RADIUS_METERS * c
    }
}
