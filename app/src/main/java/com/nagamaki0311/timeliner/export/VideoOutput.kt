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
     *
     * `insert`成功直後（コピー開始前）に[onUriCreated]で発行済みURIを呼び出し元へ通知する
     * （docs/decisions.md D-010決定1）。これはコピー完了後にコルーチンがキャンセルされ戻り値が
     * 呼び出し元に届かない場合でも、呼び出し元が発行済みURIを把握してロールバックできるようにするため。
     * コピー・更新中に失敗した場合は、この関数自身が`resolver.delete`でロールバックしてから例外を再送出する。
     */
    fun saveToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        onUriCreated: (Uri) -> Unit = {}
    ): Uri {
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
        onUriCreated(itemUri)
        try {
            val outputStream = requireNotNull(resolver.openOutputStream(itemUri)) {
                "MediaStore出力ストリームを開けませんでした"
            }
            outputStream.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
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
