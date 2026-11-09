package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 画像グリッド画面の状態 */
data class ImageGridUiState(
    /** フォルダ識別子 (未初期化の場合は null) */
    val folderKey: String? = null,
    /** フォルダ表示名 */
    val folderName: String = "",
    /** 画像一覧。同期中でも決して空配列に戻さない */
    val images: List<ImageItem> = emptyList(),
    /** 背景同期中 */
    val isSyncing: Boolean = false
) {
    /** ルート引数がまだ届いていない (ナビ引数なしで生成された) 場合のみ true */
    val isWaitingNavArgs: Boolean get() = folderKey == null
}

/**
 * フォルダ内画像一覧の ViewModel。
 *
 * - Room の Flow を直接購読し、同期開始時に一覧を空にしない (Issue #3)
 * - 開いているフォルダとスクロール位置を SavedStateHandle に保持し、
 *   プロセス再生成後も Room から即座に復元する (Issue #4)
 */
class ImageGridViewModel(
    repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** ルート引数から復元されたフォルダ識別子 */
    private val folderKey: String? = savedStateHandle[KEY_FOLDER]

    val uiState: StateFlow<ImageGridUiState> = combine(
        repository.observeImages(folderKey.orEmpty()),
        repository.syncStatus
    ) { images, sync ->
        ImageGridUiState(
            folderKey = folderKey,
            folderName = images.firstOrNull()?.folderName
                ?: folderKey?.let { FolderNames.fromFolderKey(it) } ?: "",
            images = images,
            isSyncing = sync.isRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageGridUiState()
    )

    /** スクロール基準位置をプロセス死に耐える形で保存する */
    fun saveScrollIndex(index: Int) {
        savedStateHandle[KEY_SCROLL] = index
    }

    val initialScrollIndex: Int
        get() = savedStateHandle[KEY_SCROLL] ?: 0

    private companion object {
        const val KEY_FOLDER = "folderKey"
        const val KEY_SCROLL = "gridScrollIndex"
    }
}
