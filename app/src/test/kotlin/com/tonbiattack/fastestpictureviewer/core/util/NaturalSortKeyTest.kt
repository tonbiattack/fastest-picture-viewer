package com.tonbiattack.fastestpictureviewer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自然順ソートキーの生成・順序テスト */
class NaturalSortKeyTest {

    private fun key(name: String) = NaturalSortKey.create(name)

    @Test
    fun `数字を含む名前が数値順に並ぶ`() {
        val names = listOf("10_R.jpg", "7_R.jpg", "1_R.jpg", "100_R.jpg", "8_R.jpg", "2_R.jpg")
        val sorted = names.sortedWith(compareBy { key(it) })
        assertEquals(
            listOf("1_R.jpg", "2_R.jpg", "7_R.jpg", "8_R.jpg", "10_R.jpg", "100_R.jpg"),
            sorted
        )
    }

    @Test
    fun `途中の数字も数値として比較する`() {
        assertTrue(key("page2_3.jpg") < key("page2_10.jpg"))
        assertTrue(key("page2_3.jpg") < key("page2_30.jpg"))
    }

    @Test
    fun `先頭ゼロは数値的に等しいキーになる`() {
        assertEquals(key("2_R.jpg"), key("02_R.jpg"))
        assertEquals(key("0.jpg"), key("000.jpg"))
    }

    @Test
    fun `英字の大小文字は同じキーになる`() {
        assertEquals(key("IMG_10.jpg"), key("img_10.jpg"))
    }

    @Test
    fun `全角数字はNFKCでASCII数字として扱う`() {
        assertEquals(key("10_R.jpg"), key("１０_R.jpg"))
    }

    @Test
    fun `日本語名でも安定して並ぶ`() {
        val names = listOf("あ10.jpg", "あ2.jpg", "あ1.jpg")
        assertEquals(
            listOf("あ1.jpg", "あ2.jpg", "あ10.jpg"),
            names.sortedWith(compareBy { key(it) })
        )
    }

    @Test
    fun `非常に長い数字列でもオーバーフローしない`() {
        val shorter = "9".repeat(99) + ".jpg"
        val longer = "1" + "0".repeat(99) + ".jpg"
        assertTrue(key(shorter) < key(longer))
    }

    @Test
    fun `拡張子や数字前後の文字列も比較できる`() {
        assertTrue(key("a1.jpg") < key("a2.jpg"))
        assertTrue(key("a1.jpg") < key("a1.png"))
        assertTrue(key("2_R.jpg") < key("page2_3.jpg"))
    }
}
