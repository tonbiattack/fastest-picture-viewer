package com.tonbiattack.fastestpictureviewer.ui.viewer

/**
 * ビューアを閉じたときに「どの画像を見ていたか」を決めるロジック。
 *
 * ## なぜ必要か
 *
 * `PagerState.currentPage` はスワイプ中に**先行して**切り替わる。
 * 素早く戻ると、まだ表示していない次のページの ID を一覧へ返してしまい、
 * 一覧がその画像へスクロールする。利用者からは「前に見ていた画像が出る」
 * ように見える（実際は「まだ見ていない画像」）。
 *
 * そこで、戻る先は**表示が確定したページ**（`settledPage`）で決める。
 * ここではその判定だけを純粋な関数として持ち、単体テストで固定する。
 */
object ViewerExitPage {

    /**
     * 戻る先のページ番号を決める。
     *
     * @param settledPage アニメーションが落ち着いたページ（表示中の画像）
     * @param currentPage スワイプ中に先行して変わるページ
     * @param pageCount 画像枚数
     * @return 0..pageCount-1 に収めたページ番号。画像が無ければ 0
     */
    fun resolve(settledPage: Int, currentPage: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val last = pageCount - 1
        // settledPage が範囲内ならそれを正とする。
        // 範囲外（画像が減った直後など）は currentPage で補い、それも範囲外なら端に寄せる
        val candidate = when {
            settledPage in 0..last -> settledPage
            currentPage in 0..last -> currentPage
            else -> settledPage
        }
        return candidate.coerceIn(0, last)
    }
}
