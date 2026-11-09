package com.tonbiattack.fastestpictureviewer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import java.util.Locale

/**
 * MediaStore を走査して画像メタ情報を取得する。
 *
 * - メインスレッドでは絶対に呼ばない (Repository が IO ディスパッチャで呼び出す)
 * - ボリューム別にクエリすることで SD カード上の画像も列挙する
 * - 完全走査 (modifiedAfterSec = null) と差分走査 (DATE_MODIFIED が引数以降のみ) をサポートする
 */
class MediaStoreScanner(private val context: Context) {

    /** 画像を列挙対象とするストレージのボリューム名一覧 */
    fun volumes(): List<String> =
        MediaStore.getExternalVolumeNames(context).toList()

    /**
     * 指定ボリュームの画像メタ情報を取得する。
     *
     * @param modifiedAfterSec 非 null の場合はこのエポック秒以降に更新された画像に限定する (差分走査)
     * @throws IllegalStateException / SecurityException / IllegalArgumentException は呼び出し元で扱う
     */
    fun scan(volumeName: String, modifiedAfterSec: Long? = null): List<ImageItem> {
        val collection = MediaStore.Images.Media.getContentUri(volumeName)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (modifiedAfterSec != null) {
            selection = "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
            selectionArgs = arrayOf(modifiedAfterSec.toString())
        } else {
            selection = null
            selectionArgs = null
        }

        val result = mutableListOf<ImageItem>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idIdx)
                    val relativePath = cursor.getStringOrNull(pathIdx) ?: ""
                    val itemUri: Uri = ContentUris.withAppendedId(collection, mediaId)
                    result += ImageItem(
                        id = itemUri.toString(),
                        mediaId = mediaId,
                        volumeName = volumeName,
                        uri = itemUri.toString(),
                        displayName = cursor.getStringOrNull(nameIdx) ?: "",
                        sortName = (cursor.getStringOrNull(nameIdx) ?: "").lowercase(Locale.ROOT),
                        relativePath = relativePath,
                        folderKey = FolderKeys.create(volumeName, relativePath),
                        folderName = FolderNames.parse(relativePath),
                        dateModifiedSec = cursor.getLong(dateIdx),
                        size = cursor.getLong(sizeIdx),
                        mimeType = cursor.getStringOrNull(mimeIdx),
                        width = cursor.getIntOrNull(widthIdx),
                        height = cursor.getIntOrNull(heightIdx)
                    )
                }
            }
        return result
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)
}
