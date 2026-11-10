package com.tonbiattack.fastestpictureviewer.ui.grid

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** フォルダ別スクロール位置のアンカー解決テスト */
class ScrollPositionResolverTest {

    private fun item(id: String) = ImageItem(
        id = id,
        mediaId = id.hashCode().toLong(),
        volumeName = "v",
        uri = id,
        displayName = "$id.jpg",
        sortName = "$id.jpg",
        relativePath = "Pictures/A/",
        folderKey = "v:Pictures/A/",
        folderName = "A",
        dateModifiedSec = 1L,
        size = 1L,
        mimeType = "image/jpeg",
        width = null,
        height = null
    )

    @Test
    fun `アンカーが見つかればその位置を返す`() {
        val images = listOf(item("a"), item("b"), item("c"))
        assertEquals(1, ScrollPositionResolver.resolve(images, "b", fallbackIndex = 0))
    }

    @Test
    fun `アンカーが削除されていたら保存時のインデックスへフォールバックする`() {
        val images = listOf(item("a"), item("b"), item("c"))
        assertEquals(2, ScrollPositionResolver.resolve(images, "gone", fallbackIndex = 2))
    }

    @Test
    fun `フォールバック位置は範囲内に丸める`() {
        val images = listOf(item("a"), item("b"))
        assertEquals(1, ScrollPositionResolver.resolve(images, "gone", fallbackIndex = 99))
        assertEquals(0, ScrollPositionResolver.resolve(images, "gone", fallbackIndex = -5))
    }

    @Test
    fun `画像が0件でもクラッシュしない`() {
        assertEquals(0, ScrollPositionResolver.resolve(emptyList(), "a", 10))
    }
}
