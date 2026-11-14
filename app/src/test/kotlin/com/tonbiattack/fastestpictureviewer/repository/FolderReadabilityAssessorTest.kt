package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.ImageReadableProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「フォルダごと消えた」と「一部の画像だけ欠けた」の切り分けのテスト (Issue #77)。
 *
 * 実機では、先頭画像だけが削除された健全なフォルダを丸ごと非表示にして
 * しまう不具合が見つかった。代表 1 件だけで判断せず、複数件を確認して
 * 生きているフォルダを巻き込まないことを固定する。
 */
class FolderReadabilityAssessorTest {

    private fun item(name: String, folderKey: String = "v:P/"): ImageItem = ImageItem(
        id = "content://img/$name",
        mediaId = name.hashCode().toLong().let { if (it < 0) -it else it },
        volumeName = "v",
        uri = "content://img/$name",
        displayName = name,
        sortName = name.lowercase(),
        relativePath = "P/",
        folderKey = folderKey,
        folderName = "P",
        dateModifiedSec = 100L,
        size = 100L,
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        naturalSortKey = NaturalSortKey.create(name)
    )

    private fun assessor(readable: Set<String>) =
        FolderReadabilityAssessor(ImageReadableProbe { it in readable })

    @Test
    fun `画像が無ければ健康とみなす`() = runTest {
        val result = assessor(emptySet()).assess(emptyList())
        assertEquals(FolderReadability.Healthy, result)
    }

    @Test
    fun `代表が読めれば全件確認せず健康とみなす`() = runTest {
        val items = listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"))
        // 代表だけ読める状態でも健康 (速度優先で全件は開かない)
        val result = assessor(setOf("content://img/a.jpg")).assess(items)
        assertEquals(FolderReadability.Healthy, result)
    }

    @Test
    fun `先頭だけ欠けていても他の画像が読めれば部分的と判定する`() = runTest {
        // 実機で、先頭だけ削除されたフォルダを誤って全消ししたケースの再現
        val items = listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"), item("d.jpg"))
        val result = assessor(
            setOf(
                "content://img/b.jpg",
                "content://img/c.jpg",
                "content://img/d.jpg"
            )
        ).assess(items)

        assertTrue("部分的欠損として扱うべき", result is FolderReadability.PartiallyUnreadable)
        result as FolderReadability.PartiallyUnreadable
        // 消してよいのは読めなかった先頭 1 枚だけ
        assertEquals(listOf("content://img/a.jpg"), result.items.map { it.id })
    }

    @Test
    fun `全件読めなければフォルダごと消えたと判定する`() = runTest {
        val items = listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"), item("d.jpg"))
        val result = assessor(emptySet()).assess(items)

        assertTrue("フォルダ消滅として扱うべき", result is FolderReadability.FolderGone)
        result as FolderReadability.FolderGone
        assertEquals(4, result.items.size)
    }

    @Test
    fun `1枚だけのフォルダで読めなければ消えたと判定する`() = runTest {
        val result = assessor(emptySet()).assess(listOf(item("a.jpg")))
        assertTrue(result is FolderReadability.FolderGone)
    }

    @Test
    fun `2枚のフォルダで片方読めれば部分的と判定する`() = runTest {
        val items = listOf(item("a.jpg"), item("b.jpg"))
        val result = assessor(setOf("content://img/b.jpg")).assess(items)
        assertTrue(result is FolderReadability.PartiallyUnreadable)
    }

    @Test
    fun `サンプル数は上限を超えない`() = runTest {
        var probes = 0
        val counting = FolderReadabilityAssessor(
            ImageReadableProbe {
                probes++
                false
            }
        )
        val items = (1..100).map { item("img%03d.jpg".format(it)) }

        counting.assess(items)

        // 代表 1 回 + サンプル 3 回までに抑える (大量フォルダでも遅くしない)
        assertTrue("プローブ回数が多すぎる: $probes", probes <= 4)
    }

    @Test
    fun `フォルダ内の並びは自然順で判定する`() = runTest {
        // 自然順で先頭の "2.jpg" が代表になる ("10.jpg" ではない)
        val items = listOf(item("10.jpg"), item("2.jpg"), item("3.jpg"))
        var firstProbed: String? = null
        val probe = ImageReadableProbe { uri ->
            if (firstProbed == null) firstProbed = uri
            true
        }

        FolderReadabilityAssessor(probe).assess(items)

        assertEquals("content://img/2.jpg", firstProbed)
    }
}
