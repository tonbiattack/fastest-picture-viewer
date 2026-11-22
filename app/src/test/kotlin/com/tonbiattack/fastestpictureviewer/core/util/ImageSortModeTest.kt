package com.tonbiattack.fastestpictureviewer.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画像一覧の並び順のテスト (Issue #130)。
 */
class ImageSortModeTest {

    @Test
    fun `既定は名前順`() {
        // 既存ユーザーの見た目を変えないこと
        assertEquals(ImageSortMode.NAME, ImageSortMode.DEFAULT)
    }

    @Test
    fun `選択肢は3種類`() {
        assertEquals(
            listOf(ImageSortMode.NAME, ImageSortMode.DATE_NEWEST, ImageSortMode.DATE_OLDEST),
            ImageSortMode.ALL
        )
    }

    @Test
    fun `保存された名前から復元できる`() {
        assertEquals(ImageSortMode.NAME, ImageSortMode.fromName("NAME"))
        assertEquals(ImageSortMode.DATE_NEWEST, ImageSortMode.fromName("DATE_NEWEST"))
        assertEquals(ImageSortMode.DATE_OLDEST, ImageSortMode.fromName("DATE_OLDEST"))
    }

    @Test
    fun `壊れた保存値は既定値へ戻す`() {
        // 旧バージョンの値や壊れた値でも起動できなくならないこと
        assertEquals(ImageSortMode.DEFAULT, ImageSortMode.fromName(null))
        assertEquals(ImageSortMode.DEFAULT, ImageSortMode.fromName(""))
        assertEquals(ImageSortMode.DEFAULT, ImageSortMode.fromName("UNKNOWN"))
        assertEquals(ImageSortMode.DEFAULT, ImageSortMode.fromName("date_newest"))
    }

    @Test
    fun `ラベルは日本語`() {
        assertEquals("名前順", ImageSortMode.NAME.label)
        assertEquals("新しい順", ImageSortMode.DATE_NEWEST.label)
        assertEquals("古い順", ImageSortMode.DATE_OLDEST.label)
    }

    @Test
    fun `ラベルが重複しない`() {
        // 選択肢として並べるため、同じ表示名があると選び分けられない
        val labels = ImageSortMode.ALL.map { it.label }

        assertEquals(labels.size, labels.toSet().size)
    }
}
