package com.tonbiattack.fastestpictureviewer.core.image

import android.content.Context
import coil.request.ImageRequest
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

    fun forFolder(uri: String, latestModifiedSec: Long): String =
        "$uri@folder@$latestModifiedSec"
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

/** フォルダ代表サムネイルのリクエスト */
fun folderThumbnailRequest(
    context: Context,
    uri: String,
    latestModifiedSec: Long
): ImageRequest = galleryImageRequest(
    context = context,
    uri = uri,
    cacheKey = GalleryImageCacheKeys.forFolder(uri, latestModifiedSec)
)
