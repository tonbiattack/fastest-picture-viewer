package com.tonbiattack.fastestpictureviewer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** タグ名の正規化と重複判定のテスト (Issue #46) */
class TagNamesTest {

    @Test
    fun `前後の空白は除去される`() {
        assertEquals("旅行", TagNames.display("  旅行  "))
    }

    @Test
    fun `連続する空白は1つにまとめる`() {
        assertEquals("家族 旅行", TagNames.display("家族   旅行"))
    }

    @Test
    fun `全角と半角はNFKCで統一される`() {
        assertEquals("ABC", TagNames.display("ＡＢＣ"))
    }

    @Test
    fun `空文字と空白だけはnull`() {
        assertNull(TagNames.display(""))
        assertNull(TagNames.display("   "))
    }

    @Test
    fun `重複判定キーは大文字小文字を区別しない`() {
        assertEquals(TagNames.key("Trip"), TagNames.key("trip"))
        assertEquals(TagNames.key("旅行"), TagNames.key(" 旅行 "))
    }

    @Test
    fun `長すぎる名前は最大長で切り詰める`() {
        val long = "あ".repeat(TagNames.MAX_LENGTH + 10)
        assertEquals(TagNames.MAX_LENGTH, TagNames.display(long)!!.length)
    }

    @Test
    fun `検索は部分一致で大文字小文字を無視する`() {
        assertTrue(TagNames.matches("tri", "Trip"))
        assertTrue(TagNames.matches("旅行", "家族旅行"))
        assertFalse(TagNames.matches("food", "Trip"))
    }

    @Test
    fun `検索語が空なら全件一致にする`() {
        assertTrue(TagNames.matches("", "Trip"))
        assertTrue(TagNames.matches("   ", "旅行"))
        assertFalse(TagNames.matches("", ""))
    }
}
