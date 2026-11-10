package com.tonbiattack.fastestpictureviewer.ui.grid

import com.tonbiattack.fastestpictureviewer.data.ImageItem

/** 保存済みアンカーから復元する先頭インデックスを決める純粋ロジック */
object ScrollPositionResolver {

    /**
     * [anchorItemId] が見つかればその位置、見つからなければ保存時の [fallbackIndex] を使う。
     * 画像が削除・追加されても極端に飛ばないよう範囲内に丸める。
     */
    fun resolve(
        images: List<ImageItem>,
        anchorItemId: String?,
        fallbackIndex: Int
    ): Int {
        if (images.isEmpty()) return 0
        if (anchorItemId != null) {
            val index = images.indexOfFirst { it.id == anchorItemId }
            if (index >= 0) return index
        }
        return fallbackIndex.coerceIn(0, images.lastIndex)
    }
}
