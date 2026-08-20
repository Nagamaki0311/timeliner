package com.nagamaki0311.timeliner.store

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 点列⇄BLOBの相互変換（docs/tasks.md T-005）。
 *
 * 1点あたり16バイト（緯度E7 Int32 + 経度E7 Int32 + 時刻ミリ秒 Int64）でビッグエンディアンにパックする。
 * `|lat|<=90`・`|lon|<=180`の範囲であれば、1e7倍した整数値はInt32（最大約21.4億）に収まる
 * （180 * 1e7 = 18億で余裕がある）。純Kotlin（Android API非依存）でJVM単体テストから検証できる。
 */
object PointBlobCodec {

    const val BYTES_PER_POINT = 16

    /** [decode]の戻り値。3配列は同じインデックスで対応する点を表し、常に同じ長さを持つ。 */
    data class DecodedPoints(
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val timestampsMillis: LongArray
    )

    fun encode(latitudes: DoubleArray, longitudes: DoubleArray, timestampsMillis: LongArray): ByteArray {
        require(latitudes.size == longitudes.size && latitudes.size == timestampsMillis.size) {
            "緯度・経度・時刻の配列長が一致しません: " +
                "lat=${latitudes.size}, lon=${longitudes.size}, time=${timestampsMillis.size}"
        }
        val buffer = ByteBuffer.allocate(latitudes.size * BYTES_PER_POINT).order(ByteOrder.BIG_ENDIAN)
        for (i in latitudes.indices) {
            buffer.putInt(toE7(latitudes[i]))
            buffer.putInt(toE7(longitudes[i]))
            buffer.putLong(timestampsMillis[i])
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): DecodedPoints {
        require(bytes.size % BYTES_PER_POINT == 0) {
            "BLOBのサイズが${BYTES_PER_POINT}バイトの倍数ではありません: ${bytes.size}"
        }
        val count = bytes.size / BYTES_PER_POINT
        val latitudes = DoubleArray(count)
        val longitudes = DoubleArray(count)
        val timestamps = LongArray(count)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            latitudes[i] = fromE7(buffer.int)
            longitudes[i] = fromE7(buffer.int)
            timestamps[i] = buffer.long
        }
        return DecodedPoints(latitudes, longitudes, timestamps)
    }

    private fun toE7(value: Double): Int = Math.round(value * 1e7).toInt()

    private fun fromE7(value: Int): Double = value / 1e7
}
