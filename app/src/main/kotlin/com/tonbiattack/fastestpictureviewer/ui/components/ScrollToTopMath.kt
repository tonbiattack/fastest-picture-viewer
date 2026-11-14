package com.tonbiattack.fastestpictureviewer.ui.components

import kotlin.math.roundToInt

/**
 * 「先頭へ戻る」ボタンの表示判定 (Issue #76)。
 *
 * 一覧の先頭にいるときは出さず、少しでもスクロールしていたら出す。
 * Compose に依存しない純粋ロジックにしてテストできるようにする。
 */
object ScrollToTopMath {

    /**
     * ボタンを表示するか。
     *
     * @param firstVisibleIndex 先頭に見えているアイテムのインデックス
     * @param firstVisibleOffset 先頭アイテムのピクセルオフセット
     * @param tolerancePx これ以下のオフセットは「先頭」とみなす許容値
     */
    fun shouldShow(
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        tolerancePx: Int = DEFAULT_TOLERANCE_PX
    ): Boolean {
        if (firstVisibleIndex > 0) return true
        return firstVisibleOffset > tolerancePx
    }

    /** タップしたときに戻るアイテムのインデックス */
    const val TOP_INDEX = 0

    /**
     * スクロール位置を保存するときの値。
     * 先頭へ戻した直後に保存すると、復元時も先頭になる。
     */
    fun savedIndexAfterReturningToTop(): Int = TOP_INDEX

    /**
     * 進捗 (0..1) から、ボタンを出す閾値を超えているか。
     * FastScroller と併用するときの目安として使う。
     */
    fun shouldShowForProgress(progress: Float): Boolean =
        progress.roundToInt() > 0 || progress > 0.02f

    /** 先頭とみなす既定の許容値 (dp 相当は呼び出し側で換算) */
    const val DEFAULT_TOLERANCE_PX = 8
}
