package com.nagamaki0311.timeliner.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * timelinerの永続化ストレージ（docs/tasks.md T-005・docs/decisions.md D-002）。
 *
 * Room等は使わず素の[SQLiteOpenHelper]を使い、日単位でクリーニング済み・未簡略化の点列をBLOBとして格納する。
 * - `days`: 1日1行。`points`は[PointBlobCodec]でエンコードした点列BLOB。
 * - `segments`: [com.nagamaki0311.timeliner.model.TimelineSegment]由来のメタ情報。
 */
class TimelineDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_DAYS_TABLE)
        db.execSQL(CREATE_SEGMENTS_TABLE)
        db.execSQL(CREATE_SEGMENTS_DATE_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 現状バージョン1のみのため最小実装（将来のスキーマ変更時にマイグレーションへ差し替える）。
        db.execSQL("DROP TABLE IF EXISTS segments")
        db.execSQL("DROP TABLE IF EXISTS days")
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "timeliner.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_DAYS = "days"
        const val TABLE_SEGMENTS = "segments"

        private const val CREATE_DAYS_TABLE = """
            CREATE TABLE $TABLE_DAYS (
                date TEXT PRIMARY KEY,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                point_count INTEGER NOT NULL,
                distance_meters REAL NOT NULL,
                points BLOB NOT NULL
            )
        """

        private const val CREATE_SEGMENTS_TABLE = """
            CREATE TABLE $TABLE_SEGMENTS (
                id INTEGER PRIMARY KEY,
                date TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                kind TEXT NOT NULL,
                place_name TEXT,
                activity_type TEXT,
                distance_meters REAL
            )
        """

        private const val CREATE_SEGMENTS_DATE_INDEX =
            "CREATE INDEX index_segments_date ON $TABLE_SEGMENTS (date)"
    }
}
