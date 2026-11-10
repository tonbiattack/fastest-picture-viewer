package com.tonbiattack.fastestpictureviewer.core.util

/** フォルダ一覧の並び順。永続化キーにも使う */
enum class FolderSortMode {
    /** フォルダ内画像の最終更新日時降順 (既定) */
    LATEST,

    /** 最近閲覧したフォルダを先頭にし、未閲覧は更新日時降順 */
    RECENT
}
