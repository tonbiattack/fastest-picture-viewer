package com.tonbiattack.fastestpictureviewer.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * メディア再スキャン案内の判定テスト (Issue #86)。
 *
 * 「全件走査は成功したのに 1 件も変化が無い」ときだけ案内する。
 * 誤って頻繁に出すと邪魔になるため、出さない条件を重点的に固定する。
 */
class MediaRescanHintTest {

    private fun suggest(
        wasFullScan: Boolean = true,
        added: Int = 0,
        removed: Int = 0,
        failed: Int = 0,
        hasFolders: Boolean = true
    ) = MediaRescanHint.shouldSuggest(
        wasFullScan = wasFullScan,
        addedCount = added,
        removedCount = removed,
        failedVolumeCount = failed,
        hasFolders = hasFolders
    )

    @Test
    fun `全件走査で変化が無ければ案内する`() {
        assertTrue(suggest())
    }

    @Test
    fun `差分走査では案内しない`() {
        // 最新の状態を見たと言えないため
        assertFalse(suggest(wasFullScan = false))
    }

    @Test
    fun `追加があれば案内しない`() {
        assertFalse(suggest(added = 1))
    }

    @Test
    fun `削除があれば案内しない`() {
        assertFalse(suggest(removed = 1))
    }

    @Test
    fun `走査に失敗したボリュームがあれば案内しない`() {
        // 未反映の原因がそちらである可能性が高い
        assertFalse(suggest(failed = 1))
    }

    @Test
    fun `一覧が空なら案内しない`() {
        // 初回スキャン中・写真なしの案内と重なるため
        assertFalse(suggest(hasFolders = false))
    }

    @Test
    fun `追加と削除が両方あれば案内しない`() {
        assertFalse(suggest(added = 3, removed = 2))
    }

    @Test
    fun `案内文にフォルダ名やファイル名を含めない`() {
        // プライバシー配慮: 固有名詞やパスを出さない
        val text = MediaRescanHint.MESSAGE + MediaRescanHint.DETAIL
        assertFalse(text.contains("/"))
        assertFalse(text.contains("storage"))
        assertFalse(text.contains("DCIM"))
    }
}
