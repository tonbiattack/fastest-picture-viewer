package com.tonbiattack.fastestpictureviewer.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ビューアの先読み対象ページの決定テスト (Issue #101)。
 *
 * 中央付近・端・1 枚だけ・範囲外などで、範囲外を読まないことを確認する。
 */
class ViewerPrefetchTest {

    @Test
    fun `中央では前後2枚ずつを返す`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 5, count = 20)
        assertEquals(setOf(3, 4, 6, 7), targets.toSet())
        assertEquals(4, targets.size)
    }

    @Test
    fun `先頭では前を読まず次だけを返す`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 0, count = 10)
        assertEquals(listOf(1, 2), targets)
    }

    @Test
    fun `末尾では次を読まず前だけを返す`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 9, count = 10)
        assertEquals(listOf(8, 7), targets)
    }

    @Test
    fun `現在ページは含めない`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 4, count = 10)
        assertTrue(4 !in targets)
    }

    @Test
    fun `範囲外のページは返さない`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 1, count = 3)
        assertTrue(targets.all { it in 0..2 })
    }

    @Test
    fun `画像が1枚なら先読みしない`() {
        assertTrue(ViewerPrefetch.indicesToPrefetch(page = 0, count = 1).isEmpty())
        assertTrue(ViewerPrefetch.indicesToPrefetch(page = 0, count = 0).isEmpty())
    }

    @Test
    fun `範囲外のページを渡しても例外にしない`() {
        assertTrue(ViewerPrefetch.indicesToPrefetch(page = 5, count = 3).isEmpty())
        assertTrue(ViewerPrefetch.indicesToPrefetch(page = -1, count = 3).isEmpty())
    }

    @Test
    fun `進む方向では次の画像を先に並べる`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 5, count = 20, direction = 1)
        assertEquals(listOf(6, 7, 4, 3), targets)
    }

    @Test
    fun `戻る方向では前の画像を先に並べる`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 5, count = 20, direction = -1)
        assertEquals(listOf(4, 3, 6, 7), targets)
    }

    @Test
    fun `先読み枚数を0にすると何も返さない`() {
        assertTrue(ViewerPrefetch.indicesToPrefetch(page = 5, count = 20, window = 0).isEmpty())
    }

    @Test
    fun `先読み枚数を広げるとその分だけ返す`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 10, count = 40, window = 3)
        assertEquals(setOf(7, 8, 9, 11, 12, 13), targets.toSet())
    }

    @Test
    fun `重複したページを返さない`() {
        val targets = ViewerPrefetch.indicesToPrefetch(page = 2, count = 5, window = 10)
        assertEquals(targets.distinct(), targets)
        assertEquals(setOf(0, 1, 3, 4), targets.toSet())
    }
}
