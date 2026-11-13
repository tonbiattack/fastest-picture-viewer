package com.tonbiattack.fastestpictureviewer.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ビューアから見た前後のフォルダ (Issue #57) */
data class AdjacentFolders(
    val previous: String?,
    val next: String?
) {
    val hasPrevious: Boolean get() = previous != null
    val hasNext: Boolean get() = next != null
    val hasAny: Boolean get() = hasPrevious || hasNext

    companion object {
        val NONE = AdjacentFolders(previous = null, next = null)
    }
}

/**
 * フォルダ一覧に現在表示されているフォルダの並びを共有する (Issue #57)。
 *
 * ビューアから前後のフォルダへ移動するとき、一覧で適用中の並び順・検索・
 * お気に入り・タグ絞り込みの結果をそのまま引き継ぐために使う。
 * 一覧を離れても直近の並びを保持するだけで、DB への読み書きは行わない。
 */
class FolderSequenceStore {

    private val _folderKeys = MutableStateFlow<List<String>>(emptyList())

    /** 現在表示中のフォルダキー (一覧と同じ順序) */
    val folderKeys: StateFlow<List<String>> = _folderKeys.asStateFlow()

    /** 一覧の表示結果を記録する。同じ内容なら再発行しない */
    fun update(keys: List<String>) {
        if (_folderKeys.value == keys) return
        _folderKeys.value = keys
    }

    /**
     * [folderKey] の前後のフォルダを返す。
     * 対象が見つからない場合 (絞り込みで消えた・削除された等) は両方 null。
     */
    fun neighborsOf(folderKey: String): AdjacentFolders {
        val keys = _folderKeys.value
        val index = keys.indexOf(folderKey)
        if (index < 0) return AdjacentFolders.NONE
        return AdjacentFolders(
            previous = keys.getOrNull(index - 1),
            next = keys.getOrNull(index + 1)
        )
    }
}
