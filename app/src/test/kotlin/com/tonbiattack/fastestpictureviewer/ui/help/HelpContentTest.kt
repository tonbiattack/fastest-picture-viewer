package com.tonbiattack.fastestpictureviewer.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * アプリ内ヘルプの内容のテスト (Issue #127)。
 *
 * 文言そのものではなく、**構造と網羅性**を確認する。
 * 実装済み機能を書き漏らすと、利用者が操作を見つけられなくなるため。
 */
class HelpContentTest {

    @Test
    fun `操作ガイドとFAQの両方がある`() {
        assertTrue("操作ガイドが空", HelpContent.SECTIONS.isNotEmpty())
        assertTrue("FAQ が空", HelpContent.FAQ.isNotEmpty())
    }

    @Test
    fun `すべての節に見出しと本文がある`() {
        HelpContent.allSections.forEach { section ->
            assertTrue("見出しが空: ${section.title}", section.title.isNotBlank())
            assertTrue("本文が空: ${section.title}", section.body.isNotBlank())
        }
    }

    @Test
    fun `見出しが重複しない`() {
        // 折りたたみの開閉状態を見出しで管理しているため、重複すると連動してしまう
        val titles = HelpContent.allSections.map { it.title }

        assertEquals("見出しが重複している: $titles", titles.size, titles.toSet().size)
    }

    @Test
    fun `冒頭の説明がある`() {
        assertTrue(HelpContent.INTRO.isNotBlank())
    }

    @Test
    fun `主要な機能が操作ガイドに含まれる`() {
        // Issue #127 で挙げられた項目を書き漏らしていないこと
        val required = listOf(
            "フォルダ一覧",
            "画像一覧",
            "ビューア",
            "スライドショー",
            "タグ",
            "お気に入り",
            "表示設定",
            "バックアップ"
        )
        val titles = HelpContent.SECTIONS.map { it.title }

        required.forEach { keyword ->
            assertTrue(
                "「$keyword」の説明が無い: $titles",
                titles.any { it.contains(keyword) }
            )
        }
    }

    @Test
    fun `主要なFAQが含まれる`() {
        val required = listOf(
            "見つからない画像",
            "権限",
            "表示されない",
            "SD カード",
            "バックアップ"
        )
        val titles = HelpContent.FAQ.map { it.title }

        required.forEach { keyword ->
            assertTrue(
                "「$keyword」の FAQ が無い: $titles",
                titles.any { it.contains(keyword) }
            )
        }
    }

    @Test
    fun `見つからない画像の通知設定に言及している`() {
        // Issue #126 で追加した設定へ誘導できること
        val faq = HelpContent.FAQ.first { it.title.contains("見つからない画像") }

        assertTrue(
            "表示設定でのオフ方法が書かれていない",
            faq.body.contains("表示設定") && faq.body.contains("オフ")
        )
    }

    @Test
    fun `見つからない画像のバナーをスワイプで消せることに言及している`() {
        // Issue #132 で追加した操作へ誘導できること
        val faq = HelpContent.FAQ.first { it.title.contains("見つからない画像") }

        assertTrue("スワイプの説明が無い", faq.body.contains("スワイプ"))
    }

    @Test
    fun `スライドショーの間隔が実装と一致する`() {
        // SlideshowInterval の選択肢（1/2/3/5/10/15/30 秒、初期値 3 秒）と揃えること
        val section = HelpContent.SECTIONS.first { it.title.contains("スライドショー") }

        assertTrue("選択肢が書かれていない", section.body.contains("1 / 2 / 3 / 5 / 10 / 15 / 30"))
        assertTrue("初期値が書かれていない", section.body.contains("3 秒"))
        assertTrue("ループ再生の説明が無い", section.body.contains("ループ"))
    }

    @Test
    fun `列数の範囲が実装と一致する`() {
        // フォルダ一覧は 1〜4 列、画像一覧は 2〜6 列
        val section = HelpContent.SECTIONS.first { it.title.contains("表示設定") }

        assertTrue("フォルダ一覧の列数が書かれていない", section.body.contains("1〜4"))
        assertTrue("画像一覧の列数が書かれていない", section.body.contains("2〜6"))
    }

    @Test
    fun `画像一覧の並び順に言及している`() {
        // Issue #130 で追加した並び順へ誘導できること
        val section = HelpContent.SECTIONS.first { it.title.contains("画像一覧") }

        assertTrue("名前順が書かれていない", section.body.contains("名前順"))
        assertTrue("新しい順が書かれていない", section.body.contains("新しい順"))
        assertTrue("古い順が書かれていない", section.body.contains("古い順"))
    }

    @Test
    fun `オフラインであることに言及している`() {
        val about = HelpContent.FAQ.first { it.title.contains("アプリについて") }

        assertTrue(about.body.contains("ネットワーク"))
        assertTrue(about.body.contains("広告"))
    }

    @Test
    fun `初期状態で開く節がある`() {
        // 最初から全部閉じていると、何が書いてあるか分からない
        assertTrue(
            "初期状態で開く節が無い",
            HelpContent.SECTIONS.any { it.initiallyExpanded }
        )
    }

    @Test
    fun `FAQは初期状態で閉じている`() {
        // FAQ は必要な人が開く。最初から開くと操作ガイドが押し下げられる
        HelpContent.FAQ.forEach { section ->
            assertFalse("FAQ が開いた状態: ${section.title}", section.initiallyExpanded)
        }
    }
}
