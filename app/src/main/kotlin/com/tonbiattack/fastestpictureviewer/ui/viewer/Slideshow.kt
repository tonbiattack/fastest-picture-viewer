package com.tonbiattack.fastestpictureviewer.ui.viewer

/**
 * スライドショーの表示間隔 (Issue #124)。
 *
 * 選べる秒数をここに集約し、UI と保存値の両方から参照する。
 * 保存された値が壊れていても既定値へ戻せるよう、[fromSeconds] で正規化する。
 */
enum class SlideshowInterval(val seconds: Int) {
    SEC_1(1),
    SEC_2(2),
    SEC_3(3),
    SEC_5(5),
    SEC_10(10),
    SEC_15(15),
    SEC_30(30);

    /** ミリ秒での間隔。タイマーに渡す */
    val millis: Long get() = seconds * 1_000L

    /** 表示用のラベル */
    val label: String get() = "${seconds}秒"

    companion object {
        /** 初期値。Issue の要件どおり 3 秒 */
        val DEFAULT = SEC_3

        /** 選べるすべての間隔。UI の選択肢に使う */
        val ALL: List<SlideshowInterval> = entries.toList()

        /**
         * 保存されていた秒数から間隔を復元する。
         * 想定外の値（0 以下、選択肢に無い値）は既定値へ戻す。
         */
        fun fromSeconds(seconds: Int): SlideshowInterval =
            entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}

/**
 * スライドショーの再生状態。
 *
 * 画面の再生成やバックグラウンド移行で「意図せず再開」しないよう、
 * 再生中かどうかを明示的に持つ。既定は停止。
 */
enum class SlideshowState {
    /** 停止中。スライドショーを開始していない */
    STOPPED,

    /** 再生中。一定間隔で次へ進む */
    PLAYING,

    /** 一時停止中。手動で開始するまで進まない */
    PAUSED;

    val isPlaying: Boolean get() = this == PLAYING
}

/**
 * スライドショーの次に表示するページを決めるロジック。
 *
 * ループ再生（最終画像の次は先頭）と、画像が 0 枚・1 枚のときの
 * 安全側の扱いを純粋な関数として持ち、単体テストで固定する。
 */
object SlideshowPaging {

    /**
     * 次に進むページ番号を返す。
     *
     * @param current 現在のページ
     * @param pageCount 画像枚数
     * @return 次のページ。画像が 0 枚なら null（進めない）、1 枚なら常に 0
     */
    fun next(current: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        if (pageCount == 1) return 0
        // 最終画像の次は先頭へ戻る（ループ再生）
        return (current + 1).mod(pageCount)
    }

    /**
     * 前に戻るページ番号を返す。
     *
     * @param current 現在のページ
     * @param pageCount 画像枚数
     * @return 前のページ。画像が 0 枚なら null、1 枚なら常に 0
     */
    fun previous(current: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        if (pageCount == 1) return 0
        // 先頭の前は末尾へ（ループ再生）
        return (current - 1).mod(pageCount)
    }

    /**
     * 自動再生を続けるべきかを判定する。
     *
     * 画像が 1 枚以下のときは切り替えても意味が無いため、タイマーを回さない。
     * 一時停止中・停止中も回さない。
     */
    fun shouldAutoAdvance(state: SlideshowState, pageCount: Int): Boolean =
        state.isPlaying && pageCount > 1
}
