package com.tonbiattack.fastestpictureviewer.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import java.util.Locale

/**
 * MediaStore を走査して画像メタ情報を取得する。
 *
 * - メインスレッドでは絶対に呼ばない (Repository が IO ディスパッチャで呼び出す)
 * - ボリューム別にクエリすることで SD カード上の画像も列挙する
 * - 完全走査 (modifiedAfterSec = null) と差分走査 (DATE_MODIFIED が引数以降のみ) をサポートする
 * - 大量画像でも OOM しないよう、[scanBatched] で一定件数ごとに処理する
 */
open class MediaStoreScanner(private val context: Context) {
    /** 画像を列挙対象とするストレージのボリューム名一覧 */
    open fun volumes(): List<String> =
        MediaStore.getExternalVolumeNames(context).toList()

    /**
     * 現在マウントされていて読み取り可能なボリューム名の集合 (Issue #80)。
     *
     * 実体を確認して DB から整理する処理は、SD カードが抜けているだけで
     * 「実体が無い」と誤判定してしまう。誤削除は取り返しがつかないため、
     * マウント状態を必ず確認してから実行する。
     */
    open fun mountedVolumes(): Set<String> {        val mounted = MediaStore.getExternalVolumeNames(context)
        return mounted.filterTo(hashSetOf()) { volume ->
            try {
                // 実際にクエリできて 1 行でも返るかで「読める」ことを確認する。
                // 空のボリューム (画像 0 件) も正常なので、例外が出なければ可とする
                context.contentResolver.query(
                    MediaStore.Images.Media.getContentUri(volume),
                    arrayOf(MediaStore.MediaColumns._ID),
                    null,
                    null,
                    null
                )?.use { true } ?: false
            } catch (t: Exception) {
                // 未マウント・権限なし・一時的な不調は「触らない」側に倒す
                false
            }
        }
    }

    /**
     * ボリュームの MediaStore 世代番号を取得する。
     *
     * 世代番号はそのボリュームのメディアが変更されるたびに増えるため、
     * 前回と同じなら再走査を省略できる。API 29 では取得できないため null を返す。
     */
    open fun generation(volumeName: String): Long? {        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getGeneration(context, volumeName)
        } catch (t: Exception) {
            // ボリュームが一時的に見えない場合など。差分走査へフォールバックする
            null
        }
    }

    /**
     * 指定ボリュームの画像メタ情報を全件メモリに載せて返す。
     * 大量画像では [scanBatched] を使うこと。
     */
    suspend fun scan(volumeName: String, modifiedAfterSec: Long? = null): List<ImageItem> {
        val result = ArrayList<ImageItem>()
        scanBatched(volumeName, modifiedAfterSec) { result.addAll(it) }
        return result
    }

    /**
     * 指定ボリュームの画像メタ情報を [batchSize] 件ずつ [onBatch] に渡す。
     * カーソルは 1 つだけ開き、全件リストを保持しないため大量画像でも安全。
     *
     * @param modifiedAfterSec 非 null の場合はこのエポック秒以降に更新された画像に限定する (差分走査)
     * @throws IllegalStateException / SecurityException / IllegalArgumentException は呼び出し元で扱う
     */
    open suspend fun scanBatched(
        volumeName: String,
        modifiedAfterSec: Long? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        onBatch: suspend (List<ImageItem>) -> Unit
    ) {        require(batchSize > 0) { "batchSize must be positive" }

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

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val columns = Columns(cursor)
                val batch = ArrayList<ImageItem>(batchSize)
                while (cursor.moveToNext()) {
                    batch += columns.read(cursor, collection, volumeName)
                    if (batch.size >= batchSize) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    onBatch(batch)
                }
            }
    }

    /** カーソルの列インデックスを一度だけ解決する */
    private class Columns(cursor: Cursor) {
        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)

        fun read(cursor: Cursor, collection: Uri, volumeName: String): ImageItem {
            val mediaId = cursor.getLong(idIdx)
            val relativePath = cursor.getStringOrNull(pathIdx) ?: ""
            val displayName = cursor.getStringOrNull(nameIdx) ?: ""
            val itemUri: Uri = ContentUris.withAppendedId(collection, mediaId)
            return ImageItem(
                id = itemUri.toString(),
                mediaId = mediaId,
                volumeName = volumeName,
                uri = itemUri.toString(),
                displayName = displayName,
                sortName = displayName.lowercase(Locale.ROOT),
                relativePath = relativePath,
                folderKey = FolderKeys.create(volumeName, relativePath),
                folderName = FolderNames.parse(relativePath),
                dateModifiedSec = cursor.getLong(dateIdx),
                size = cursor.getLong(sizeIdx),
                mimeType = cursor.getStringOrNull(mimeIdx),
                width = cursor.getIntOrNull(widthIdx),
                height = cursor.getIntOrNull(heightIdx),
                naturalSortKey = NaturalSortKey.create(displayName)
            )
        }
    }

    companion object {
        /** 1 バッチあたりの件数。350,000 件でもメモリに全件載せない */
        const val DEFAULT_BATCH_SIZE = 500
    }
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)
