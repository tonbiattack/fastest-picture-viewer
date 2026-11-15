package com.tonbiattack.fastestpictureviewer.ui.viewer

/**
 * ビューアの前後画像の先読み範囲を決める (Issue #101)。
 *
 * スワイプが始まってから次画像をデコードすると、ドラッグ中に処理が走って引っかかる。
 * あらかじめ前後の画像をデコードしておき、スワイプ時はメモリキャッシュから出す。
 *
 * 先読みする範囲は「現在ページの前後 [DEFAULT_WINDOW] 枚」に固定する。
 * 全件先読みはせず、遠くへスクラブしたときは都度その周辺だけを読む。
 */
object ViewerPrefetch {

    /** 既定の先読み枚数（前後それぞれ） */
    const val DEFAULT_WINDOW = 2

    /**
     * 先読みするページ番号を、優先順に返す。
     *
     * - 現在ページは含めない（表示側が読み込む）
     * - 進む方向 ([direction] が正なら次、負なら前) を先に並べる。
     *   連続スワイプでは進む側が先に必要になるため
     * - 範囲外は含めない。重複もしない
     *
     * @param page 現在のページ
     * @param count 画像枚数
     * @param window 前後それぞれ何枚まで先読みするか
     * @param direction スワイプの向き。0 なら前後を交互に並べる
     */
    fun indicesToPrefetch(
        page: Int,
        count: Int,
        window: Int = DEFAULT_WINDOW,
        direction: Int = 0
    ): List<Int> {
        if (count <= 1 || window <= 0) return emptyList()
        if (page < 0 || page >= count) return emptyList()

        val forward = ArrayList<Int>(window)
        val backward = ArrayList<Int>(window)
        for (distance in 1..window) {
            (page + distance).takeIf { it < count }?.let { forward.add(it) }
            (page - distance).takeIf { it >= 0 }?.let { backward.add(it) }
        }

        val ordered = when {
            direction > 0 -> forward + backward
            direction < 0 -> backward + forward
            // 向きが分からないときは距離の近い順に前後を交互へ
            else -> buildList {
                for (i in 0 until maxOf(forward.size, backward.size)) {
                    forward.getOrNull(i)?.let { add(it) }
                    backward.getOrNull(i)?.let { add(it) }
                }
            }
        }
        return ordered.distinct()
    }
}
