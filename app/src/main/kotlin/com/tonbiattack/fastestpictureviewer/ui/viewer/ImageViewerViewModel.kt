package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 画像ビューアの状態 */
data class ImageViewerUiState(
    /** 表示対象フォルダの画像一覧 (前後移動はこのリスト内で行う) */
    val images: List<ImageItem> = emptyList(),
    /** 起動時に指していた位置 */
    val initialIndex: Int = 0
)

/**
 * 画像詳細画面の ViewModel。
 *
 * - フォルダ識別子・初期位置は SavedStateHandle から復元する (プロセス再生成対応、Issue #4)
 * - 現在のページ位置が変わるたびに SavedStateHandle に書き戻し、プロセス死後も同じ位置から再開する
 */
class ImageViewerViewModel(
    repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val folderKey: String = savedStateHandle.get<String>(KEY_FOLDER).orEmpty()

    /**
     * ナビゲーション引数は型を宣言していないと String で入るため、
     * Int / String / Long のいずれでも安全に読み取る。
     */
    private val requestedIndex: Int = run {
        val raw: Any? = savedStateHandle[KEY_INDEX]
        val index = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
        index.takeIf { it >= 0 } ?: 0
    }

    val uiState: StateFlow<ImageViewerUiState> = repository.observeImages(folderKey)
        .map { images ->
            ImageViewerUiState(
                images = images,
                initialIndex = requestedIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ImageViewerUiState(images = emptyList(), initialIndex = requestedIndex)
        )

    /** ページ移動時の位置保持 (プロセス再生成後に同じ画像を開く) */
    fun onPageChanged(index: Int) {
        savedStateHandle[KEY_PAGE] = index
    }

    private companion object {
        const val KEY_FOLDER = "folderKey"
        const val KEY_INDEX = "startIndex"
        const val KEY_PAGE = "currentPage"
    }
}
