package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.LastViewedFolder
import com.tonbiattack.fastestpictureviewer.data.LastViewedStore
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** フォルダ一覧の 1 セル分 */
data class FolderUiModel(
    val folderKey: String,
    val volumeName: String,
    val relativePath: String,
    val folderName: String,
    val imageCount: Long,
    val latestModifiedSec: Long,
    val representativeImageUri: String?,
    val isFavorite: Boolean = false
)

/** フォルダ一覧画面の状態 */
data class FolderListUiState(
    /** 最初の Room 発行待ち (キャッシュが全くない真の初回のみ表示する) */
    val isLoadingFirstFrame: Boolean = true,

    /** フィルタ適用後のフォルダ一覧。同期開始時にもクリアしない (Issue #3) */
    val folders: List<FolderUiModel> = emptyList(),

    /** フィルタ前のフォルダが 1 件でもあるか (空状態の文言判定用) */
    val hasFolders: Boolean = false,

    /** 表示列数 (1〜4) */
    val columnCount: Int = 2,

    /** フォルダ名検索クエリ */
    val searchQuery: String = "",

    /** お気に入りのみ表示中か */
    val favoritesOnly: Boolean = false,

    /** フォルダ一覧の並び順 */
    val sortMode: FolderSortMode = FolderSortMode.LATEST,

    /** 復元する先頭インデックス */
    val initialIndex: Int = 0,

    /** 復元する先頭アイテムのピクセルオフセット */
    val initialOffset: Int = 0,

    /** 並び順ごとのスクロール位置の読み込みが完了したか */
    val isScrollRestored: Boolean = false,

    /** フィルタなしの並び順のフォルダキー (スクロール保存用) */
    val allFolderKeys: List<String> = emptyList(),

    /** 起動時の「前回の続き」に表示するフォルダ */
    val resumeFolder: FolderUiModel? = null,

    /** 前回見ていた画像 ID */
    val resumeItemId: String? = null,

    /** 前回見ていた画像のインデックス */
    val resumeItemIndex: Int = 0,

    /** 背景同期中かどうか */
    val isSyncing: Boolean = false,

    /** 初回スキャン (まだキャッシュが全くない状態での走査) 中か */
    val isInitialScan: Boolean = false,

    /** 同期失敗のメッセージ */
    val errorMessage: String? = null
) {
    /** 同期中でまだ 1 件も無い場合に初回スキャン表示を出す */
    val showInitialScanProgress: Boolean get() = isSyncing && !hasFolders

    /** 検索中か */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /** 検索・お気に入りフィルタ中か (この間のスクロール位置は保存しない) */
    val isFiltered: Boolean get() = isSearching || favoritesOnly
}

/** 内部で扱うフォルダ・代表画像・お気に入りの組み合わせ */
private data class FolderDataSource(
    val summaries: List<FolderSummaryRow>,
    val representatives: List<RepresentativeImageRow>,
    val favoriteKeys: Set<String>,
    val recentOpened: Map<String, Long>
)

/** 検索とお気に入りフィルタ */
private data class FolderFilter(
    val query: String,
    val favoritesOnly: Boolean
)

/** スクロール位置の復元情報 */
private data class ScrollRestore(
    val loaded: Boolean,
    val anchorFolderKey: String?,
    val fallbackIndex: Int,
    val offsetPx: Int
)

/** UI 合成用のフィルタ + 復元情報 */
private data class UiContext(
    val filter: FolderFilter,
    val restore: ScrollRestore,
    val resume: LastViewedFolder?
) {
    val isFiltered: Boolean get() = filter.query.isNotBlank() || filter.favoritesOnly
}

/**
 * フォルダ一覧の ViewModel。
 *
 * - Room の Flow をそのまま StateFlow にする。再取得中も直前の一覧を保持し続ける
 * - 検索・お気に入りフィルタはキャッシュ済みデータに対して行い、再スキャンしない
 * - 列数・並び順は [FolderViewSettings] に保存し、再起動後も維持する
 * - スクロール位置は並び順ごとに Room に保存し、アプリ再起動後も復元する (Issue #28)
 */
class FolderListViewModel(
    private val repository: GalleryRepository,
    private val viewSettings: FolderViewSettings,
    private val lastViewedStore: LastViewedStore
) : ViewModel() {

    /** 一覧の初回表示までの時間計測用 (JVM 単体テストでも動くよう System.nanoTime を使う) */
    private val startedAtNanos = System.nanoTime()
    private var firstFrameLogged = false

    private val searchQuery = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)

    private val filterFlow = combine(searchQuery, favoritesOnly) { query, favorites ->
        FolderFilter(query = query, favoritesOnly = favorites)
    }

    private val dataSource = combine(
        repository.observeFolderSummaries(),
        // 代表サムネイル・お気に入りの到着を待たず、フォルダ一覧を先に表示する
        repository.observeRepresentatives().onStart { emit(emptyList()) },
        repository.observeFavoriteFolders().onStart { emit(emptyList()) },
        repository.observeRecentFolders().onStart { emit(emptyList()) }
    ) { summaries, representatives: List<RepresentativeImageRow>, favorites: List<FavoriteFolderEntity>, recent: List<RecentFolderEntity> ->
        FolderDataSource(
            summaries = summaries,
            representatives = representatives,
            favoriteKeys = favorites.mapTo(hashSetOf()) { it.folderKey },
            recentOpened = recent.associate { it.folderKey to it.openedAtSec }
        )
    }

    private val scrollRestore = MutableStateFlow(
        ScrollRestore(loaded = false, anchorFolderKey = null, fallbackIndex = 0, offsetPx = 0)
    )

    private var restoreJob: Job? = null
    private var saveJob: Job? = null
    private var latestIndex = 0
    private var latestOffset = 0
    private var latestSortMode = FolderSortMode.LATEST
    private var hasPendingScroll = false

    init {
        // 並び順が変わるたびに、その並び順の保存位置を読み直す
        viewModelScope.launch {
            // StateFlow は同一値の再発行をしないため、そのまま購読する
            viewSettings.sortMode.collect { mode -> loadScrollRestore(mode) }
        }
    }

    /**
     * フォルダ集計 + 代表サムネイル + お気に入り + 同期状態 + 列数 + スクロール復元を合成する。
     *
     * 除外設定が変わったら内側の Room クエリを購読し直す。Room の無効化通知だけに頼らず、
     * 解除直後に戻ってきた場合でも最新の一覧を確実に取得するための保険。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<FolderListUiState> =
        repository.observeExcludedFolders()
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                combine(
                    dataSource,
                    repository.syncStatus,
                    viewSettings.columnCount,
                    viewSettings.sortMode,
                    combine(
                        filterFlow,
                        scrollRestore,
                        lastViewedStore.lastViewed
                    ) { filter, restore, resume ->
                        UiContext(filter = filter, restore = restore, resume = resume)
                    }
                ) { data, status, columns, sortMode, context ->
                    if (!firstFrameLogged && data.summaries.isNotEmpty()) {
                        firstFrameLogged = true
                        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                        println("FPV-Perf: folder list first frame: ${elapsedMs}ms, folders=${data.summaries.size}")
                    }

                    val allFolders = data.summaries.toUiModels(
                        representatives = data.representatives,
                        favoriteKeys = data.favoriteKeys
                    )
                    val sortedFolders = when (sortMode) {
                        FolderSortMode.LATEST -> allFolders
                        FolderSortMode.RECENT -> allFolders.sortedWith(
                            compareBy<FolderUiModel> {
                                // 閲覧済み (false) を未閲覧 (true) より先に並べる
                                data.recentOpened[it.folderKey] == null
                            }
                                .thenByDescending { data.recentOpened[it.folderKey] ?: 0L }
                                .thenByDescending { it.latestModifiedSec }
                                .thenBy { it.folderKey }
                        )
                    }
                    val visibleFolders = sortedFolders.filter { folder ->
                        val matchesQuery = context.filter.query.isBlank() ||
                            folder.folderName.contains(context.filter.query, ignoreCase = true)
                        val matchesFavorite =
                            !context.filter.favoritesOnly || folder.isFavorite
                        matchesQuery && matchesFavorite
                    }

                    val resumeFolder = if (context.isFiltered) {
                        null
                    } else {
                        context.resume?.let { resume ->
                            allFolders.firstOrNull { it.folderKey == resume.folderKey }
                        }
                    }

                    FolderListUiState(
                        isLoadingFirstFrame = false,
                        folders = visibleFolders,
                        hasFolders = allFolders.isNotEmpty(),
                        columnCount = columns,
                        searchQuery = context.filter.query,
                        favoritesOnly = context.filter.favoritesOnly,
                        sortMode = sortMode,
                        initialIndex = if (context.isFiltered) {
                            0
                        } else {
                            FolderListScrollResolver.resolve(
                                folders = sortedFolders,
                                anchorFolderKey = context.restore.anchorFolderKey,
                                fallbackIndex = context.restore.fallbackIndex
                            )
                        },
                        initialOffset = if (context.isFiltered) 0 else context.restore.offsetPx,
                        isScrollRestored = context.restore.loaded,
                        allFolderKeys = sortedFolders.map { it.folderKey },
                        resumeFolder = resumeFolder,
                        resumeItemId = context.resume?.itemId,
                        resumeItemIndex = context.resume?.itemIndex ?: 0,
                        isSyncing = status.isRunning,
                        isInitialScan = status.isInitialScan,
                        errorMessage = status.errorMessage
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                // 設定画面離脱後も短時間はホットキープし、即戻るケースでの再発行を避ける
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FolderListUiState(isLoadingFirstFrame = true)
            )

    private fun loadScrollRestore(mode: FolderSortMode) {
        restoreJob?.cancel()
        saveJob?.cancel()
        latestIndex = 0
        latestOffset = 0
        latestSortMode = mode
        hasPendingScroll = false
        scrollRestore.value = ScrollRestore(
            loaded = false,
            anchorFolderKey = null,
            fallbackIndex = 0,
            offsetPx = 0
        )
        restoreJob = viewModelScope.launch {
            val saved = runCatching { repository.getFolderListScrollPosition(mode) }.getOrNull()
            scrollRestore.value = if (saved == null) {
                ScrollRestore(loaded = true, anchorFolderKey = null, fallbackIndex = 0, offsetPx = 0)
            } else {
                ScrollRestore(
                    loaded = true,
                    anchorFolderKey = saved.anchorFolderKey,
                    fallbackIndex = saved.anchorIndex,
                    offsetPx = saved.anchorOffsetPx
                )
            }
        }
    }

    /** フォルダ名検索の入力を更新する */
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

    /** 「前回の続き」履歴を消す */
    fun clearResume() {
        lastViewedStore.clear()
    }

    /** 列数を変更して永続化する */
    fun setColumnCount(count: Int) {
        viewSettings.setColumnCount(count)
    }

    /** 並び順を変更して永続化する。切替前に現在の位置を保存する */
    fun setSortMode(mode: FolderSortMode) {
        if (mode != viewSettings.sortMode.value) {
            flushScrollPosition()
        }
        viewSettings.setSortMode(mode)
    }

    /** スクロール中の位置を軽量に受け取り、500ms の debounce 後に永続化する */
    fun onScrollPositionChanged(index: Int, offsetPx: Int) {
        val state = uiState.value
        if (!state.isScrollRestored || state.isFiltered) return
        latestIndex = index
        latestOffset = offsetPx
        latestSortMode = viewSettings.sortMode.value
        hasPendingScroll = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val snapshot = snapshotScrollForSave() ?: return@launch
            hasPendingScroll = false
            persistScrollPosition(snapshot)
        }
    }

    /** 画面離脱時に最後の位置を即座に保存する */
    fun flushScrollPosition() {
        saveJob?.cancel()
        // 並び順切替中でも旧モードのキーと一致するよう、その場でスナップショットを取る
        val snapshot = snapshotScrollForSave() ?: return
        hasPendingScroll = false
        viewModelScope.launch {
            persistScrollPosition(snapshot)
        }
    }

    private fun snapshotScrollForSave(): ScrollSave? {
        val state = uiState.value
        if (!hasPendingScroll || state.isFiltered || state.allFolderKeys.isEmpty()) return null
        val index = latestIndex.coerceIn(0, state.allFolderKeys.lastIndex)
        return ScrollSave(
            sortMode = latestSortMode,
            anchorFolderKey = state.allFolderKeys.getOrNull(index),
            anchorIndex = index,
            offsetPx = latestOffset
        )
    }

    private suspend fun persistScrollPosition(snapshot: ScrollSave) {
        repository.saveFolderListScrollPosition(
            sortMode = snapshot.sortMode,
            anchorFolderKey = snapshot.anchorFolderKey,
            anchorIndex = snapshot.anchorIndex,
            anchorOffsetPx = snapshot.offsetPx
        )
    }

    private data class ScrollSave(
        val sortMode: FolderSortMode,
        val anchorFolderKey: String?,
        val anchorIndex: Int,
        val offsetPx: Int
    )

    private fun List<FolderSummaryRow>.toUiModels(
        representatives: List<RepresentativeImageRow>,
        favoriteKeys: Set<String>
    ): List<FolderUiModel> {
        // フォルダごとに代表サムネイルを最大 1 件割り当てる
        val repByFolder = representatives.associateBy({ it.folderKey }, { it.id })
        return map { row ->
            FolderUiModel(
                folderKey = row.folderKey,
                volumeName = row.volumeName,
                relativePath = row.relativePath,
                folderName = FolderNames.parse(row.relativePath),
                imageCount = row.imageCount,
                latestModifiedSec = row.latestModifiedSec,
                representativeImageUri = repByFolder[row.folderKey],
                isFavorite = row.folderKey in favoriteKeys
            )
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
