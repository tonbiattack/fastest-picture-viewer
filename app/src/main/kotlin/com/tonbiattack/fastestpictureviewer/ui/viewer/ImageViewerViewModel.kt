package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewSettings
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.ui.navigation.AdjacentFolders
import com.tonbiattack.fastestpictureviewer.ui.navigation.FolderSequenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val favoriteIds: Set<String> = emptySet(),
    /** 前のフォルダから移動してきたので末尾の画像から開く (Issue #57) */
    val startsWithLastImage: Boolean = false
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
    private val savedStateHandle: SavedStateHandle,
    private val folderSequenceStore: FolderSequenceStore = FolderSequenceStore(),
    private val slideshowSettings: SlideshowSettings? = null,
    /**
     * 画像一覧の並び順 (Issue #137)。
     *
     * グリッドと同じ並び順で読まないと、グリッドで選んだ位置と
     * ビューアが開く画像がずれる。null なら名前順（従来の動作）。
     */
    private val gridViewSettings: ImageGridViewSettings? = null
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

    /** 前のフォルダから移動してきたか。末尾の画像から開く (Issue #57) */
    private val openFromEnd: Boolean = when (val raw: Any? = savedStateHandle[KEY_FROM_END]) {
        is Boolean -> raw
        is String -> raw.toBoolean()
        else -> false
    }

    private val imagesFlow: Flow<List<ImageItem>> =
        if (favoritesOnly) {
            repository.observeFavoriteImages(favoriteFolderFilter)
        } else {
            // グリッドと同じ並び順で読む (Issue #137)。
            // 並び順を渡さないと常に名前順になり、グリッドで選んだ位置とずれる
            val sortModeFlow = gridViewSettings?.sortMode
            if (sortModeFlow == null) {
                repository.observeImages(rawFolderKey)
            } else {
                // @OptIn は flatMapLatest 自体に必要なため、呼び出し側で指定する
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                sortModeFlow.flatMapLatest { mode ->
                    repository.observeImages(rawFolderKey, mode)
                }
            }
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
            initialIndex = if (openFromEnd && images.isNotEmpty()) {
                // 前のフォルダから移動したときは末尾の画像から開く (Issue #57)
                images.lastIndex
            } else {
                requestedIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
            },
            favoriteIds = favoriteIds,
            startsWithLastImage = openFromEnd
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

    // ---- スライドショー (Issue #124) ----

    /**
     * スライドショーの再生状態。
     *
     * 保存はしない。画面を開き直した直後に勝手に再生が始まらないよう、既定は停止。
     */
    private val _slideshowState = MutableStateFlow(SlideshowState.STOPPED)
    val slideshowState: StateFlow<SlideshowState> = _slideshowState.asStateFlow()

    /** 表示間隔。設定が無い場合は既定値で動く */
    val slideshowInterval: StateFlow<SlideshowInterval> =
        slideshowSettings?.interval ?: MutableStateFlow(SlideshowInterval.DEFAULT)

    /** スライドショーを開始する。画像が 1 枚以下なら何もしない */
    fun startSlideshow() {
        if (uiState.value.images.size <= 1) return
        _slideshowState.value = SlideshowState.PLAYING
    }

    /** 再生と一時停止を切り替える */
    fun toggleSlideshow() {
        _slideshowState.value = when (_slideshowState.value) {
            SlideshowState.PLAYING -> SlideshowState.PAUSED
            SlideshowState.STOPPED, SlideshowState.PAUSED -> {
                if (uiState.value.images.size <= 1) {
                    SlideshowState.STOPPED
                } else {
                    SlideshowState.PLAYING
                }
            }
        }
    }

    /** 一時停止する（状態は保持し、再開できるようにする） */
    fun pauseSlideshow() {
        if (_slideshowState.value == SlideshowState.PLAYING) {
            _slideshowState.value = SlideshowState.PAUSED
        }
    }

    /** スライドショーを終了する。操作 UI も通常表示に戻す */
    fun stopSlideshow() {
        _slideshowState.value = SlideshowState.STOPPED
    }

    /**
     * バックグラウンドへ移行したときに呼ぶ。
     *
     * 復帰時に意図せず再生が再開しないよう、停止ではなく一時停止にする。
     * 利用者が明示的に再開したときだけ進む。
     */
    fun onEnterBackground() {
        pauseSlideshow()
    }

    /** 表示間隔を変更して保存する。再生中なら次の切り替えから新しい間隔になる */
    fun setSlideshowInterval(interval: SlideshowInterval) {
        slideshowSettings?.setInterval(interval)
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

    /**
     * 前後のフォルダ (Issue #57)。フォルダ一覧で表示中の並び・絞り込み結果を引き継ぐ。
     * お気に入り画像の全件ビューアなどフォルダに紐付かない画面では常に NONE。
     */
    val adjacentFolders: StateFlow<AdjacentFolders> =
        folderSequenceStore.folderKeys
            .map { keys -> adjacentOf(keys) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AdjacentFolders.NONE
            )

    private fun adjacentOf(keys: List<String>): AdjacentFolders {
        if (favoritesOnly || rawFolderKey.isEmpty() || rawFolderKey == ALL_FAVORITES) {
            return AdjacentFolders.NONE
        }
        val index = keys.indexOf(rawFolderKey)
        if (index < 0) return AdjacentFolders.NONE
        return AdjacentFolders(
            previous = keys.getOrNull(index - 1),
            next = keys.getOrNull(index + 1)
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
        private const val KEY_FROM_END = "fromEnd"
        private const val KEY_FAVORITES_ONLY = "favoritesOnly"
    }
}
