package com.tonbiattack.fastestpictureviewer.core.util

import java.text.Normalizer

/**
 * タグ名の正規化ユーティリティ (Issue #46)。
 *
 * 表記ゆれによる重複タグを防ぐため、次の方針で正規化する。
 * - NFKC 正規化 (全角/半角、互換文字を統一)
 * - 連続する空白を 1 つにまとめ、前後の空白を除去
 * - 重複判定キーはロケール非依存の小文字化 (大文字小文字を同一視)
 */
object TagNames {

    /** タグ名の最大文字数。長すぎる入力で UI が崩れないように制限する */
    const val MAX_LENGTH = 30

    /** 表示用に整形したタグ名を返す。空文字なら null */
    fun display(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return null
        return normalized.take(MAX_LENGTH)
    }

    /** 重複判定用キーを返す。空文字なら null */
    fun key(raw: String): String? =
        display(raw)?.lowercase(java.util.Locale.ROOT)

    /**
     * 検索用の部分一致判定。
     * 検索語が空なら「全件一致」として扱う (ダイアログを開いた直後に一覧が出ない不具合の防止)。
     */
    fun matches(query: String, tagName: String): Boolean {
        val target = key(tagName) ?: return false
        if (query.isBlank()) return true
        val key = key(query) ?: return true
        return target.contains(key)
    }

    private val WHITESPACE = Regex("\\s+")
}
