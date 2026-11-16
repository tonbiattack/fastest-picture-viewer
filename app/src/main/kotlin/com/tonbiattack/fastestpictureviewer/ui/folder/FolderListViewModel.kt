package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.data.BackupReminder
import com.tonbiattack.fastestpictureviewer.data.BackupStatus
import com.tonbiattack.fastestpictureviewer.data.BackupStatusStore
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.FolderTagNameRow
import com.tonbiattack.fastestpictureviewer.data.LastViewedFolder
import com.tonbiattack.fastestpictureviewer.data.LastViewedStore
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.data.TagWithCount
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.MediaRescanHint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** フォルダに付いたタグ 1 件分 (バッジ表示用) */
data class FolderTagUi(
    val tagId: Long,
    val displayName: String
)

/** タグの絞り込み条件。複数タグ指定時の AND/OR は UI で明示する (Issue #46) */
data class TagFilterState(
    val tagIds: Set<Long> = emptySet(),
    val mode: TagFilterMode = TagFilterMode.OR
) {
    val isActive: Boolean get() = tagIds.isNotEmpty()
}

/** 複数タグを指定したときの絞り込み方法 */
enum class TagFilterMode {
    /** いずれかのタグが付いたフォルダを表示する */
    OR,

    /** 指定したすべてのタグが付いたフォルダだけを表示する */
    AND
}

/** フォルダ一覧の 1 セル分 */
data class FolderUiModel(
    val folderKey: String,
    val volumeName: String,
    val relativePath: String,
    val folderName: String,
    val imageCount: Long,
    val latestModifiedSec: Long,
    val representativeImageUri: String?,
    /** 代表画像の更新日時。フォルダの最終更新日時とは別 (Issue #49) */
    val representativeModifiedSec: Long = 0L,
    /** 代表画像のファイルサイズ。サムネイルキャッシュのキーに使う */
    val representativeSize: Long = 0L,
    val isFavorite: Boolean = false,
    /** 付与済みのタグ (表示用) */
    val tags: List<FolderTagUi> = emptyList()
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
    val errorMessage: String? = null,

    /** タグの絞り込み条件 (Issue #46) */
    val tagFilter: TagFilterState = TagFilterState(),

    /** 選択可能なタグ一覧 (付与件数付き) */
    val allTags: List<TagWithCount> = emptyList(),

    /** 「前回の続き」カードを表示するか (Issue #53) */
    val showResumeCard: Boolean = true,

    /** 表示中のフォルダキー (一覧と同じ順序)。ビューアの前後フォルダ移動に使う (Issue #57) */
    val visibleFolderKeys: List<String> = emptyList(),

    /**
     * 実体が見つからない画像の件数 (Issue #80)。
     * 0 なら案内を出さない。件数だけで、フォルダ名などは UI に出さない。
     */
    val staleImageCount: Long = 0,

    /**
     * メディア再スキャンを案内すべきか (Issue #86)。
     * 全件走査が成功したのに変化が無いときだけ true になる。
     */
    val suggestRescan: Boolean = false,

    /**
     * バックアップの書き出しを促すか (Issue #108)。
     *
     * 変更があり、かつ前回の書き出しから一定期間経ったときだけ true。
     * 起動をブロックしない控えめなバナーで出す。
     */
    val suggestBackup: Boolean = false,

    /** バックアップのリマインド本文。表示しないときは null */
    val backupReminderMessage: String? = null
) {
    /** 同期中でまだ 1 件も無い場合に初回スキャン表示を出す */
    val showInitialScanProgress: Boolean get() = isSyncing && !hasFolders

    /** 検索中か */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /** 検索・お気に入り・タグフィルタ中か (この間のスクロール位置は保存しない) */
    val isFiltered: Boolean get() = isSearching || favoritesOnly || tagFilter.isActive
}

/** 内部で扱うフォルダ・代表画像・お気に入りの組み合わせ */
private data class FolderDataSource(
    val summaries: List<FolderSummaryRow>,
    val representatives: List<RepresentativeImageRow>,
    val favoriteKeys: Set<String>,
    val recentOpened: Map<String, Long>,
    val tagsByFolder: Map<String, List<FolderTagUi>>
)

/** 検索とお気に入り・タグフィルタ */
private data class FolderFilter(
    val query: String,
    val favoritesOnly: Boolean,
    val tagFilter: TagFilterState
) {
    val isFiltered: Boolean
        get() = query.isNotBlank() || favoritesOnly || tagFilter.isActive
}

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
    val resume: LastViewedFolder?,
    val showResumeCard: Boolean
) {
    val isFiltered: Boolean get() = filter.isFiltered
}

/**
 * 一覧の骨格に影響しない付加情報 (Issue #80 / #86 / #108)。
 *
 * これらが変わっても一覧の並びは変わらないが、バナーの表示が変わる。
 */
private data class BackupExtras(
    val showResumeCard: Boolean,
    val staleImageCount: Long,
    val rescanDismissed: Boolean,
    val backupStatus: BackupStatus,
    val backupSnoozedUntilSec: Long
)

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
    private val lastViewedStore: LastViewedStore,
    /**
     * バックアップの書き出し状況 (Issue #108)。null ならリマインドを出さない。
     *
     * 起動時に読むのは SharedPreferences の数値 2 つだけ。DB も MediaStore も触らないため、
     * 一覧の初回表示を待たせない。
     */
    private val backupStatusStore: BackupStatusStore? = null,
    /** 現在時刻 (テスト差し替え用) */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : ViewModel() {

    /** 一覧の初回表示までの時間計測用 (JVM 単体テストでも動くよう System.nanoTime を使う) */
    private val startedAtNanos = System.nanoTime()
    private var firstFrameLogged = false

    private val searchQuery = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val tagFilter = MutableStateFlow(TagFilterState())

    private val filterFlow = combine(
        searchQuery,
        favoritesOnly,
        tagFilter
    ) { query, favorites, tags ->
        FolderFilter(query = query, favoritesOnly = favorites, tagFilter = tags)
    }

    private val dataSource = combine(
        // Issue #106: キャッシュ済みの集計があればそれを読む（未作成なら内部で従来の集計へ切り替わる）
        repository.observeFolderSummaries(),
        // 代表サムネイルは一覧の見た目そのものなので必須にする (Issue #110)。
        // 以前は onStart で空リストを先に出していたが、その結果 2,667 件を
        // サムネイル無しで 1 回描画し、代表が届いた時点でもう一度全件描画していた。
        // 実機では 2 回目の描画に約 1.2 秒かかっており、起動が遅くなる主因だった。
        // 集計と代表は同じキャッシュから読むため、到着時刻はほぼ同時になる。
        repository.observeRepresentatives(),
        // お気に入り・最近閲覧・タグは一覧の骨格に不要なので、到着を待たない
        repository.observeFavoriteFolders().onStart { emit(emptyList()) },
        repository.observeRecentFolders().onStart { emit(emptyList()) },
        repository.observeFolderTagNames().onStart { emit(emptyList()) }
    ) { summaries, representatives: List<RepresentativeImageRow>, favorites: List<FavoriteFolderEntity>, recent: List<RecentFolderEntity>, tagRows: List<FolderTagNameRow> ->
        // 起動時に 5 つの Room クエリが揃った時点 (Issue #98)
        StartupTrace.mark("data_source_combined")
        FolderDataSource(
            summaries = summaries,
            representatives = representatives,
            favoriteKeys = favorites.mapTo(hashSetOf()) { it.folderKey },
            recentOpened = recent.associate { it.folderKey to it.openedAtSec },
            tagsByFolder = tagRows.groupBy(
                keySelector = { it.folderKey },
                valueTransform = { FolderTagUi(tagId = it.tagId, displayName = it.displayName) }
            )
        )
    }

    /** 選択可能なタグ一覧。付与件数はバッジ表示のみに使い、絞り込みには使わない */
    private val tagsFlow = repository.observeTagsWithCount().onStart { emit(emptyList()) }

    private val scrollRestore = MutableStateFlow(
        ScrollRestore(loaded = false, anchorFolderKey = null, fallbackIndex = 0, offsetPx = 0)
    )

    private var restoreJob: Job? = null
    private var saveJob: Job? = null
    private var latestIndex = 0
    private var latestOffset = 0
    private var latestSortMode = FolderSortMode.LATEST
    private var hasPendingScroll = false

    /** サムネイル失敗から実体を確認済みのフォルダ (Issue #77)。同じフォルダで繰り返さない */
    private val thumbnailCheckedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 実体が見つからない画像の件数 (Issue #80)。画面表示用 */
    private val staleImageCount = MutableStateFlow(0L)

    /** 利用者がメディア再スキャンの案内を閉じたか (Issue #86) */
    private val rescanHintDismissed = MutableStateFlow(false)

    /**
     * バックアップのリマインドを先送りした期限 (Issue #108)。0 なら先送りしていない。
     *
     * メモリ上だけに持つ。アプリを再起動すると期限が消えるが、
     * 再起動のたびにバナーが出るのは煩わしいため、期限は SharedPreferences に置く。
     */
    private val backupSnoozedUntil = MutableStateFlow(0L)

    /** バックアップの書き出し状況。null ならリマインドしない */
    private val backupStatus = backupStatusStore?.status ?: MutableStateFlow(BackupStatus())

    init {
        StartupTrace.mark("vm_init_start")
        // 並び順が変わるたびに、その並び順の保存位置を読み直す
        viewModelScope.launch {
            // StateFlow は同一値の再発行をしないため、そのまま購読する
            viewSettings.sortMode.collect { mode -> loadScrollRestore(mode) }
        }
        // 実体が見つからない画像の件数を初回に読み込む (Issue #80)
        refreshStaleCount()
        StartupTrace.mark("vm_init_end")
    }

    /**
     * フォルダ集計 + 代表サムネイル + お気に入り + 同期状態 + 列数 + スクロール復元を合成する。
     *
     * 除外設定が変わったら内側の Room クエリを購読し直す。Room の無効化通知だけに頼らず、
     * 解除直後に戻ってきた場合でも最新の一覧を確実に取得するための保険。
     *
     * 初回値を人工的に追加せず、実際の除外リストの変化だけで再購読する。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<FolderListUiState> =
        repository.observeExcludedFolders()
            .distinctUntilChanged()
            .flatMapLatest {
                combine(
                    dataSource,
                    repository.syncStatus,
                    viewSettings.columnCount,
                    viewSettings.sortMode,
                    combine(
                        filterFlow,
                        scrollRestore,
                        lastViewedStore.lastViewed,
                        tagsFlow,
                        combine(
                            viewSettings.showResumeCard,
                            staleImageCount,
                            rescanHintDismissed,
                            backupStatus,
                            backupSnoozedUntil
                        ) { show, stale, dismissed, backup, snoozed ->
                            BackupExtras(
                                showResumeCard = show,
                                staleImageCount = stale,
                                rescanDismissed = dismissed,
                                backupStatus = backup,
                                backupSnoozedUntilSec = snoozed
                            )
                        }
                    ) { filter, restore, resume, allTags, extras ->
                        UiContext(
                            filter = filter,
                            restore = restore,
                            resume = resume,
                            showResumeCard = extras.showResumeCard
                        ) to (allTags to extras)
                    }
                ) { data, status, columns, sortMode, contextAndExtras ->
                    val (context, extrasPair) = contextAndExtras
                    val (allTags, extras) = extrasPair
                    val staleCount = extras.staleImageCount
                    val dismissed = extras.rescanDismissed
                    if (!firstFrameLogged && data.summaries.isNotEmpty()) {
                        firstFrameLogged = true
                        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                        println("FPV-Perf: folder list first frame: ${elapsedMs}ms, folders=${data.summaries.size}")
                    }

                    val allFolders = data.summaries.toUiModels(
                        representatives = data.representatives,
                        favoriteKeys = data.favoriteKeys,
                        tagsByFolder = data.tagsByFolder
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
                        val matchesTag = matchesTagFilter(folder, context.filter.tagFilter)
                        matchesQuery && matchesFavorite && matchesTag
                    }

                    val resumeFolder = if (context.isFiltered || !context.showResumeCard) {
                        null
                    } else {
                        context.resume?.let { resume ->
                            allFolders.firstOrNull { it.folderKey == resume.folderKey }
                        }
                    }

                    // 最初の一覧データが用意できた時点 (Issue #99)。
                    // ここから Compose の初回描画が始まる
                    StartupTrace.mark("folder_list_ready")

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
                        resumeItemId = if (context.showResumeCard) context.resume?.itemId else null,
                        resumeItemIndex = context.resume?.itemIndex ?: 0,
                        isSyncing = status.isRunning,
                        isInitialScan = status.isInitialScan,
                        errorMessage = status.errorMessage,
                        tagFilter = context.filter.tagFilter,
                        allTags = allTags,
                        showResumeCard = context.showResumeCard,
                        visibleFolderKeys = visibleFolders.map { it.folderKey },
                        staleImageCount = staleCount,
                        suggestRescan = !dismissed && MediaRescanHint.shouldSuggest(                            wasFullScan = status.wasFullScan,
                            addedCount = status.addedCount,
                            removedCount = status.removedCount,
                            failedVolumeCount = status.failedVolumeCount,
                            hasFolders = allFolders.isNotEmpty()
                        ),
                        // バックアップの書き出し忘れ防止 (Issue #108)。
                        // 変更が無い・先送り中・前回から日が浅いときは出さない
                        suggestBackup = shouldSuggestBackup(extras),
                        backupReminderMessage = if (shouldSuggestBackup(extras)) {
                            BackupReminder.message(
                                status = extras.backupStatus,
                                formattedLastExport = formatLastExport(extras.backupStatus)
                            )
                        } else {
                            null
                        }
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                // 設定画面離脱後も短時間はホットキープし、即戻るケースでの再発行を避ける
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FolderListUiState(isLoadingFirstFrame = true)
            )

    /**
     * バックアップのリマインドを出すべきか (Issue #108)。
     *
     * 判定は [BackupReminder] に任せ、ここでは現在時刻を渡すだけ。
     * 起動時に DB も MediaStore も触らないため、一覧の初回表示を待たせない。
     */
    private fun shouldSuggestBackup(extras: BackupExtras): Boolean {
        if (backupStatusStore == null) return false
        return BackupReminder.shouldRemind(
            status = extras.backupStatus,
            nowSec = nowSeconds(),
            snoozedUntilSec = extras.backupSnoozedUntilSec
        )
    }

    /**
     * 最終書き出し日時を画面用の文字列にする。一度も書き出していなければ null。
     *
     * 日時は端末のタイムゾーンで表示する。保存しているのはエポック秒なので、
     * タイムゾーンが変わっても正しい時刻になる。
     */
    private fun formatLastExport(status: BackupStatus): String? {
        if (!status.hasExported) return null
        return runCatching {
            val formatter = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            formatter.format(java.util.Date(status.lastExportedAtSec * 1000))
        }.getOrNull()
    }

    /**
     * バックアップのリマインドを先送りする (Issue #108)。
     *
     * 次に出すまでの期限をメモリに持つ。アプリを再起動すると期限が消えるが、
     * 再起動のたびにバナーが出るのは煩わしいため、期限は SharedPreferences に置く。
     */
    fun snoozeBackupReminder() {
        backupSnoozedUntil.value = BackupReminder.snoozeUntil(nowSeconds())
    }

    /** バックアップ画面を開いたことを記録し、リマインドを閉じる (Issue #108) */
    fun onBackupScreenOpened() {
        backupSnoozedUntil.value = 0L
    }

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

    /** タグ絞り込みを追加/解除する */
    fun toggleTagFilter(tagId: Long) {
        tagFilter.value = tagFilter.value.let { current ->
            if (tagId in current.tagIds) {
                current.copy(tagIds = current.tagIds - tagId)
            } else {
                current.copy(tagIds = current.tagIds + tagId)
            }
        }
    }

    /** 複数タグ指定時の AND/OR を切り替える */
    fun setTagFilterMode(mode: TagFilterMode) {
        tagFilter.value = tagFilter.value.copy(mode = mode)
    }

    /** タグ絞り込みをすべて解除する */
    fun clearTagFilter() {
        tagFilter.value = TagFilterState()
    }

    /** フォルダに付与するタグを、指定内容へ置き換える */
    fun setFolderTags(folderKey: String, tagIds: Set<Long>) {
        viewModelScope.launch {
            repository.setFolderTags(folderKey, tagIds)
        }
    }

    /** タグ名からタグを作成 (既存なら取得) し、フォルダへ付与する */
    fun addTagByName(
        folderKey: String,
        rawName: String,
        currentTagIds: Set<Long>,
        onCreated: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val tag = repository.findOrCreateTag(rawName) ?: return@launch
            repository.setFolderTags(folderKey, currentTagIds + tag.tagId)
            onCreated(tag.tagId)
        }
    }

    /** 検索結果の複数フォルダへ、既存タグをまとめて付与する */
    fun addTagsToFolders(tagIds: Set<Long>, folderKeys: Collection<String>) {
        viewModelScope.launch {
            repository.addTagsToFolders(tagIds, folderKeys)
        }
    }

    /** 未登録のタグ名から新規作成し、複数フォルダへまとめて付与する */
    fun addNewTagToFolders(rawName: String, folderKeys: Collection<String>) {
        if (folderKeys.isEmpty()) return
        viewModelScope.launch {
            val tag = repository.findOrCreateTag(rawName) ?: return@launch
            repository.addTagsToFolders(setOf(tag.tagId), folderKeys)
        }
    }

    /** タグ名を変更する */
    fun renameTag(tagId: Long, rawName: String) {
        viewModelScope.launch {
            repository.renameTag(tagId, rawName)
        }
    }

    /** タグを削除する (フォルダとの紐付けも消える) */
    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            repository.deleteTag(tagId)
        }
    }

    /** 「前回の続き」履歴を消す */
    fun clearResume() {
        lastViewedStore.clear()
    }

    /**
     * フォルダ代表サムネイルの読み込み失敗を受ける (Issue #77)。
     *
     * OPPO/ColorOS では削除済みフォルダの行が MediaStore に残り、走査では消せない。
     * 実際に開けなかったフォルダだけを対象に実体を確認し、無ければ一覧から外す。
     * 同じフォルダで何度も呼ばれないよう、確認済みのキーは記録しておく。
     */
    fun onFolderThumbnailError(folder: FolderUiModel) {
        if (!thumbnailCheckedKeys.add(folder.folderKey)) return
        viewModelScope.launch {
            val removed = runCatching {
                repository.verifyFolderFromThumbnailFailure(folder.folderKey)
            }.getOrDefault(0)
            if (removed > 0) {
                // 消えたフォルダの「前回の続き」が残っていると開けないため、履歴も掃除する
                val resume = lastViewedStore.lastViewed.value
                if (resume?.folderKey == folder.folderKey) {
                    lastViewedStore.clear()
                }
            } else {
                // 読めた場合は次回また失敗したときに再確認できるようにする
                thumbnailCheckedKeys.remove(folder.folderKey)
            }
        }
    }

    /** 列数を変更して永続化する */
    fun setColumnCount(count: Int) {
        viewSettings.setColumnCount(count)
    }

    /**
     * 実体が見つからない画像の件数を読み直す (Issue #80)。
     * 一覧を開いたときと、整理を実行した後に呼ぶ。
     */
    fun refreshStaleCount() {
        viewModelScope.launch {
            val summary = runCatching { repository.getUnreadableFolderSummary() }
                .getOrDefault(emptyList())
            staleImageCount.value = summary.sumOf { it.imageCount }
        }
    }

    /**
     * 実体が見つからない行を DB から整理する (Issue #80)。
     *
     * SD カードが外れている場合などはリポジトリ側の安全条件で中止される。
     * 中止理由は [lastCleanupMessage] に残し、UI で案内する。
     */
    fun cleanupStaleEntries() {
        viewModelScope.launch {
            val result = runCatching { repository.cleanupStaleEntries() }
                .getOrNull()
            lastCleanupMessage.value = when {
                result == null -> "整理に失敗しました"
                result.rejectedReason != null -> result.rejectedReason
                result.folderCount == 0 -> "整理する対象はありませんでした"
                else -> "${result.imageCount} 枚の情報を整理しました"
            }
            refreshStaleCount()
        }
    }

    /** 整理の結果メッセージ (Issue #80)。表示後に [clearCleanupMessage] で消す */
    val lastCleanupMessage = MutableStateFlow<String?>(null)

    /** 整理の結果メッセージを消す */
    fun clearCleanupMessage() {
        lastCleanupMessage.value = null
    }

    /**
     * メディア再スキャンの案内を閉じる (Issue #86)。
     * 閉じた後は、次に手動更新するまで再表示しない。
     */
    fun dismissRescanHint() {
        rescanHintDismissed.value = true
    }

    /**
     * 手動更新を開始したことを記録する (Issue #86)。
     * 閉じていた案内を再表示し、更新結果を改めて確認できるようにする。
     */
    fun onManualRefreshStarted() {
        rescanHintDismissed.value = false
    }

    /** 「前回の続き」カードの表示を切り替えて永続化する (Issue #53) */
    fun setShowResumeCard(show: Boolean) {
        viewSettings.setShowResumeCard(show)
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
        favoriteKeys: Set<String>,
        tagsByFolder: Map<String, List<FolderTagUi>>
    ): List<FolderUiModel> {
        // フォルダごとに代表サムネイルを最大 1 件割り当てる。
        // 代表画像はファイル名の自然順先頭で、フォルダの最新更新日時とは別の日時を持つ (Issue #49)
        val repByFolder = representatives.associateBy({ it.folderKey }, { it })
        return map { row ->
            val representative = repByFolder[row.folderKey]
            FolderUiModel(
                folderKey = row.folderKey,
                volumeName = row.volumeName,
                relativePath = row.relativePath,
                folderName = FolderNames.parse(row.relativePath),
                imageCount = row.imageCount,
                latestModifiedSec = row.latestModifiedSec,
                representativeImageUri = representative?.id,
                representativeModifiedSec = representative?.dateModifiedSec ?: 0L,
                representativeSize = representative?.size ?: 0L,
                isFavorite = row.folderKey in favoriteKeys,
                tags = tagsByFolder[row.folderKey].orEmpty()
            )
        }
    }

    /**
     * タグ絞り込みの判定。複数タグ指定時は UI で明示した AND/OR に従う。
     * タグ未設定のフォルダは、絞り込み中だけ対象外になる。
     */
    private fun matchesTagFilter(folder: FolderUiModel, filter: TagFilterState): Boolean {
        if (!filter.isActive) return true
        val folderTagIds = folder.tags.mapTo(hashSetOf()) { it.tagId }
        return when (filter.mode) {
            TagFilterMode.OR -> filter.tagIds.any { it in folderTagIds }
            TagFilterMode.AND -> folderTagIds.containsAll(filter.tagIds)
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
