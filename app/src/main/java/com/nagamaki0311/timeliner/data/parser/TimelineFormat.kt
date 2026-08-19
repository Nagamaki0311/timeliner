package com.nagamaki0311.timeliner.data.parser

/**
 * 対応する4つのタイムラインJSON形式。判別方法の詳細はdocs/decisions.md D-002・タスクT-003参照。
 * パース結果（[com.nagamaki0311.timeliner.model.RawTrack]）自体は形式を持たない
 * （後続処理は形式を一切意識しない設計のため）。この列挙型はパーサ内部の形式判別・テストでのみ用いる。
 */
enum class TimelineFormat {
    /** 端末内Timeline(Android)。ルートオブジェクトが`semanticSegments`キーを持つ。 */
    DEVICE_TIMELINE_ANDROID,

    /** 端末内Timeline(iOS)。ルートが配列そのもの（ラッパーキーなし）。 */
    DEVICE_TIMELINE_IOS,

    /** Takeout Semantic Location History(旧)。ルートオブジェクトが`timelineObjects`キーを持つ。 */
    TAKEOUT_SEMANTIC_LOCATION_HISTORY,

    /** Takeout Records(生GPS)。ルートオブジェクトが`locations`キーを持つ。 */
    TAKEOUT_RECORDS
}
