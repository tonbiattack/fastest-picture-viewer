package com.tonbiattack.fastestpictureviewer.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/** ピンチ操作による一時的な列数計算のテスト (Issue #45) */
class PinchColumnMathTest {

    @Test
    fun `変化なしなら基準の列数`() {
        assertEquals(3, PinchColumnMath.targetColumns(3, 1f))
    }

    @Test
    fun `ピンチインで列数が増える`() {
        // 1/0.8 = 1.25 → 段階 1.25-1=0.25 / 0.18 ≒ 1.4 → +1
        assertEquals(4, PinchColumnMath.targetColumns(3, 0.8f))
        // 大きくピンチインすると増える
        assertEquals(6, PinchColumnMath.targetColumns(3, 0.4f))
    }

    @Test
    fun `ピンチアウトで列数が減る`() {
        assertEquals(2, PinchColumnMath.targetColumns(3, 1.5f))
        assertEquals(2, PinchColumnMath.targetColumns(2, 2f))
    }

    @Test
    fun `上限と下限を超えない`() {
        assertEquals(PinchColumnMath.MAX_COLUMN_COUNT, PinchColumnMath.targetColumns(6, 0.1f))
        assertEquals(PinchColumnMath.MIN_COLUMN_COUNT, PinchColumnMath.targetColumns(2, 5f))
    }

    @Test
    fun `不正な拡大率は基準値にフォールバックする`() {
        assertEquals(4, PinchColumnMath.targetColumns(4, 0f))
        assertEquals(4, PinchColumnMath.targetColumns(4, Float.NaN))
        assertEquals(4, PinchColumnMath.targetColumns(4, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `基準列数が範囲外なら丸める`() {
        assertEquals(PinchColumnMath.MIN_COLUMN_COUNT, PinchColumnMath.targetColumns(0, 1f))
        assertEquals(PinchColumnMath.MAX_COLUMN_COUNT, PinchColumnMath.targetColumns(10, 1f))
    }
}
