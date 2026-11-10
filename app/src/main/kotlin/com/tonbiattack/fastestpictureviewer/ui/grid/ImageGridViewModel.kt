package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 画像グリッド画面の状態 */
data class ImageGridUiState(
    /** フォルダ識別子 (未初期化の場合は null) */
    val folderKey: String? = null,
    /** フォルダ表示名 */
    val folderName: String = "",
    /** 画像一覧。同期中でも決して空配列に戻さない */
    val images: List<ImageItem> = emptyList(),
    /** お気に入り登録済みの画像 ID */
    val favoriteIds: Set<String> = emptySet(),
    /** 復元する先頭インデックス */
    val initialIndex: Int = 0,
    /** 復元する先頭アイテムのピクセルオフセット */
    val initialOffset: Int = 0,
    /** フォルダ別スクロール位置の読み込みが完了したか */
    val isScrollRestored: Boolean = false,
    /** 背景同期中 */
    val isSyncing: Boolean = false
) {
    /** ルート引数がまだ届いていない (ナビ引数なしで生成された) 場合のみ true */
    val isWaitingNavArgs: Boolean get() = folderKey == null
}

/** スクロール位置の復元情報 */
private data class ScrollRestore(
    val loaded: Boolean,
    val anchorItemId: String?,
    val fallbackIndex: Int,
    val offsetPx: Int
)

/**
 * フォルダ内画像一覧の ViewModel。
 *
 * - Room の Flow を直接購読し、同期開始時に一覧を空にしない (Issue #3)
 * - 画像お気に入りの星を一覧から直接切り替えられる (Issue #27)
 * - フォルダ別のスクロール位置を Room に保存し、再入場・再起動後も復元する (Issue #21)
 */
class ImageGridViewModel(
    private val repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** ルート引数から復元されたフォルダ識別子 */
    private val folderKey: String? = savedStateHandle[KEY_FOLDER]

    /** 連打時も最後の操作が勝つよう、DB 書き込みを直列化する */
    private val favoriteMutex = Mutex()

    /** DB 反映を待たずに星の状態を即時反映する */
    private val favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val scrollRestore = MutableStateFlow(
        ScrollRestore(loaded = false, anchorItemId = null, fallbackIndex = 0, offsetPx = 0)
    )

    private var latestIndex = 0
    private var latestOffset = 0
    private var saveJob: Job? = null

    init {
        val key = folderKey
        if (key.isNullOrEmpty()) {
            scrollRestore.value = ScrollRestore(true, null, 0, 0)
        } else {
            viewModelScope.launch {
                val saved = runCatching { repository.getScrollPosition(key) }.getOrNull()
                scrollRestore.value = if (saved == null) {
                    ScrollRestore(
                        loaded = true,
                        anchorItemId = null,
                        fallbackIndex = savedStateHandle[KEY_SCROLL] ?: 0,
                        offsetPx = 0
                    )
                } else {
                    ScrollRestore(
                        loaded = true,
                        anchorItemId = saved.anchorItemId,
                        fallbackIndex = saved.anchorIndex,
                        offsetPx = saved.anchorOffsetPx
                    )
                }
            }
        }
    }

    val uiState: StateFlow<ImageGridUiState> = combine(
        repository.observeImages(folderKey.orEmpty()),
        repository.syncStatus,
        repository.observeFavorites(),
        favoriteOverrides,
        scrollRestore
    ) { images: List<ImageItem>, sync, favorites: List<FavoriteImageEntity>, overrides: Map<String, Boolean>, restore: ScrollRestore ->
        val favoriteIds = favorites.mapTo(hashSetOf()) { it.itemId }
        overrides.forEach { (itemId, favorite) ->
            if (favorite) favoriteIds.add(itemId) else favoriteIds.remove(itemId)
        }
        ImageGridUiState(
            folderKey = folderKey,
            folderName = images.firstOrNull()?.folderName
                ?: folderKey?.let { FolderNames.fromFolderKey(it) } ?: "",
            images = images,
            favoriteIds = favoriteIds,
            initialIndex = ScrollPositionResolver.resolve(
                images = images,
                anchorItemId = restore.anchorItemId,
                fallbackIndex = restore.fallbackIndex
            ),
            initialOffset = restore.offsetPx,
            isScrollRestored = restore.loaded,
            isSyncing = sync.isRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageGridUiState()
    )

    /** スクロール中の位置を軽量に受け取り、500ms の debounce 後に永続化する */
    fun onScrollPositionChanged(index: Int, offsetPx: Int) {
        latestIndex = index
        latestOffset = offsetPx
        savedStateHandle[KEY_SCROLL] = index
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistScrollPosition()
        }
    }

    /** 画面離脱時に最後の位置を即座に保存する */
    fun flushScrollPosition() {
        saveJob?.cancel()
        if (folderKey.isNullOrEmpty()) return
        viewModelScope.launch {
            persistScrollPosition()
        }
    }

    private suspend fun persistScrollPosition() {
        val key = folderKey ?: return
        val images = uiState.value.images
        if (images.isEmpty()) return
        val index = latestIndex.coerceIn(0, images.lastIndex)
        repository.saveScrollPosition(
            folderKey = key,
            anchorItemId = images.getOrNull(index)?.id,
            anchorIndex = index,
            anchorOffsetPx = latestOffset
        )
    }

    /** 画像のお気に入りをワンタップで切り替える */
    fun toggleFavorite(itemId: String, currentFavorite: Boolean) {
        val next = !currentFavorite
        favoriteOverrides.update { it + (itemId to next) }
        viewModelScope.launch {
            favoriteMutex.withLock {
                try {
                    repository.setFavorite(itemId, next)
                } catch (t: Exception) {
                    favoriteOverrides.update { it - itemId }
                }
            }
        }
    }

    private companion object {
        const val KEY_FOLDER = "folderKey"
        const val KEY_SCROLL = "gridScrollIndex"
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
