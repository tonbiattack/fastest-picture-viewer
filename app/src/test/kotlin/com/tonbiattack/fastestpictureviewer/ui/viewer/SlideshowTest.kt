package com.tonbiattack.fastestpictureviewer.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * スライドショーの表示間隔のテスト (Issue #124)。
 */
class SlideshowIntervalTest {

    @Test
    fun `既定は3秒`() {
        assertEquals(3, SlideshowInterval.DEFAULT.seconds)
        assertEquals(SlideshowInterval.SEC_3, SlideshowInterval.DEFAULT)
    }

    @Test
    fun `選択肢は要件の7種類`() {
        val seconds = SlideshowInterval.ALL.map { it.seconds }

        assertEquals(listOf(1, 2, 3, 5, 10, 15, 30), seconds)
    }

    @Test
    fun `秒数から復元できる`() {
        assertEquals(SlideshowInterval.SEC_10, SlideshowInterval.fromSeconds(10))
        assertEquals(SlideshowInterval.SEC_30, SlideshowInterval.fromSeconds(30))
    }

    @Test
    fun `選択肢に無い秒数は既定値へ戻す`() {
        // 保存値が壊れていても起動できなくならないこと
        assertEquals(SlideshowInterval.DEFAULT, SlideshowInterval.fromSeconds(0))
        assertEquals(SlideshowInterval.DEFAULT, SlideshowInterval.fromSeconds(-5))
        assertEquals(SlideshowInterval.DEFAULT, SlideshowInterval.fromSeconds(7))
        assertEquals(SlideshowInterval.DEFAULT, SlideshowInterval.fromSeconds(999))
    }

    @Test
    fun `ミリ秒は秒数の1000倍`() {
        assertEquals(1_000L, SlideshowInterval.SEC_1.millis)
        assertEquals(30_000L, SlideshowInterval.SEC_30.millis)
    }

    @Test
    fun `ラベルは秒数を含む`() {
        assertEquals("3秒", SlideshowInterval.SEC_3.label)
        assertEquals("30秒", SlideshowInterval.SEC_30.label)
    }
}

/**
 * スライドショーのページ移動のテスト (Issue #124)。
 */
class SlideshowPagingTest {

    @Test
    fun `次へ進むと1つ後ろになる`() {
        assertEquals(1, SlideshowPaging.next(current = 0, pageCount = 5))
        assertEquals(3, SlideshowPaging.next(current = 2, pageCount = 5))
    }

    @Test
    fun `最終画像の次は先頭へ戻る`() {
        assertEquals("ループ再生になっていない", 0, SlideshowPaging.next(current = 4, pageCount = 5))
    }

    @Test
    fun `先頭の前は末尾へ戻る`() {
        assertEquals(4, SlideshowPaging.previous(current = 0, pageCount = 5))
    }

    @Test
    fun `前へ戻ると1つ前になる`() {
        assertEquals(2, SlideshowPaging.previous(current = 3, pageCount = 5))
    }

    @Test
    fun `画像0枚なら進めない`() {
        assertNull(SlideshowPaging.next(current = 0, pageCount = 0))
        assertNull(SlideshowPaging.previous(current = 0, pageCount = 0))
    }

    @Test
    fun `画像1枚なら常に0`() {
        assertEquals(0, SlideshowPaging.next(current = 0, pageCount = 1))
        assertEquals(0, SlideshowPaging.previous(current = 0, pageCount = 1))
    }

    @Test
    fun `範囲外の現在位置でも安全に丸める`() {
        // 画像が減った直後など、現在位置が範囲外になることがある
        val next = SlideshowPaging.next(current = 99, pageCount = 5)

        assertTrue("範囲外のページを返している", next!! in 0..4)
    }

    @Test
    fun `再生中かつ2枚以上なら自動で進む`() {
        assertTrue(
            SlideshowPaging.shouldAutoAdvance(SlideshowState.PLAYING, pageCount = 2)
        )
    }

    @Test
    fun `一時停止中は自動で進まない`() {
        assertFalse(
            SlideshowPaging.shouldAutoAdvance(SlideshowState.PAUSED, pageCount = 10)
        )
    }

    @Test
    fun `停止中は自動で進まない`() {
        assertFalse(
            SlideshowPaging.shouldAutoAdvance(SlideshowState.STOPPED, pageCount = 10)
        )
    }

    @Test
    fun `画像1枚以下なら再生中でも自動で進まない`() {
        // 切り替えても意味が無いのでタイマーを回さない
        assertFalse(
            SlideshowPaging.shouldAutoAdvance(SlideshowState.PLAYING, pageCount = 1)
        )
        assertFalse(
            SlideshowPaging.shouldAutoAdvance(SlideshowState.PLAYING, pageCount = 0)
        )
    }
}

/**
 * スライドショーの再生状態のテスト (Issue #124)。
 */
class SlideshowStateTest {

    @Test
    fun `再生中だけが再生状態`() {
        assertTrue(SlideshowState.PLAYING.isPlaying)
        assertFalse(SlideshowState.PAUSED.isPlaying)
        assertFalse(SlideshowState.STOPPED.isPlaying)
    }
}
