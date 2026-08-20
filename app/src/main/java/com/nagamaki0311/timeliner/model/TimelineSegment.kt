package com.nagamaki0311.timeliner.model

/** タイムラインセグメントの種別。 */
enum class TimelineSegmentType {
    /** 滞在（Googleの"visit"/"placeVisit"）。 */
    VISIT,

    /** 移動（Googleの"activity"/"activitySegment"）。 */
    ACTIVITY,

    /** visit/activityいずれの情報も持たず、ルート点（timelinePath等）のみのセグメント。 */
    PATH_ONLY
}

/**
 * タイムラインセグメントのメタ情報。実際の測位点列は[RawTrack]側にまとめて保持する
 * （セグメントとRawTrackの点列は、点の時刻がセグメントの開始〜終了時刻の範囲に入ることで対応付く）。
 *
 * @param latitude VISITセグメントの代表地点（滞在場所）の緯度。ACTIVITY/PATH_ONLYではnull。
 * @param longitude VISITセグメントの代表地点（滞在場所）の経度。ACTIVITY/PATH_ONLYではnull。
 * @param activityType ACTIVITYセグメントの移動手段（例: "IN_PASSENGER_VEHICLE"、"WALKING"）。
 * @param distanceMeters ACTIVITYセグメントの移動距離（メートル）。
 */
data class TimelineSegment(
    val type: TimelineSegmentType,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val activityType: String? = null,
    val distanceMeters: Double? = null
)
