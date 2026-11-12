package com.tonbiattack.fastestpictureviewer.core.image

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
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

    @Test
    fun `代表画像が別のファイルに変わるとキャッシュキーが変わる`() {
        // 先頭画像の削除・リネームで URI が変わればキーも変わる (Issue #49)
        assertNotEquals(
            GalleryImageCacheKeys.forFolder("content://media/external/images/media/1", 100L, 10L),
            GalleryImageCacheKeys.forFolder("content://media/external/images/media/2", 100L, 10L)
        )
    }

    @Test
    fun `同じ代表画像ならサイズが同じ限りキーは安定する`() {
        val uri = "content://media/external/images/media/1"
        assertEquals(
            GalleryImageCacheKeys.forFolder(uri, 100L, 10L),
            GalleryImageCacheKeys.forFolder(uri, 100L, 10L)
        )
        // 上書きされるとサイズで検出できる
        assertNotEquals(
            GalleryImageCacheKeys.forFolder(uri, 100L, 10L),
            GalleryImageCacheKeys.forFolder(uri, 100L, 20L)
        )
    }
}
