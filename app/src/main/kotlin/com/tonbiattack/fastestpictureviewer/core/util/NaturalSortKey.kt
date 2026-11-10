package com.tonbiattack.fastestpictureviewer.core.util

import com.tonbiattack.fastestpictureviewer.data.ImageItem
import java.text.Normalizer
import java.util.Locale

/**
 * ファイル名の自然順ソートキー。
 *
 * 連続する数字を数値として比較し、それ以外の文字はコードポイント順で比較する。
 * キーは SQLite の TEXT (BINARY) 比較でそのまま自然順になるよう、次の形式で符号化する。
 *
 * - 数字列: '/' + 有効桁数(ゼロ埋め6桁) + 先頭ゼロを除いた数字列
 * - それ以外: NFKC 正規化・小文字化した文字をそのまま連結
 *
 * '/' はファイル名に含められないため、区切りとして安全に使える。
 * 数字列は '/' (0x2F) から始まるので、同じ位置の通常文字との比較でも
 * 「数字列は数字として比較する」という一貫した順序になる。
 *
 * 例: "7_R.jpg" < "8_R.jpg" < "10_R.jpg" < "100_R.jpg"
 * "2_R.jpg" と "02_R.jpg" は数値的に等しいため同じキーになる (タイブレークは DAO 側)。
 */
object NaturalSortKey {

    /** 名前からソートキーを生成する */
    fun create(displayName: String): String {
        // 全角数字・全角英字も NFKC で ASCII へ寄せ、大小文字を無視して比較する
        val normalized = Normalizer.normalize(displayName, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return ""

        val builder = StringBuilder(normalized.length + 16)
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            if (codePoint in '0'.code..'9'.code) {
                var end = index
                while (end < normalized.length && normalized[end] in '0'..'9') {
                    end++
                }
                val run = normalized.substring(index, end)
                val significant = run.trimStart('0').ifEmpty { "0" }
                builder.append('/')
                // ファイル名長は最大255程度なので6桁で十分。長い数字列でもオーバーフローしない
                builder.append(significant.length.toString().padStart(6, '0'))
                builder.append(significant)
                index = end
            } else {
                builder.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        return builder.toString()
    }

    /** 自然順キー → sortName → mediaId の安定した比較 */
    val comparator: Comparator<ImageItem> = Comparator { a, b ->
        val byKey = a.naturalSortKey.compareTo(b.naturalSortKey)
        if (byKey != 0) {
            byKey
        } else {
            val byName = a.sortName.compareTo(b.sortName)
            if (byName != 0) byName else a.mediaId.compareTo(b.mediaId)
        }
    }
}
