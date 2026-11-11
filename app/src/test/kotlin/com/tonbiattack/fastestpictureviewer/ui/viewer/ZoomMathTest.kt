package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/** ダブルタップズームの中心・境界計算テスト */
class ZoomMathTest {

    @Test
    fun `画面中央のダブルタップはオフセットなし`() {
        val offset = ZoomMath.doubleTapOffset(
            tap = Offset(540f, 1200f),
            viewportWidth = 1080,
            viewportHeight = 2400,
            targetScale = 2f
        )
        assertEquals(0f, offset.x, 0.01f)
        assertEquals(0f, offset.y, 0.01f)
    }

    @Test
    fun `右側のダブルタップでは画像を左へ移動してタップ位置を保つ`() {
        val offset = ZoomMath.doubleTapOffset(
            tap = Offset(740f, 1200f),
            viewportWidth = 1080,
            viewportHeight = 2400,
            targetScale = 2f
        )
        assertEquals(-200f, offset.x, 0.01f)
        assertEquals(0f, offset.y, 0.01f)
    }

    @Test
    fun `端のダブルタップでも画像外へはみ出さない`() {
        val offset = ZoomMath.doubleTapOffset(
            tap = Offset(0f, 0f),
            viewportWidth = 1080,
            viewportHeight = 2400,
            targetScale = 2f
        )
        assertEquals(540f, offset.x, 0.01f)
        assertEquals(1200f, offset.y, 0.01f)
    }

    @Test
    fun `等倍時はオフセットを0に戻す`() {
        assertEquals(
            Offset.Zero,
            ZoomMath.clampOffset(Offset(100f, 100f), 1080, 2400, scale = 1f)
        )
    }

    @Test
    fun `ピンチ後のオフセットも範囲内に丸める`() {
        val clamped = ZoomMath.clampOffset(
            offset = Offset(9999f, -9999f),
            viewportWidth = 1080,
            viewportHeight = 2400,
            scale = 2f
        )
        assertEquals(540f, clamped.x, 0.01f)
        assertEquals(-1200f, clamped.y, 0.01f)
    }
}
