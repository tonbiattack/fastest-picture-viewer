package com.tonbiattack.fastestpictureviewer.core.util

/**
 * フォルダ内画像一覧の並び順。
 *
 * 永続化キーにも使うため、名前を変えると保存済みの設定が読めなくなる。
 * 読めなかった場合は既定値へ戻す（[com.tonbiattack.fastestpictureviewer.ui.grid.SharedPreferencesImageGridViewSettings]）。
 */
enum class ImageSortMode {
    /** ファイル名の自然順（既定）。連番の写真を撮影順に並べられる */
    NAME,

    /** 更新日時の新しい順。最近追加・編集した写真を先に見る */
    DATE_NEWEST,

    /** 更新日時の古い順。古い写真から順にたどる */
    DATE_OLDEST;

    /** 表示用のラベル */
    val label: String
        get() = when (this) {
            NAME -> "名前順"
            DATE_NEWEST -> "新しい順"
            DATE_OLDEST -> "古い順"
        }

    companion object {
        /** 既定は名前順。既存ユーザーの見た目を変えない */
        val DEFAULT = NAME

        /** 選べるすべての並び順。UI の選択肢に使う */
        val ALL: List<ImageSortMode> = entries.toList()

        /**
         * 保存されていた名前から復元する。
         * 想定外の値（旧バージョンの値、壊れた値）は既定値へ戻す。
         */
        fun fromName(name: String?): ImageSortMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
