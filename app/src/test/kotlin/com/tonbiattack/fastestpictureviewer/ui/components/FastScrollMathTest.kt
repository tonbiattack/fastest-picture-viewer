package com.tonbiattack.fastestpictureviewer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** FastScroller の位置計算テスト */
class FastScrollMathTest {

    @Test
    fun `先頭と末尾へ正しくジャンプする`() {
        assertEquals(0, FastScrollMath.targetIndex(0f, 100))
        assertEquals(99, FastScrollMath.targetIndex(1f, 100))
        assertEquals(50, FastScrollMath.targetIndex(0.5f, 101))
    }

    @Test
    fun `範囲外の位置はクランプされる`() {
        assertEquals(0, FastScrollMath.targetIndex(-1f, 100))
        assertEquals(99, FastScrollMath.targetIndex(2f, 100))
    }

    @Test
    fun `アイテムが無い場合でも安全に0を返す`() {
        assertEquals(0, FastScrollMath.targetIndex(0.5f, 0))
        assertEquals(0, FastScrollMath.targetIndex(0.5f, 1))
    }

    @Test
    fun `先頭アイテムから進捗を計算できる`() {
        assertEquals(0f, FastScrollMath.progress(0, 100), 0.0001f)
        assertEquals(1f, FastScrollMath.progress(99, 100), 0.0001f)
        assertEquals(0f, FastScrollMath.progress(0, 1), 0.0001f)
    }

    @Test
    fun `つまみの高さ比率は下限付きで計算される`() {
        assertEquals(0.1f, FastScrollMath.thumbFraction(10, 100), 0.0001f)
        assertEquals(0.06f, FastScrollMath.thumbFraction(1, 1000), 0.0001f)
        assertEquals(1f, FastScrollMath.thumbFraction(100, 100), 0.0001f)
        assertEquals(1f, FastScrollMath.thumbFraction(0, 0), 0.0001f)
    }
}
