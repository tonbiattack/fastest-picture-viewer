package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移動した写真へのお気に入り引き継ぎのテスト (Issue #94)。
 *
 * MediaStore の ID はファイル移動で変わる。お気に入りは ID を主キーに
 * しているため、SD へ移すと紐付けが切れる。
 *
 * 取り違えて別の写真にお気に入りが付く方が、お気に入りが消えるより悪い。
 * 候補が 1 件だけのときに限って移行することを固定する。
 */
class FavoriteMigrationTest {

    private fun item(id: String, name: String = "a.jpg"): ImageItem = ImageItem(
        id = id,
        mediaId = id.hashCode().toLong(),
        volumeName = "v",
        uri = id,
        displayName = name,
        sortName = name.lowercase(),
        relativePath = "P/",
        folderKey = "v:P/",
        folderName = "P",
        dateModifiedSec = 100L,
        size = 1000L,
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        naturalSortKey = NaturalSortKey.create(name)
    )

    @Test
    fun `候補が1件なら移動先へ引き継ぐ`() {
        val to = item("content://media/new/1")
        val decision = FavoriteMigration.decide(listOf(to), alreadyFavorite = false)

        assertTrue(decision is FavoriteMigration.Decision.Migrate)
        assertEquals(to, (decision as FavoriteMigration.Decision.Migrate).to)
    }

    @Test
    fun `候補が複数なら移行しない`() {
        // 連番コピーなどで同じ値の写真が複数ある場合、取り違える危険がある
        val decision = FavoriteMigration.decide(
            listOf(item("content://media/new/1"), item("content://media/new/2")),
            alreadyFavorite = false
        )

        assertEquals(
            FavoriteMigration.Decision.Keep(FavoriteMigration.Reason.AMBIGUOUS),
            decision
        )
    }

    @Test
    fun `候補が無ければ移行しない`() {
        val decision = FavoriteMigration.decide(emptyList(), alreadyFavorite = false)

        assertEquals(
            FavoriteMigration.Decision.Keep(FavoriteMigration.Reason.NO_CANDIDATE),
            decision
        )
    }

    @Test
    fun `移動先が既にお気に入りなら移行しない`() {
        // 重複登録しても情報が増えない
        val decision = FavoriteMigration.decide(
            listOf(item("content://media/new/1")),
            alreadyFavorite = true
        )

        assertEquals(
            FavoriteMigration.Decision.Keep(FavoriteMigration.Reason.ALREADY_FAVORITE),
            decision
        )
    }
}
