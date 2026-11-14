package com.tonbiattack.fastestpictureviewer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 画像の実体を開けるかどうかを判定する (Issue #77)。
 *
 * OPPO/ColorOS ではフォルダごと削除しても MediaStore の行が残ることがある。
 * その行は URI としては存在するが、開くと `FileNotFoundException` になる。
 * ファイルパスの stat は Scoped Storage で許可されないため、URI を開いて判定する。
 *
 * テストでは差し替えられるよう interface にしている。
 */
fun interface ImageReadableProbe {

    /** [uri] を読み取り用に開けるなら true。開けなければ false */
    suspend fun isReadable(uri: String): Boolean
}

/**
 * [ContentResolver] で実際に開いて判定する本番実装。
 *
 * 画像の全体は読まず、ファイルディスクリプタを開くだけなので 1 件あたり数 ms で済む。
 * 例外は握りつぶして false を返す (呼び出し側は「表示しない」だけで動作を続ける)。
 */
class ContentResolverReadableProbe(private val context: Context) : ImageReadableProbe {

    override suspend fun isReadable(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        return try {
            // AssetFileDescriptor は fd を開くだけ。decode や全読み込みはしない
            context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { true } ?: false
        } catch (e: SecurityException) {
            // 権限がないだけの場合は「壊れている」と断定しない (キャッシュを守る)
            Log.w(TAG, "permission denied while probing $uri", e)
            true
        } catch (e: Exception) {
            // FileNotFoundException (ENOENT)・IsDirectory などは実体なしとみなす
            false
        }
    }

    private companion object {
        const val TAG = "FPV-Perf"
    }
}

/** [ContentResolver] を直接使う小さなヘルパー (Application からの組み立て用) */
fun ContentResolver.asReadableProbe(context: Context): ImageReadableProbe =
    ContentResolverReadableProbe(context)
