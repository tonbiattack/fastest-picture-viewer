package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 画像詳細から戻ったときにグリッドで表示する位置 */
data class ImageGridScrollTarget(
    val itemId: String?,
    val fallbackIndex: Int,
    val requestId: Long
)

/** 画像グリッド画面の状態 */
data class ImageGridUiState(
    /** フォルダ識別子 (未初期化の場合は null) */
    val folderKey: String? = null,
    /** フォルダ表示名 */
    val folderName: String = "",
    /** フィルタ適用後の画像一覧。同期中でも決して空配列に戻さない */
    val images: List<ImageItem> = emptyList(),
    /** フィルタ前の全画像一覧 (スクロール保存・復帰解決用) */
    val allImages: List<ImageItem> = emptyList(),
    /** お気に入り登録済みの画像 ID */
    val favoriteIds: Set<String> = emptySet(),
    /** ファイル名検索クエリ */
    val searchQuery: String = "",
    /** お気に入りのみ表示中か */
    val favoritesOnly: Boolean = false,
    /** 画像一覧の並び順 (Issue #130) */
    val sortMode: ImageSortMode = ImageSortMode.DEFAULT,
    /** 復元する先頭インデックス */
    val initialIndex: Int = 0,
    /** 復元する先頭アイテムのピクセルオフセット */
    val initialOffset: Int = 0,
    /** フォルダ別スクロール位置の読み込みが完了したか */
    val isScrollRestored: Boolean = false,
    /** 画像グリッドの列数 (2〜6) */
    val columnCount: Int = 3,
    /** 縦向き用に保存されている列数 (Issue #44) */
    val portraitColumnCount: Int = 3,
    /** 横向き用に保存されている列数 (Issue #44) */
    val landscapeColumnCount: Int = 3,
    /** 詳細画面から戻った際のスクロール先 */
    val scrollTarget: ImageGridScrollTarget? = null,
    /** 詳細画面から戻るたびに増える世代。グリッド再生成のキーに使う */
    val returnRequestId: Long = 0L,
    /** 背景同期中 */
    val isSyncing: Boolean = false
) {
    /** ルート引数がまだ届いていない (ナビ引数なしで生成された) 場合のみ true */
    val isWaitingNavArgs: Boolean get() = folderKey == null

    /** ファイル名検索中か */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /** 検索・お気に入りフィルタ中か (この間のスクロール位置は保存しない) */
    val isFiltered: Boolean get() = isSearching || favoritesOnly
}

/** スクロール位置の復元情報 */
private data class ScrollRestore(
    val loaded: Boolean,
    val anchorItemId: String?,
    val fallbackIndex: Int,
    val offsetPx: Int
)

/** 検索とお気に入りフィルタ */
private data class GridFilter(
    val query: String,
    val favoritesOnly: Boolean
)

/** UI 合成用のコンテキスト */
private data class GridUiContext(
    val favoriteOverrides: Map<String, Boolean>,
    val restore: ScrollRestore,
    val columns: ColumnSettings,
    val scrollTarget: ImageGridScrollTarget?,
    val returnRequestId: Long,
    val filter: GridFilter
) {
    val isFiltered: Boolean get() = filter.query.isNotBlank() || filter.favoritesOnly
}

/** 向き別の列数設定 (Issue #44) */
private data class ColumnSettings(
    val portrait: Int,
    val landscape: Int,
    val current: Int
)

/**
 * フォルダ内画像一覧の ViewModel。
 *
 * - Room の Flow を直接購読し、同期開始時に一覧を空にしない (Issue #3)
 * - 画像お気に入りの星を一覧から直接切り替えられる (Issue #27)
 * - フォルダ別のスクロール位置を Room に保存し、再入場・再起動後も復元する (Issue #21)
 * - 列数 (2〜6) をフォルダ一覧とは独立して永続化する (Issue #34)
 * - 詳細画面で最後に見ていた画像 ID を受け取り、戻り先の位置を解決する (Issue #35)
 * - お気に入りフィルタ・ファイル名検索を読み込み済みリストに対して行う (Issue #39)
 */
class ImageGridViewModel(
    private val repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle,
    private val viewSettings: ImageGridViewSettings
) : ViewModel() {

    /** ルート引数から復元されたフォルダ識別子 */
    private val folderKey: String? = savedStateHandle[KEY_FOLDER]

    /** 連打時も最後の操作が勝つよう、DB 書き込みを直列化する */
    private val favoriteMutex = Mutex()

    /** DB 反映を待たずに星の状態を即時反映する */
    private val favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val searchQuery = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)

    private val filterFlow = combine(searchQuery, favoritesOnly) { query, favorites ->
        GridFilter(query = query, favoritesOnly = favorites)
    }

    private val filterContext = combine(favoriteOverrides, filterFlow) { overrides, filter ->
        overrides to filter
    }

    private val scrollRestore = MutableStateFlow(
        ScrollRestore(loaded = false, anchorItemId = null, fallbackIndex = 0, offsetPx = 0)
    )

    /** 詳細画面から戻ったときのスクロール先 */
    private val scrollTarget = MutableStateFlow<ImageGridScrollTarget?>(null)

    /** グリッドを再生成する世代。スクロール先の消費後も値を維持する */
    private val returnRequest = MutableStateFlow(0L)

    /** 縦横それぞれの保存値と、現在適用中の列数をまとめる (Issue #44) */
    private val columnSettings = combine(
        viewSettings.portraitColumnCount,
        viewSettings.landscapeColumnCount,
        viewSettings.columnCount
    ) { portrait, landscape, current ->
        ColumnSettings(portrait = portrait, landscape = landscape, current = current)
    }

    private val uiContext = combine(
        scrollRestore,
        columnSettings,
        scrollTarget,
        returnRequest,
        filterContext
    ) { restore, columns, target, returnId, filterPair ->
        val (overrides, filter) = filterPair
        GridUiContext(
            favoriteOverrides = overrides,
            restore = restore,
            columns = columns,
            scrollTarget = target,
            returnRequestId = returnId,
            filter = filter
        )
    }

    private var latestIndex = 0
    private var latestOffset = 0
    private var visibleFirstIndex = 0
    private var visibleLastIndex = 0
    private var saveJob: Job? = null

    /** 読み込み失敗から実体を確認済みの画像 (Issue #77)。同じ画像で繰り返さない */
    private val loadErrorCheckedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        // 並び順を切り替えると購読先のクエリごと変わる (Issue #130)。
        // flatMapLatest で古い購読を打ち切り、切り替え時に二重に流さない
        viewSettings.sortMode.flatMapLatest { mode ->
            repository.observeImages(folderKey.orEmpty(), mode)
        },
        repository.syncStatus,
        repository.observeFavorites(),
        uiContext
    ) { images: List<ImageItem>, sync, favorites: List<FavoriteImageEntity>, context: GridUiContext ->
        val favoriteIds = favorites.mapTo(hashSetOf()) { it.itemId }
        context.favoriteOverrides.forEach { (itemId, favorite) ->
            if (favorite) favoriteIds.add(itemId) else favoriteIds.remove(itemId)
        }
        val visibleImages = if (context.isFiltered) {
            images.filter { image ->
                val matchesQuery = context.filter.query.isBlank() ||
                    image.displayName.contains(context.filter.query, ignoreCase = true)
                val matchesFavorite =
                    !context.filter.favoritesOnly || image.id in favoriteIds
                matchesQuery && matchesFavorite
            }
        } else {
            images
        }
        ImageGridUiState(
            folderKey = folderKey,
            folderName = images.firstOrNull()?.folderName
                ?: folderKey?.let { FolderNames.fromFolderKey(it) } ?: "",
            images = visibleImages,
            allImages = images,
            favoriteIds = favoriteIds,
            searchQuery = context.filter.query,
            favoritesOnly = context.filter.favoritesOnly,
            sortMode = viewSettings.sortMode.value,
            initialIndex = if (context.isFiltered) {
                0
            } else {
                ScrollPositionResolver.resolve(
                    images = images,
                    anchorItemId = context.restore.anchorItemId,
                    fallbackIndex = context.restore.fallbackIndex
                )
            },
            initialOffset = if (context.isFiltered) 0 else context.restore.offsetPx,
            isScrollRestored = context.restore.loaded,
            columnCount = context.columns.current,
            portraitColumnCount = context.columns.portrait,
            landscapeColumnCount = context.columns.landscape,
            scrollTarget = context.scrollTarget,
            returnRequestId = context.returnRequestId,
            isSyncing = sync.isRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageGridUiState()
    )

    /** ファイル名検索の入力を更新する */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    /** 検索を解除して全件表示に戻す */
    fun clearSearch() {
        searchQuery.value = ""
    }

    /** お気に入りのみ表示を切り替える */
    fun toggleFavoritesOnly() {
        favoritesOnly.value = !favoritesOnly.value
    }

    /**
     * 画像一覧の並び順を変更して永続化する (Issue #130)。
     *
     * 並び順が変わると画像の位置が変わるため、スクロール位置は先頭に戻す。
     * 元の位置を保つと、別の画像を指したまま復元されてしまう。
     */
    fun setSortMode(mode: ImageSortMode) {
        if (viewSettings.sortMode.value == mode) return
        viewSettings.setSortMode(mode)
        // 並び順ごとに保存された位置が別物になるため、復帰位置をリセットする
        scrollRestore.value = ScrollRestore(
            loaded = true,
            anchorItemId = null,
            fallbackIndex = 0,
            offsetPx = 0
        )
        returnRequest.value += 1
    }

    /**
     * 表示中の範囲を受け取る。詳細から戻った画像が既に画面内なら位置を動かさないために使う。
     */
    fun onVisibleRangeChanged(firstIndex: Int, lastIndex: Int, offsetPx: Int) {
        // フィルタ中の位置は通常一覧の保存位置を上書きしない
        if (uiState.value.isFiltered) return
        visibleFirstIndex = firstIndex
        visibleLastIndex = lastIndex
        onScrollPositionChanged(firstIndex, offsetPx)
    }

    /**
     * サムネイルの読み込みに失敗した画像を受ける (Issue #77)。
     *
     * OPPO/ColorOS では削除済み画像の行が MediaStore に残るため、実体を確認して
     * 開けなければ一覧から外す。フォルダ全体ではなく、失敗した画像だけを対象にする。
     * 同じ画像で何度も確認しないよう記録する。
     */
    fun onImageLoadError(image: ImageItem) {
        if (!loadErrorCheckedIds.add(image.id)) return
        viewModelScope.launch {
            val removed = runCatching {
                repository.verifyImageReadable(image)
            }.getOrDefault(false)
            if (!removed) {
                // 読めた場合は次回また失敗したときに再確認できるようにする
                loadErrorCheckedIds.remove(image.id)
            }
        }
    }

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
        val state = uiState.value
        if (state.isFiltered) return
        val images = state.allImages
        if (images.isEmpty()) return
        val index = latestIndex.coerceIn(0, images.lastIndex)
        repository.saveScrollPosition(
            folderKey = key,
            anchorItemId = images.getOrNull(index)?.id,
            anchorIndex = index,
            anchorOffsetPx = latestOffset
        )
    }

    /** 列数を変更して永続化する。縦横それぞれの保存値へ書き込む (Issue #44) */
    fun setColumnCount(count: Int) {
        viewSettings.setColumnCount(count)
    }

    /**
     * 画面の向きを通知する。保存値は変えず、その向きで最後に設定した列数を適用する。
     */
    fun onOrientationChanged(isLandscape: Boolean) {
        viewSettings.setLandscape(isLandscape)
    }

    /**
     * 詳細画面から戻った際の最後の閲覧画像を受け取る。
     * 画像 ID が見つからない場合は [fallbackIndex] を近傍位置として使う。
     */
    fun onReturnedFromViewer(itemId: String?, fallbackIndex: Int) {
        val state = uiState.value
        if (state.isFiltered) return
        val images = state.allImages
        if (images.isNotEmpty()) {
            val targetIndex = ScrollPositionResolver.resolve(
                images = images,
                anchorItemId = itemId,
                fallbackIndex = fallbackIndex.coerceAtLeast(0)
            )
            // 最後に見ていた画像がすでに画面内なら、元のグリッド位置を維持する
            if (targetIndex in visibleFirstIndex..visibleLastIndex) return
        }

        val next = returnRequest.value + 1
        returnRequest.value = next
        scrollTarget.value = ImageGridScrollTarget(
            itemId = itemId,
            fallbackIndex = fallbackIndex.coerceAtLeast(0),
            requestId = next
        )
    }

    /** スクロール先を適用済みにする。新しい要求が来ていれば消さない */
    fun consumeScrollTarget(requestId: Long) {
        scrollTarget.update { current ->
            if (current?.requestId == requestId) null else current
        }
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
