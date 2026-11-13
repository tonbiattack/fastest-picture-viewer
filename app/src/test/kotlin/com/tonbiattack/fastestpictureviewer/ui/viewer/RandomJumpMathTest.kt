package com.tonbiattack.fastestpictureviewer.ui.viewer

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ランダムジャンプのジャンプ先決定ロジックのテスト (Issue #58) */
class RandomJumpMathTest {

    /** 常に同じ値を返す固定乱数 (nextInt(bound) のみ使用) */
    private class FixedRandom(private val value: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = value.coerceIn(0, until - 1)
    }

    @Test
    fun `画像が無ければnull`() {
        assertNull(RandomJumpMath.pickTarget(count = 0, currentIndex = 0))
    }

    @Test
    fun `1枚しか無ければその画像を返す`() {
        assertEquals(0, RandomJumpMath.pickTarget(count = 1, currentIndex = 0))
        // 現在位置が範囲外でも 0 を返す
        assertEquals(0, RandomJumpMath.pickTarget(count = 1, currentIndex = 5))
    }

    @Test
    fun `現在の画像は選ばない`() {
        val count = 10
        for (current in 0 until count) {
            repeat(50) { seed ->
                val target = RandomJumpMath.pickTarget(
                    count = count,
                    currentIndex = current,
                    random = Random(seed)
                )
                assertNotEquals("current=$current seed=$seed", current, target)
                assertTrue("範囲外 current=$current seed=$seed target=$target", target!! in 0 until count)
            }
        }
    }

    @Test
    fun `現在より前の候補を選ぶとインデックスはそのまま`() {
        // count=5, current=3, offset=1 → 1 (current より小さいので詰め直しなし)
        assertEquals(1, RandomJumpMath.pickTarget(5, 3, FixedRandom(1)))
        assertEquals(2, RandomJumpMath.pickTarget(5, 3, FixedRandom(2)))
    }

    @Test
    fun `現在以上の候補を選ぶと1つ後ろへずれる`() {
        // count=5, current=1, offset=1 → 1 は current 以上なので 2
        assertEquals(2, RandomJumpMath.pickTarget(5, 1, FixedRandom(1)))
        // offset=3 → 4
        assertEquals(4, RandomJumpMath.pickTarget(5, 1, FixedRandom(3)))
    }

    @Test
    fun `現在位置が範囲外なら全件から選ぶ`() {
        assertEquals(0, RandomJumpMath.pickTarget(count = 5, currentIndex = -1, random = FixedRandom(0)))
        assertEquals(4, RandomJumpMath.pickTarget(count = 5, currentIndex = 99, random = FixedRandom(4)))
    }

    @Test
    fun `全パターンで必ず有効なインデックスを返す`() {
        for (count in 1..20) {
            for (current in -2..count + 2) {
                repeat(20) { seed ->
                    val target = RandomJumpMath.pickTarget(count, current, Random(seed))
                    assertTrue("count=$count current=$current", target != null)
                    assertTrue("count=$count current=$current target=$target", target!! in 0 until count)
                    if (count > 1 && current in 0 until count) {
                        assertNotEquals("count=$count current=$current", current, target)
                    }
                }
            }
        }
    }
}
