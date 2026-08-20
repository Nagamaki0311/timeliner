package com.nagamaki0311.timeliner.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.nagamaki0311.timeliner.data.parser.TimelineJsonParser
import com.nagamaki0311.timeliner.model.RawTrack
import java.io.IOException
import java.io.InputStream

/**
 * SAF（Storage Access Framework）経由で選択された[Uri]から[InputStream]を開き、
 * 単体の`.json`ファイルとzipファイルを判別して[TimelineJsonParser.parseJson]/[TimelineJsonParser.parseZip]
 * のどちらを呼ぶか振り分ける入口（docs/tasks.md T-005）。
 *
 * zip判定はファイル名の拡張子だけでなく、内容の先頭バイト（zipのマジックナンバー`PK`、0x50 0x4B）でも行う。
 * SAFの`ContentResolver`が返すMIMEタイプはプロバイダによって`application/octet-stream`等になりうるため、
 * 拡張子・MIMEタイプに頼らず内容で判定できるようにする。
 */
object ImportSource {

    private const val ZIP_MAGIC_BYTE_0 = 0x50.toByte() // 'P'
    private const val ZIP_MAGIC_BYTE_1 = 0x4B.toByte() // 'K'

    /** [uri]の内容を判別してパースし、[RawTrack]へ正規化する。 */
    fun readRawTrack(context: Context, uri: Uri): RawTrack {
        val resolver = context.contentResolver
        val opener: () -> InputStream = { openOrThrow(resolver, uri) }
        return if (isZip(uri, opener)) {
            TimelineJsonParser.parseZip(opener)
        } else {
            opener().use { TimelineJsonParser.parseJson(it) }
        }
    }

    private fun openOrThrow(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("ファイルを開けませんでした: $uri")

    private fun isZip(uri: Uri, opener: () -> InputStream): Boolean {
        if (hasZipExtension(uri)) return true
        return opener().use { input ->
            val header = ByteArray(2)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read == header.size && header[0] == ZIP_MAGIC_BYTE_0 && header[1] == ZIP_MAGIC_BYTE_1
        }
    }

    /** 純Kotlin部分のみ切り出し、JVM単体テストで検証可能にする。 */
    internal fun hasZipExtension(uri: Uri): Boolean =
        (uri.lastPathSegment ?: uri.toString()).substringAfterLast('/').endsWith(".zip", ignoreCase = true)
}
