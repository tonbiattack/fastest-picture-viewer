package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 画像ビューアの状態 */
data class ImageViewerUiState(
    /** 表示対象の画像一覧 (前後移動はこのリスト内で行う) */
    val images: List<ImageItem> = emptyList(),
    /** 起動時に指していた位置 */
    val initialIndex: Int = 0,
    /** お気に入り登録済みの画像 ID */
    val favoriteIds: Set<String> = emptySet()
)

/**
 * 画像詳細画面の ViewModel。
 *
 * - フォルダ識別子・初期位置は SavedStateHandle から復元する (Issue #4)
 * - 通常フォルダのほか、お気に入り画像だけの連続ビューアにも対応する
 * - 画像お気に入りはフォルダお気に入りとは独立して Room に保存する
 */
class ImageViewerViewModel(
    private val repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val rawFolderKey: String = savedStateHandle.get<String>(KEY_FOLDER).orEmpty()

    private val favoritesOnly: Boolean = when (val raw: Any? = savedStateHandle[KEY_FAVORITES_ONLY]) {
        is Boolean -> raw
        is String -> raw.toBoolean()
        else -> false
    }

    /** お気に入りビューアの「全件」はフォルダ指定なしを表す */
    private val favoriteFolderFilter: String? =
        rawFolderKey.takeIf { it.isNotEmpty() && it != ALL_FAVORITES }

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

    private val imagesFlow: Flow<List<ImageItem>> =
        if (favoritesOnly) {
            repository.observeFavoriteImages(favoriteFolderFilter)
        } else {
            repository.observeImages(rawFolderKey)
        }

    /** 連打時も最後の操作が勝つよう、DB 書き込みを直列化する */
    private val favoriteMutex = Mutex()

    /** DB 反映を待たずに星の状態を即時反映する */
    private val favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val uiState: StateFlow<ImageViewerUiState> = combine(
        imagesFlow,
        repository.observeFavorites(),
        favoriteOverrides
    ) { images: List<ImageItem>, favorites: List<FavoriteImageEntity>, overrides: Map<String, Boolean> ->
        val favoriteIds = favorites.mapTo(hashSetOf()) { it.itemId }
        overrides.forEach { (itemId, favorite) ->
            if (favorite) favoriteIds.add(itemId) else favoriteIds.remove(itemId)
        }
        ImageViewerUiState(
            images = images,
            initialIndex = requestedIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
            favoriteIds = favoriteIds
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageViewerUiState(images = emptyList(), initialIndex = requestedIndex)
    )

    /** ページ移動時の位置保持 (プロセス再生成後に同じ画像を開く) */
    fun onPageChanged(index: Int) {
        savedStateHandle[KEY_PAGE] = index
    }

    /**
     * ランダムジャンプのジャンプ先インデックスを返す (Issue #58)。
     * 画像が無ければ null。現在表示中の画像は可能な限り避ける。
     * 画面履歴や戻る階層は増やさず、同じビューア内でページを移るだけ。
     */
    fun randomJumpTarget(currentIndex: Int): Int? =
        RandomJumpMath.pickTarget(
            count = uiState.value.images.size,
            currentIndex = currentIndex
        )

    /** 画像のお気に入りをワンタップで切り替える */
    fun toggleFavorite(itemId: String, currentFavorite: Boolean) {
        val next = !currentFavorite
        favoriteOverrides.update { it + (itemId to next) }
        viewModelScope.launch {
            favoriteMutex.withLock {
                try {
                    repository.setFavorite(itemId, next)
                } catch (t: Exception) {
                    // 失敗時は楽観更新を戻し、DB の状態に整合させる
                    favoriteOverrides.update { it - itemId }
                }
            }
        }
    }

    companion object {
        /** お気に入りビューアで全フォルダを対象にする場合の folderKey */
        const val ALL_FAVORITES = "_all_"

        private const val KEY_FOLDER = "folderKey"
        private const val KEY_INDEX = "startIndex"
        private const val KEY_PAGE = "currentPage"
        private const val KEY_FAVORITES_ONLY = "favoritesOnly"
    }
}
