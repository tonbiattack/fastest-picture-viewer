package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

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
}

/** 内部で扱うフォルダ・代表画像・お気に入りの組み合わせ */
private data class FolderDataSource(
    val summaries: List<FolderSummaryRow>,
    val representatives: List<RepresentativeImageRow>,
    val favoriteKeys: Set<String>
)

/** 検索とお気に入りフィルタ */
private data class FolderFilter(
    val query: String,
    val favoritesOnly: Boolean
)

/**
 * フォルダ一覧の ViewModel。
 *
 * - Room の Flow をそのまま StateFlow にする。再取得中も直前の一覧を保持し続ける
 * - 検索・お気に入りフィルタはキャッシュ済みデータに対して行い、再スキャンしない
 * - 列数は [FolderViewSettings] に保存し、再起動後も維持する
 * - スクロール位置は SavedStateHandle に書き込み、プロセス再生成後も復元する
 */
class FolderListViewModel(
    repository: GalleryRepository,
    private val viewSettings: FolderViewSettings,
    private val savedStateHandle: SavedStateHandle
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
        repository.observeFavoriteFolders().onStart { emit(emptyList()) }
    ) { summaries, representatives: List<RepresentativeImageRow>, favorites: List<FavoriteFolderEntity> ->
        FolderDataSource(
            summaries = summaries,
            representatives = representatives,
            favoriteKeys = favorites.mapTo(hashSetOf()) { it.folderKey }
        )
    }

    /**
     * フォルダ集計 + 代表サムネイル + お気に入り + 同期状態 + 列数を合成する。
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
                    filterFlow
                ) { data, status, columns, filter ->
                    if (!firstFrameLogged && data.summaries.isNotEmpty()) {
                        firstFrameLogged = true
                        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                        println("FPV-Perf: folder list first frame: ${elapsedMs}ms, folders=${data.summaries.size}")
                    }

                    val allFolders = data.summaries.toUiModels(
                        representatives = data.representatives,
                        favoriteKeys = data.favoriteKeys
                    )
                    val visibleFolders = allFolders.filter { folder ->
                        val matchesQuery = filter.query.isBlank() ||
                            folder.folderName.contains(filter.query, ignoreCase = true)
                        val matchesFavorite = !filter.favoritesOnly || folder.isFavorite
                        matchesQuery && matchesFavorite
                    }

                    FolderListUiState(
                        isLoadingFirstFrame = false,
                        folders = visibleFolders,
                        hasFolders = allFolders.isNotEmpty(),
                        columnCount = columns,
                        searchQuery = filter.query,
                        favoritesOnly = filter.favoritesOnly,
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

    /** 列数を変更して永続化する */
    fun setColumnCount(count: Int) {
        viewSettings.setColumnCount(count)
    }

    /** スクロール位置をプロセス死に耐える形で保存する */
    fun saveScrollIndex(index: Int) {
        savedStateHandle[KEY_SCROLL_INDEX] = index
    }

    fun initialScrollIndex(): Int = savedStateHandle[KEY_SCROLL_INDEX] ?: 0

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
        const val KEY_SCROLL_INDEX = "folderListScrollIndex"
    }
}
