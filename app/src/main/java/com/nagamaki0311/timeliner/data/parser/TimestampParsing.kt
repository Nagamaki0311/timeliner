package com.nagamaki0311.timeliner.data.parser

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 時刻文字列のパース（純Kotlin、Android API非依存）。JVM単体テストで検証する。
 *
 * 対応形式:
 * - ISO-8601（オフセット付き）: `"2026-08-19T10:00:00+09:00"` / `"...Z"`
 * - epoch ms文字列: `"1755500400000"`（Takeout旧形式の`timestampMs`等）
 *
 * `java.time`はminSdk 29（API 26以上）で desugaring 無しに利用できるため追加依存は不要。
 */
fun parseTimestampMillis(raw: String): Long {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "時刻文字列が空です" }
    if (trimmed.all { it.isDigit() }) {
        return trimmed.toLong()
    }
    return try {
        OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("不明な時刻形式です: $raw", e)
    }
}
