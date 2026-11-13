package com.tonbiattack.fastestpictureviewer.ui.viewer

import kotlin.random.Random

/**
 * ランダムジャンプのジャンプ先を決める純粋ロジック (Issue #58)。
 *
 * - 現在表示中の画像は可能な限り避ける
 * - 1 枚しかない場合はその画像を返す
 * - 範囲外のインデックスや空リストでも落ちない
 */
object RandomJumpMath {

    /**
     * 次に表示するインデックスを返す。画像が 0 枚なら null。
     *
     * @param count 画像枚数
     * @param currentIndex 現在表示中のインデックス
     * @param random 乱数源 (テストで差し替え可能)
     */
    fun pickTarget(count: Int, currentIndex: Int, random: Random = Random.Default): Int? {
        if (count <= 0) return null
        if (count == 1) return 0

        val current = currentIndex.takeIf { it in 0 until count }
        return if (current == null) {
            // 現在位置が不明なら全件から選ぶ
            random.nextInt(count)
        } else {
            // 現在の画像を除いた count-1 件から選び、元のインデックスへ詰め直す
            val offset = random.nextInt(count - 1)
            if (offset >= current) offset + 1 else offset
        }
    }
}
