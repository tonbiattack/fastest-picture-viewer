package com.tonbiattack.fastestpictureviewer.perf

/**
 * ビューアのスワイプ応答の計測 (Issue #101)。
 *
 * 「スワイプを始めてから、その次の画像が実際に描かれるまで」を区間として出す。
 * 計測の起点は指を置いてドラッグが始まった時点、終点は新しいページの初回描画とする。
 * アニメーション（スナップ）の完了は含めないため、「画像が出るまでの引っかかり」を表す。
 *
 * 端末を必要としない名前の管理だけをここに置き、記録は [StartupTrace] に任せる
 * （同じ logcat タグ・同じ出力形式で、scripts 側の抽出処理を共通化するため）。
 *
 * 出力例:
 *   span=viewer_swipe_start>viewer_page_drawn ms=34
 *   viewer_image_loaded cached=true
 *
 * 画像のデコードがメモリキャッシュから出たか（cached）も記録する。
 * 先読みが効いていれば cached=true が増えるため、改善の効果を数で確認できる。
 */
object ViewerTrace {

    /** スワイプ（ドラッグ）を始めた時点 */
    const val MARK_SWIPE_START = "viewer_swipe_start"

    /** 移動先のページが描かれた時点 */
    const val MARK_PAGE_DRAWN = "viewer_page_drawn"

    /** ドラッグの開始を記録する */
    fun onSwipeStart() {
        StartupTrace.mark(MARK_SWIPE_START)
    }

    /** 移動先のページが描かれたことを記録し、開始からの経過を出す */
    fun onPageDrawn() {
        StartupTrace.mark(MARK_PAGE_DRAWN)
        StartupTrace.span(MARK_SWIPE_START, MARK_PAGE_DRAWN)
    }

    /**
     * 表示中の画像の読み込みが完了したことを記録する。
     *
     * @param fromMemoryCache Coil のメモリキャッシュから返ったか（先読みが効いたか）
     */
    fun onImageLoaded(fromMemoryCache: Boolean) {
        StartupTrace.event("viewer_image_loaded cached=$fromMemoryCache")
    }
}