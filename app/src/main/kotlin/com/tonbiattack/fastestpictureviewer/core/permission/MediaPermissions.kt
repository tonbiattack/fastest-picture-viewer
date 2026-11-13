package com.tonbiattack.fastestpictureviewer.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 画像ストレージへのアクセスレベル。
 * 削除判定付きの完全同期ができるかどうかを区別するため [Denied]/[Partial]/[Full] の3段階にする。
 */
enum class MediaAccessLevel {
    /** 権限なし */
    Denied,

    /** 部分アクセス (Android 14 以降のユーザー選択)。キャッシュ保護のため削除同期はしない */
    Partial,

    /** 完全アクセス。全ボリュームを走査し、削除済みファイルの反映も可能 */
    Full
}

/**
 * Android バージョン別の画像アクセス権限を集約する。
 * - Android 13 以降: READ_MEDIA_IMAGES
 * - Android 14 以降: READ_MEDIA_VISUAL_USER_SELECTED (部分アクセス) も検出
 * - Android 12 以前: READ_EXTERNAL_STORAGE
 */
object MediaPermissions {

    /** 要求すべき権限の一覧 */
    fun requestPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    /** 現在のアクセスレベルを判定する */
    fun accessLevel(context: Context): MediaAccessLevel {
        // READ_MEDIA_IMAGES は API 33 以降の権限。古い OS で参照しないよう分岐する
        val modernGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 以降は部分アクセス (READ_MEDIA_VISUAL_USER_SELECTED) もあり得る
            val partial = isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return when {
                modernGranted -> MediaAccessLevel.Full
                partial -> MediaAccessLevel.Partial
                else -> MediaAccessLevel.Denied
            }
        }
        val legacy = isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        return when {
            // Android 10〜12 は READ_EXTERNAL_STORAGE、13 以降は READ_MEDIA_IMAGES
            modernGranted || legacy -> MediaAccessLevel.Full
            else -> MediaAccessLevel.Denied
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
