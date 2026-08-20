package com.nagamaki0311.timeliner.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 書き出した動画ファイルを`MediaStore.Video.Media`へ登録し、共有・再生用の`content://` URIを提供する
 * （docs/tasks.md T-008）。minSdk 29（Android 10）前提のため、`WRITE_EXTERNAL_STORAGE`権限や
 * FileProviderは不要（`MediaStore.Video.Media.getContentUri`経由のスコープドストレージ書き込みで完結する）。
 */
object VideoOutput {

    private const val RELATIVE_PATH = "Movies/timeliner"
    private const val VIDEO_MIME_TYPE = "video/mp4"

    /**
     * [sourceFile]の内容を`MediaStore.Video.Media`（`RELATIVE_PATH=Movies/timeliner`）へコピーし、
     * 登録先の`content://` URIを返す。コピー完了後、呼び出し元は[sourceFile]（一時ファイル）を削除してよい。
     */
    fun saveToMediaStore(context: Context, sourceFile: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = requireNotNull(resolver.insert(collection, values)) {
            "MediaStoreへの書き込み用URIを取得できませんでした"
        }
        val outputStream = requireNotNull(resolver.openOutputStream(itemUri)) {
            "MediaStore出力ストリームを開けませんでした"
        }
        outputStream.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return itemUri
    }

    /** [videoUri]を他アプリへ共有するためのIntentを組み立てる。 */
    fun createShareIntent(videoUri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = VIDEO_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, videoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** [videoUri]をアプリ（動画プレーヤー等）で開くためのIntentを組み立てる。 */
    fun createViewIntent(videoUri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, VIDEO_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
