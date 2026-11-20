package com.tonbiattack.fastestpictureviewer.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ビューアを閉じたときの戻り先ページのテスト。
 *
 * 素早く戻ると「まだ表示していない次の画像」が一覧に返り、
 * 前に見ていた画像が出たように見える不具合を固定する。
 */
class ViewerExitPageTest {

    @Test
    fun `スワイプ途中で戻っても表示中のページを返す`() {
        // 3 枚目を表示中に、4 枚目へスワイプし始めた直後の状態。
        // currentPage は先行して 3 になるが、表示はまだ 2 のまま
        val page = ViewerExitPage.resolve(
            settledPage = 2,
            currentPage = 3,
            pageCount = 10
        )

        assertEquals("まだ表示していないページを返している", 2, page)
    }

    @Test
    fun `確定していればそのページを返す`() {
        val page = ViewerExitPage.resolve(settledPage = 5, currentPage = 5, pageCount = 10)

        assertEquals(5, page)
    }

    @Test
    fun `先頭で戻っても先頭を返す`() {
        val page = ViewerExitPage.resolve(settledPage = 0, currentPage = 1, pageCount = 10)

        assertEquals(0, page)
    }

    @Test
    fun `末尾で戻っても範囲内に収める`() {
        val page = ViewerExitPage.resolve(settledPage = 9, currentPage = 10, pageCount = 10)

        assertEquals(9, page)
    }

    @Test
    fun `画像が無ければ0を返す`() {
        assertEquals(0, ViewerExitPage.resolve(settledPage = 3, currentPage = 3, pageCount = 0))
    }

    @Test
    fun `画像が減って確定ページが範囲外なら現在ページで補う`() {
        // 10 枚表示中に 5 枚目を見ていたが、削除などで 3 枚になった
        val page = ViewerExitPage.resolve(settledPage = 4, currentPage = 2, pageCount = 3)

        assertEquals("範囲外のページを返している", 2, page)
    }

    @Test
    fun `両方とも範囲外なら端に寄せる`() {
        val page = ViewerExitPage.resolve(settledPage = 99, currentPage = 98, pageCount = 3)

        assertEquals(2, page)
    }

    @Test
    fun `負のページは先頭に寄せる`() {
        val page = ViewerExitPage.resolve(settledPage = -1, currentPage = -1, pageCount = 5)

        assertEquals(0, page)
    }

    @Test
    fun `1枚だけなら常に0`() {
        assertEquals(0, ViewerExitPage.resolve(settledPage = 0, currentPage = 0, pageCount = 1))
        assertEquals(0, ViewerExitPage.resolve(settledPage = 5, currentPage = 5, pageCount = 1))
    }
}
