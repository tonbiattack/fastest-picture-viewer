package com.tonbiattack.fastestpictureviewer.ui.folder

/** 保存済みアンカーからフォルダ一覧の復元先インデックスを決める純粋ロジック */
object FolderListScrollResolver {

    /**
     * [anchorFolderKey] が見つかればその位置、見つからなければ保存時の [fallbackIndex] を使う。
     * フォルダの増減・除外・並び替えがあっても範囲内に丸める。
     */
    fun resolve(
        folders: List<FolderUiModel>,
        anchorFolderKey: String?,
        fallbackIndex: Int
    ): Int {
        if (folders.isEmpty()) return 0
        if (anchorFolderKey != null) {
            val index = folders.indexOfFirst { it.folderKey == anchorFolderKey }
            if (index >= 0) return index
        }
        return fallbackIndex.coerceIn(0, folders.lastIndex)
    }
}
