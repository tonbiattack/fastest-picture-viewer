package com.tonbiattack.fastestpictureviewer.core.image

import android.content.Context
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.tonbiattack.fastestpictureviewer.data.ImageItem

/**
 * 画像キャッシュのキー生成。
 *
 * content URI は同じファイルでも更新されると中身が変わるため、URI だけをキーにすると
 * 古いサムネイルが表示され続ける。更新日時とサイズをキーに含めて更新を検出する。
 */
object GalleryImageCacheKeys {

    fun forItem(item: ImageItem): String =
        "${item.uri}@${item.dateModifiedSec}@${item.size}"

    /**
     * フォルダ代表サムネイルのキー。代表画像の URI と更新日時・サイズで作るため、
     * 先頭画像が削除・リネームされて別の画像に変わると必ずキーも変わる (Issue #49)。
     */
    fun forFolder(uri: String, modifiedSec: Long, size: Long = 0L): String =
        "$uri@folder@$modifiedSec@$size"
}

/** 一覧・ビューア用の画像リクエスト。サイズを指定すると先読みにも使える */
fun galleryImageRequest(
    context: Context,
    uri: String,
    cacheKey: String,
    widthPx: Int? = null,
    heightPx: Int? = null
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(uri)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)

    if (widthPx != null && heightPx != null) {
        builder.size(widthPx, heightPx)
    }
    return builder.build()
}

/** フォルダ内画像 1 件分のリクエスト */
fun ImageItem.toImageRequest(
    context: Context,
    widthPx: Int? = null,
    heightPx: Int? = null
): ImageRequest = galleryImageRequest(
    context = context,
    uri = uri,
    cacheKey = GalleryImageCacheKeys.forItem(this),
    widthPx = widthPx,
    heightPx = heightPx
)

/**
 * ビューアで表示する 1 枚分のリクエスト (Issue #101)。
 *
 * 先読みと表示で**同じ設定**を使うことが重要。サイズや精度が違うと、先読みで
 * メモリキャッシュに載っていてもサイズ不一致で再デコードになり、スワイプが引っかかる。
 *
 * - サイズを表示領域に固定し、その場でレイアウトから解決させない
 * - 精度は INEXACT。画面に合わせた縮小であり、原寸一致は要求しない
 * - クロスフェードは切る。スワイプ直後は無い画像から出すため、フェードの待ちが
 *   そのまま「もたつき」に見える
 */
fun ImageItem.toViewerImageRequest(
    context: Context,
    widthPx: Int,
    heightPx: Int
): ImageRequest {
    val cacheKey = GalleryImageCacheKeys.forItem(this)
    return ImageRequest.Builder(context)
        .data(uri)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .size(widthPx, heightPx)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .build()
}

/** フォルダ代表サムネイルのリクエスト。代表画像の更新日時とサイズをキャッシュキーに含める */
fun folderThumbnailRequest(
    context: Context,
    uri: String,
    modifiedSec: Long,
    size: Long = 0L
): ImageRequest = galleryImageRequest(
    context = context,
    uri = uri,
    cacheKey = GalleryImageCacheKeys.forFolder(uri, modifiedSec, size)
)
