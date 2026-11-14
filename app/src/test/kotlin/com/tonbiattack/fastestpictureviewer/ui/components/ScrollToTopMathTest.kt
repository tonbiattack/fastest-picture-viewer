package com.tonbiattack.fastestpictureviewer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「先頭へ戻る」ボタンの表示判定テスト (Issue #76) */
class ScrollToTopMathTest {

    @Test
    fun `完全に先頭ならボタンを出さない`() {
        assertFalse(ScrollToTopMath.shouldShow(firstVisibleIndex = 0, firstVisibleOffset = 0))
    }

    @Test
    fun `許容値以下のわずかなオフセットは先頭とみなす`() {
        assertFalse(ScrollToTopMath.shouldShow(0, tolerancePx = 8, firstVisibleOffset = 8))
        assertFalse(ScrollToTopMath.shouldShow(0, tolerancePx = 8, firstVisibleOffset = 3))
    }

    @Test
    fun `許容値を超えてスクロールしたら表示する`() {
        assertTrue(ScrollToTopMath.shouldShow(0, tolerancePx = 8, firstVisibleOffset = 9))
        assertTrue(ScrollToTopMath.shouldShow(0, tolerancePx = 8, firstVisibleOffset = 500))
    }

    @Test
    fun `先頭以外のアイテムが見えていたら表示する`() {
        assertTrue(ScrollToTopMath.shouldShow(firstVisibleIndex = 1, firstVisibleOffset = 0))
        assertTrue(ScrollToTopMath.shouldShow(firstVisibleIndex = 120, firstVisibleOffset = 0))
        // インデックスが進んでいればオフセットは問わない
        assertTrue(ScrollToTopMath.shouldShow(firstVisibleIndex = 5, firstVisibleOffset = 0))
    }

    @Test
    fun `負の値でも落ちない`() {
        assertFalse(ScrollToTopMath.shouldShow(firstVisibleIndex = 0, firstVisibleOffset = -10))
    }

    @Test
    fun `タップ後は先頭インデックスを保存する`() {
        assertEquals(0, ScrollToTopMath.savedIndexAfterReturningToTop())
        assertEquals(0, ScrollToTopMath.TOP_INDEX)
    }

    @Test
    fun `進捗からの判定は僅かな進捗では出さない`() {
        assertFalse(ScrollToTopMath.shouldShowForProgress(0f))
        assertFalse(ScrollToTopMath.shouldShowForProgress(0.01f))
        assertTrue(ScrollToTopMath.shouldShowForProgress(0.5f))
        assertTrue(ScrollToTopMath.shouldShowForProgress(1f))
    }
}
