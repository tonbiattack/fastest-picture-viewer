package com.tonbiattack.fastestpictureviewer.core.image

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画像更新時にキャッシュキーが変わることのテスト */
class GalleryImageCacheKeysTest {

    private fun item(
        id: String = "content://media/external/images/media/1",
        modifiedSec: Long = 100L,
        size: Long = 1000L
    ) = ImageItem(
        id = id,
        mediaId = 1L,
        volumeName = "external_primary",
        uri = id,
        displayName = "a.jpg",
        sortName = "a.jpg",
        relativePath = "Pictures/",
        folderKey = "external_primary:Pictures/",
        folderName = "Pictures",
        dateModifiedSec = modifiedSec,
        size = size,
        mimeType = "image/jpeg",
        width = 100,
        height = 100
    )

    @Test
    fun `更新日時が変わるとキーが変わる`() {
        assertNotEquals(
            GalleryImageCacheKeys.forItem(item(modifiedSec = 100L)),
            GalleryImageCacheKeys.forItem(item(modifiedSec = 200L))
        )
    }

    @Test
    fun `ファイルサイズが変わるとキーが変わる`() {
        assertNotEquals(
            GalleryImageCacheKeys.forItem(item(size = 1000L)),
            GalleryImageCacheKeys.forItem(item(size = 2000L))
        )
    }

    @Test
    fun `同じ画像ならキーは安定する`() {
        assertTrue(
            GalleryImageCacheKeys.forItem(item()) == GalleryImageCacheKeys.forItem(item())
        )
    }

    @Test
    fun `フォルダ代表サムネイルにも更新日時が含まれる`() {
        val uri = "content://media/external/images/media/1"
        assertNotEquals(
            GalleryImageCacheKeys.forFolder(uri, 100L),
            GalleryImageCacheKeys.forFolder(uri, 200L)
        )
    }
}
