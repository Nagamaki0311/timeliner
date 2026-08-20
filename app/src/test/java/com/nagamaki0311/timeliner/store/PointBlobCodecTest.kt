package com.nagamaki0311.timeliner.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointBlobCodecTest {

    @Test
    fun encodeDecode_roundTrip_preservesValues() {
        val latitudes = doubleArrayOf(35.681236, -35.681236, 0.0)
        val longitudes = doubleArrayOf(139.767125, -139.767125, 0.0)
        val timestamps = longArrayOf(1_700_000_000_000L, 0L, Long.MAX_VALUE)

        val encoded = PointBlobCodec.encode(latitudes, longitudes, timestamps)
        val decoded = PointBlobCodec.decode(encoded)

        assertArrayEquals(latitudes, decoded.latitudes, 1e-7)
        assertArrayEquals(longitudes, decoded.longitudes, 1e-7)
        assertArrayEquals(timestamps, decoded.timestampsMillis)
    }

    @Test
    fun encode_producesSixteenBytesPerPoint() {
        val latitudes = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val longitudes = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val timestamps = longArrayOf(1L, 2L, 3L, 4L)

        val encoded = PointBlobCodec.encode(latitudes, longitudes, timestamps)

        assertEquals(latitudes.size * PointBlobCodec.BYTES_PER_POINT, encoded.size)
    }

    @Test
    fun encodeDecode_emptyArrays_roundTripToEmpty() {
        val encoded = PointBlobCodec.encode(DoubleArray(0), DoubleArray(0), LongArray(0))
        val decoded = PointBlobCodec.decode(encoded)

        assertEquals(0, encoded.size)
        assertEquals(0, decoded.latitudes.size)
        assertEquals(0, decoded.longitudes.size)
        assertEquals(0, decoded.timestampsMillis.size)
    }

    @Test
    fun encodeDecode_boundaryCoordinates_roundTrip() {
        // 緯度経度の取りうる最大範囲（|lat|<=90, |lon|<=180）でオーバーフローしないことを確認する。
        val latitudes = doubleArrayOf(90.0, -90.0, 0.0)
        val longitudes = doubleArrayOf(180.0, -180.0, 0.0)
        val timestamps = longArrayOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)

        val decoded = PointBlobCodec.decode(PointBlobCodec.encode(latitudes, longitudes, timestamps))

        assertArrayEquals(latitudes, decoded.latitudes, 1e-7)
        assertArrayEquals(longitudes, decoded.longitudes, 1e-7)
        assertArrayEquals(timestamps, decoded.timestampsMillis)
    }

    @Test
    fun decode_invalidByteLength_throws() {
        var threw = false
        try {
            PointBlobCodec.decode(ByteArray(15))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("BLOBサイズが16の倍数でない場合はIllegalArgumentExceptionを送出すること", threw)
    }

    @Test
    fun encode_mismatchedArraySizes_throws() {
        var threw = false
        try {
            PointBlobCodec.encode(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0), longArrayOf(1L, 2L))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("配列長が一致しない場合はIllegalArgumentExceptionを送出すること", threw)
    }
}
