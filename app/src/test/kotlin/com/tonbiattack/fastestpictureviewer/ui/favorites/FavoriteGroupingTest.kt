package com.tonbiattack.fastestpictureviewer.ui.favorites

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** お気に入り画像のフォルダ別グルーピングテスト */
class FavoriteGroupingTest {

    private fun item(id: String, path: String, name: String) = ImageItem(
        id = id,
        mediaId = id.hashCode().toLong(),
        volumeName = "v",
        uri = id,
        displayName = name,
        sortName = name.lowercase(),
        relativePath = path,
        folderKey = "v:$path",
        folderName = "x",
        dateModifiedSec = 1L,
        size = 1L,
        mimeType = "image/jpeg",
        width = null,
        height = null
    )

    @Test
    fun `入力順を維持してフォルダ単位にまとめる`() {
        val images = listOf(
            item("b3", "Pictures/B/", "3_R.jpg"),
            item("a1", "Pictures/A/", "1_R.jpg"),
            item("a2", "Pictures/A/", "2_R.jpg"),
            item("a10", "Pictures/A/", "10_R.jpg")
        )
        val groups = FavoriteGrouping.group(images)

        assertEquals(listOf("v:Pictures/B/", "v:Pictures/A/"), groups.map { it.folderKey })
        assertEquals(1, groups[0].imageCount)
        assertEquals(3, groups[1].imageCount)
        assertEquals(listOf("1_R.jpg", "2_R.jpg", "10_R.jpg"), groups[1].images.map { it.displayName })
    }
}
