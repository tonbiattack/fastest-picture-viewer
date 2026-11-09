package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MediaStore 走査結果と Room キャッシュの差分計算テスト */
class PendingSyncPlannerTest {

    private fun item(
        id: String,
        name: String = "$id.jpg",
        modifiedSec: Long = 100L,
        size: Long = 1000L,
        relativePath: String = "Pictures/test/"
    ) = ImageItem(
        id = id,
        mediaId = id.hashCode().let { if (it < 0) -it else it }.toLong(),
        volumeName = "external_primary",
        uri = id,
        displayName = name,
        sortName = name.lowercase(),
        relativePath = relativePath,
        folderKey = "external_primary:$relativePath",
        folderName = "test",
        dateModifiedSec = modifiedSec,
        size = size,
        mimeType = "image/jpeg",
        width = null,
        height = null
    )

    @Test
    fun `新規画像はupsertに入る`() {
        val plan = PendingSyncPlanner.plan(
            scanned = listOf(item("a"), item("b")),
            cached = emptyList()
        )
        assertEquals(2, plan.upserts.size)
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `変更がない画像はupsertから除外される`() {
        val cached = listOf(item("a"), item("b"))
        val scanned = listOf(item("a"), item("b")) // 同一内容
        val plan = PendingSyncPlanner.plan(scanned, cached)
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `更新日時が変わった画像はupsertに入る`() {
        val cached = listOf(item("a", modifiedSec = 100L))
        val scanned = listOf(item("a", modifiedSec = 200L))
        val plan = PendingSyncPlanner.plan(scanned, cached)
        assertEquals(1, plan.upserts.size)
        assertEquals(200L, plan.upserts.first().dateModifiedSec)
    }

    @Test
    fun `走査結果に存在しない既存行は削除候補になる`() {
        val cached = listOf(item("a"), item("b"), item("c"))
        val scanned = listOf(item("a"), item("c"))
        val plan = PendingSyncPlanner.plan(scanned, cached)
        assertEquals(listOf("b"), plan.deletions.map { it.id })
    }

    @Test
    fun `削除判定はボリューム内の比較のみで他ボリュームに影響しない`() {
        // cached はこのボリュームの行だけを渡す想定 (Repository がボリューム単位で呼ぶ)
        val cached = listOf(item("a"))
        val scanned = emptyList<ImageItem>()
        val plan = PendingSyncPlanner.plan(scanned, cached)
        assertEquals(listOf("a"), plan.deletions.map { it.id })
    }
}
