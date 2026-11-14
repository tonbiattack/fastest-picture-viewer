package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.folderThumbnailRequest
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.data.TagWithCount
import com.tonbiattack.fastestpictureviewer.repository.MediaRescanHint
import com.tonbiattack.fastestpictureviewer.ui.components.FastScroller
import com.tonbiattack.fastestpictureviewer.ui.components.ScrollToTopButton
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * フォルダ一覧画面。
 *
 * 設計原則: キャッシュ済み一覧を即座に表示し、同期は背景で行う。
 * 同期中も表示中のリストは一切クリアしない。
 * 検索・お気に入り・除外はキャッシュ済みデータに対して適用する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    viewModel: FolderListViewModel,
    accessLevel: MediaAccessLevel,
    onRequestPermission: () -> Unit,
    onManualRefresh: () -> Unit,
    onClearImageCache: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenExcludedFolders: () -> Unit,
    onOpenBackup: () -> Unit,
    onExcludeFolder: (FolderUiModel) -> Unit,
    onSetFolderFavorite: (FolderUiModel, Boolean) -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    onOpenResume: (FolderUiModel, String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    var showTagFilter by remember { mutableStateOf(false) }
    var showTagManagement by remember { mutableStateOf(false) }
    var tagEditorFolder by remember { mutableStateOf<FolderUiModel?>(null) }
    var tagEditorSelected by remember { mutableStateOf(emptySet<Long>()) }
    var bulkAssignTargets by remember { mutableStateOf<List<String>?>(null) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(state.isSearching) }
    var pendingExclude by remember { mutableStateOf<FolderUiModel?>(null) }
    // 検索結果から複数フォルダを選んで一括付与するモード (Issue #46)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    // 見つからない画像の整理の確認ダイアログ (Issue #80)
    var showCleanupDialog by remember { mutableStateOf(false) }

    // 検索が終わったら選択モードも解除する
    LaunchedEffect(state.isSearching) {
        if (!state.isSearching) {
            selectionMode = false
            selectedKeys = emptySet()
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("画像キャッシュを削除") },
            text = { Text("サムネイルと先読みのキャッシュを削除します。画像やフォルダの情報は削除されません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        onClearImageCache()
                    }
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("キャンセル") }
            }
        )
    }

    if (showDisplaySettings) {
        DisplaySettingsDialog(
            columnCount = state.columnCount,
            sortMode = state.sortMode,
            showResumeCard = state.showResumeCard,
            onColumnCountChange = viewModel::setColumnCount,
            onSortModeChange = viewModel::setSortMode,
            onShowResumeCardChange = viewModel::setShowResumeCard,
            onDismiss = { showDisplaySettings = false }
        )
    }

    pendingExclude?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingExclude = null },
            title = { Text("フォルダを除外") },
            text = {
                Text(
                    "「${folder.folderName}」をフォルダ一覧から除外します。" +
                        "元の画像は削除されません。設定の「除外フォルダ」から解除できます。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExclude = null
                        onExcludeFolder(folder)
                    }
                ) { Text("除外") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExclude = null }) { Text("キャンセル") }
            }
        )
    }

    // タグ編集: 選択状態はダイアログ側のローカル集合で保持し、操作のたびに全体を保存する
    tagEditorFolder?.let { target ->
        FolderTagEditorDialog(
            folderName = target.folderName,
            selectedTagIds = tagEditorSelected,
            allTags = state.allTags,
            onToggle = { tagId, add ->
                val next = if (add) tagEditorSelected + tagId else tagEditorSelected - tagId
                tagEditorSelected = next
                viewModel.setFolderTags(target.folderKey, next)
            },
            onCreateAndAdd = { name ->
                viewModel.addTagByName(
                    folderKey = target.folderKey,
                    rawName = name,
                    currentTagIds = tagEditorSelected
                ) { createdId ->
                    // 作成直後に選択済みとして表示する (Room の反映を待たない)
                    tagEditorSelected = tagEditorSelected + createdId
                }
            },
            onDismiss = { tagEditorFolder = null }
        )
    }

    bulkAssignTargets?.let { targets ->
        BulkTagAssignDialog(
            targetCount = targets.size,
            allTags = state.allTags,
            onApply = { tagIds, newTagName ->
                if (newTagName != null) {
                    viewModel.addNewTagToFolders(newTagName, targets)
                } else {
                    viewModel.addTagsToFolders(tagIds, targets)
                }
            },
            onDismiss = { bulkAssignTargets = null }
        )
    }

    if (showTagFilter) {
        TagFilterDialog(
            allTags = state.allTags,
            filter = state.tagFilter,
            onToggle = viewModel::toggleTagFilter,
            onModeChange = viewModel::setTagFilterMode,
            onClear = viewModel::clearTagFilter,
            onDismiss = { showTagFilter = false }
        )
    }

    if (showTagManagement) {
        TagManagementDialog(
            allTags = state.allTags,
            onRename = viewModel::renameTag,
            onDelete = viewModel::deleteTag,
            onDismiss = { showTagManagement = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("フォルダ") },
                    actions = {
                        IconButton(
                            onClick = {
                                if (searchActive) {
                                    searchActive = false
                                    viewModel.clearSearch()
                                } else {
                                    searchActive = true
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "フォルダを検索")
                        }
                        IconButton(onClick = viewModel::toggleFavoritesOnly) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "お気に入りのみ表示",
                                tint = if (state.favoritesOnly) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    // 案内を閉じていても、更新したら再判定する (Issue #86)
                                    viewModel.onManualRefreshStarted()
                                    onManualRefresh()
                                }
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "全件再同期")
                            }
                        }
                        Box {
                            IconButton(onClick = { topMenuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                            }
                            DropdownMenu(
                                expanded = topMenuExpanded,
                                onDismissRequest = { topMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("表示設定") },
                                    onClick = {
                                        topMenuExpanded = false
                                        showDisplaySettings = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("お気に入り画像") },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenFavorites()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("タグで絞り込み") },
                                    onClick = {
                                        topMenuExpanded = false
                                        showTagFilter = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("タグの管理") },
                                    onClick = {
                                        topMenuExpanded = false
                                        showTagManagement = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("除外フォルダ") },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenExcludedFolders()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("画像キャッシュを削除") },
                                    onClick = {
                                        topMenuExpanded = false
                                        showClearCacheDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("バックアップ") },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenBackup()
                                    }
                                )
                            }
                        }
                    }
                )

                if (searchActive || state.isSearching) {
                    SearchField(
                        query = state.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onClear = viewModel::clearSearch
                    )
                }
                // 検索中の一括付与操作。対象件数を常に見えるようにして誤操作を防ぐ (Issue #46)
                if (selectionMode) {
                    BulkAssignBar(
                        selectedCount = selectedKeys.size,
                        resultCount = state.folders.size,
                        onAssignSelected = {
                            val targets = state.folders
                                .map { it.folderKey }
                                .filter { it in selectedKeys }
                            if (targets.isNotEmpty()) bulkAssignTargets = targets
                        },
                        onAssignAll = {
                            val targets = state.folders.map { it.folderKey }
                            if (targets.isNotEmpty()) bulkAssignTargets = targets
                        },
                        onCancel = {
                            selectionMode = false
                            selectedKeys = emptySet()
                        }
                    )
                } else if (state.isSearching) {
                    TextButton(
                        onClick = {
                            selectionMode = true
                            selectedKeys = emptySet()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("複数選択してタグを一括付与")
                    }
                }
                if (state.tagFilter.isActive) {
                    ActiveTagFilterBar(
                        filter = state.tagFilter,
                        tags = state.allTags,
                        onClear = viewModel::clearTagFilter
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // 真の初回フレーム (Room からまだ 1 度も発行されていない) のみローディング表示
                state.isLoadingFirstFrame -> LoadingHint()

                // 初回スキャン中でまだデータが無い
                state.showInitialScanProgress -> InitialScanHint()

                // 並び順ごとのスクロール位置を読み込み中
                !state.isScrollRestored -> LoadingHint()

                // フィルタ後に 0 件
                state.folders.isEmpty() && !state.isSyncing -> EmptyHint(
                    searchQuery = state.searchQuery,
                    favoritesOnly = state.favoritesOnly,
                    hasFolders = state.hasFolders,
                    tagFilterActive = state.tagFilter.isActive
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    val resumeFolder = state.resumeFolder
                    val resumeItemId = state.resumeItemId
                    if (!state.isFiltered && resumeFolder != null && resumeItemId != null) {
                        ResumeFolderCard(
                            folder = resumeFolder,
                            onOpen = {
                                onOpenResume(
                                    resumeFolder,
                                    resumeItemId,
                                    state.resumeItemIndex
                                )
                            },
                            onClear = viewModel::clearResume
                        )
                    }
                    key(state.sortMode, state.isFiltered) {
                        FolderGrid(
                            folders = state.folders,
                            columnCount = state.columnCount,
                            initialIndex = state.initialIndex,
                            initialOffset = state.initialOffset,
                            onScrollPositionChanged = viewModel::onScrollPositionChanged,
                            onFlushScrollPosition = viewModel::flushScrollPosition,
                            onFavoriteToggle = { folder ->
                                onSetFolderFavorite(folder, !folder.isFavorite)
                            },
                            onExcludeRequest = { pendingExclude = it },
                            onOpenFolder = onOpenFolder,
                            onThumbnailError = viewModel::onFolderThumbnailError,
                            selectionMode = selectionMode,
                            selectedKeys = selectedKeys,
                            onToggleSelection = { key ->
                                selectedKeys = if (key in selectedKeys) {
                                    selectedKeys - key
                                } else {
                                    selectedKeys + key
                                }
                            },
                            onEditTags = { folder ->
                                tagEditorSelected = folder.tags.mapTo(hashSetOf()) { it.tagId }
                                tagEditorFolder = folder
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }

            // FR-07: 権限不足でも既存キャッシュは表示し、案内だけ重ねる
            if (accessLevel == MediaAccessLevel.Denied) {
                PermissionBanner(
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }

            // 同期失敗メッセージは一覧の上に重ねて表示 (既存一覧は消さない)
            state.errorMessage?.let { message ->
                SyncErrorBanner(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                )
            }

            // 実体が見つからない画像の案内 (Issue #80)。
            // 件数だけを出し、フォルダ名などの内容は表示しない
            if (state.staleImageCount > 0L) {
                StaleMediaBanner(
                    imageCount = state.staleImageCount,
                    onCleanup = { showCleanupDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                )
            }

            // メディア再スキャンの案内 (Issue #86)。
            // 全件走査しても変化が無いときだけ出す。既存バナーと重ならないよう上に積む
            if (state.suggestRescan) {
                MediaRescanBanner(
                    onDismiss = viewModel::dismissRescanHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                        .fillMaxWidth()
                )
            }
        }
    }

    // 整理の実行前に、何が起きるかを明示して確認する (Issue #80)
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("見つからない画像の情報を整理") },
            text = {
                Text(
                    "${state.staleImageCount} 枚の画像が見つかりません。\n\n" +
                        "端末から削除された画像の「登録情報」だけを片付けます。" +
                        "写真そのものは削除しません。\n\n" +
                        "SD カードが外れている場合は実行されません。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupDialog = false
                        viewModel.cleanupStaleEntries()
                    }
                ) { Text("整理する") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) { Text("やめる") }
            }
        )
    }

    // 整理の結果 (中止理由を含む) を案内する
    val cleanupMessage by viewModel.lastCleanupMessage.collectAsStateWithLifecycle()
    cleanupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearCleanupMessage,
            title = { Text("整理の結果") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearCleanupMessage) { Text("閉じる") }
            }
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text("フォルダ名で検索") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "検索をクリア")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
}

@Composable
private fun DisplaySettingsDialog(
    columnCount: Int,
    sortMode: FolderSortMode,
    showResumeCard: Boolean,
    onColumnCountChange: (Int) -> Unit,
    onSortModeChange: (FolderSortMode) -> Unit,
    onShowResumeCardChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表示設定") },
        text = {
            // 端末の画面が小さい場合でも設定項目が切れないよう縦スクロール可能にする (Issue #53)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("フォルダ一覧の列数", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (count in 1..4) {
                        FilterChip(
                            selected = columnCount == count,
                            onClick = { onColumnCountChange(count) },
                            label = { Text("${count}列") }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("フォルダ一覧の並び順", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sortMode == FolderSortMode.LATEST,
                        onClick = { onSortModeChange(FolderSortMode.LATEST) },
                        label = { Text("更新日時順") }
                    )
                    FilterChip(
                        selected = sortMode == FolderSortMode.RECENT,
                        onClick = { onSortModeChange(FolderSortMode.RECENT) },
                        label = { Text("最近閲覧順") }
                    )
                }

                // 前回の続きカードの表示切り替え (Issue #53)
                // スイッチだけでなく行全体をタップできるようにして押しやすくする
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = showResumeCard,
                            role = Role.Switch,
                            onValueChange = onShowResumeCardChange
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("「前回の続き」を表示", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "最後に見たフォルダ・画像のカードを一覧の上に表示します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showResumeCard,
                        // 行側で切り替えるため、スイッチ自身は表示専用にする
                        onCheckedChange = null
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

@Composable
private fun FolderGrid(
    folders: List<FolderUiModel>,
    columnCount: Int,
    initialIndex: Int,
    initialOffset: Int,
    onScrollPositionChanged: (Int, Int) -> Unit,
    onFlushScrollPosition: () -> Unit,
    onFavoriteToggle: (FolderUiModel) -> Unit,
    onExcludeRequest: (FolderUiModel) -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
    onEditTags: (FolderUiModel) -> Unit,
    /** 代表サムネイルの読み込みに失敗したフォルダの通知 (Issue #77) */
    onThumbnailError: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialOffset
    )

    // Issue #28: スクロール位置を並び順ごとに ViewModel 経由で保存する
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) -> onScrollPositionChanged(index, offset) }
    }
    DisposableEffect(gridState) {
        onDispose { onFlushScrollPosition() }
    }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            // 右端の FastScroller と重ならないよう終端に余白を確保する
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(folders, key = { it.folderKey }, contentType = { "folder" }) { folder ->
                FolderGridCell(
                    folder = folder,
                    selectionMode = selectionMode,
                    selected = folder.folderKey in selectedKeys,
                    onClick = {
                        if (selectionMode) {
                            onToggleSelection(folder.folderKey)
                        } else {
                            onOpenFolder(folder)
                        }
                    },
                    onFavoriteToggle = { onFavoriteToggle(folder) },
                    onExcludeRequest = { onExcludeRequest(folder) },
                    onEditTags = { onEditTags(folder) },
                    onThumbnailError = { onThumbnailError(folder) },
                    onLongClick = {
                        if (selectionMode) {
                            onToggleSelection(folder.folderKey)
                        }
                    }
                )
            }
        }
        FastScroller(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
        // ワンタップで先頭へ戻る (Issue #76)。
        // FastScroller (右端 28dp) と重ならないよう右に余白を取る
        ScrollToTopButton(
            state = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = 16.dp)
        )
    }
}

@Composable
private fun ResumeFolderCard(
    folder: FolderUiModel,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(folder.representativeImageUri, folder.representativeModifiedSec, folder.representativeSize) {
        folder.representativeImageUri?.let {
            folderThumbnailRequest(
                context = context,
                uri = it,
                modifiedSec = folder.representativeModifiedSec,
                size = folder.representativeSize
            )
        }
    }
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = folder.folderName,
                    contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.ic_broken_image),
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "前回の続き",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = folder.folderName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${folder.imageCount} 枚",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "前回の続きをクリア")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGridCell(
    folder: FolderUiModel,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onExcludeRequest: () -> Unit,
    onEditTags: () -> Unit,
    onLongClick: () -> Unit,
    /** 代表サムネイルの読み込みに失敗したときの通知 (Issue #77) */
    onThumbnailError: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val request = remember(folder.representativeImageUri, folder.representativeModifiedSec, folder.representativeSize) {
        folder.representativeImageUri?.let {
            folderThumbnailRequest(
                context = context,
                uri = it,
                modifiedSec = folder.representativeModifiedSec,
                size = folder.representativeSize
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (selectionMode) {
                            // 選択モード中は長押しも選択トグルとして扱う
                            onLongClick()
                        } else {
                            // 長押しでメニュー。スクロール中の誤表示は combinedClickable が抑止する
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuExpanded = true
                        }
                    },
                    onClickLabel = if (selectionMode) "選択を切り替え" else "フォルダを開く",
                    onLongClickLabel = "フォルダメニュー"
                )
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = folder.folderName,
                        contentScale = ContentScale.Crop,
                        error = painterResource(R.drawable.ic_broken_image),
                        // 読めなかった代表画像を通知し、フォルダごと非表示にできるようにする (Issue #77)
                        onError = { onThumbnailError() },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 右上の星を直接タップしてお気に入りを切り替える (タッチ領域48dp)
                    IconButton(
                        onClick = onFavoriteToggle,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(48.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (folder.isFavorite) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = "フォルダのお気に入りから解除",
                                    tint = Color(0xFFFFC107),
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    painterResource(R.drawable.ic_star_outline),
                                    contentDescription = "フォルダのお気に入りに追加",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = folder.folderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${folder.imageCount} 枚",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatShortDate(folder.latestModifiedSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (folder.tags.isNotEmpty()) {
                        // タグは最大 2 件だけ表示し、高さを増やしすぎない (Issue #46)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            folder.tags.take(2).forEach { tag ->
                                FolderTagChip(name = tag.displayName)
                            }
                            if (folder.tags.size > 2) {
                                Text(
                                    "+${folder.tags.size - 2}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 選択モード中はチェック状態を画像左上に重ねる
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "選択済み",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "未選択",
                        tint = Color.Transparent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (folder.isFavorite) "お気に入りから解除"
                        else "お気に入りに追加"
                    )
                },
                onClick = {
                    menuExpanded = false
                    onFavoriteToggle()
                }
            )
            DropdownMenuItem(
                text = { Text("タグを編集") },
                onClick = {
                    menuExpanded = false
                    onEditTags()
                }
            )
            DropdownMenuItem(
                text = { Text("このフォルダを除外") },
                onClick = {
                    menuExpanded = false
                    onExcludeRequest()
                }
            )
        }
    }
}

/** フォルダカードに出すタグの小さなバッジ */
@Composable
private fun FolderTagChip(name: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 0.dp
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 検索結果への一括付与バー。
 * 「選択した N 件」と「検索結果すべて N 件」を別ボタンに分け、対象件数を明示する。
 */
@Composable
private fun BulkAssignBar(
    selectedCount: Int,
    resultCount: Int,
    onAssignSelected: () -> Unit,
    onAssignAll: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "選択中 ${selectedCount} 件 / 検索結果 ${resultCount} 件",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onCancel) { Text("やめる") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = selectedCount > 0,
                    onClick = onAssignSelected
                ) { Text("選択した ${selectedCount} 件にタグ付与") }
                TextButton(
                    enabled = resultCount > 0,
                    onClick = onAssignAll
                ) { Text("検索結果すべて ${resultCount} 件に付与") }
            }
        }
    }
}

/** 適用中のタグ絞り込みを表示し、解除できるようにする */
@Composable
private fun ActiveTagFilterBar(
    filter: TagFilterState,
    tags: List<TagWithCount>,
    onClear: () -> Unit
) {
    val names = tags.filter { it.tagId in filter.tagIds }.map { it.displayName }
    val label = names.joinToString(if (filter.mode == TagFilterMode.AND) " かつ " else " または ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (label.isBlank()) "タグで絞り込み中" else "タグ: $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear) { Text("解除") }
        }
    }
}

@Composable
private fun LoadingHint() {
    HintColumn(icon = { CircularProgressIndicator() }, text = "読み込み中…")
}

@Composable
private fun InitialScanHint() {
    HintColumn(icon = { CircularProgressIndicator() }, text = "初回スキャンをしています…")
}

@Composable
private fun EmptyHint(
    searchQuery: String,
    favoritesOnly: Boolean,
    hasFolders: Boolean,
    tagFilterActive: Boolean
) {
    val message = when {
        searchQuery.isNotBlank() -> "一致するフォルダがありません"
        tagFilterActive && hasFolders -> "タグに一致するフォルダがありません"
        favoritesOnly && hasFolders -> "お気に入りのフォルダがありません"
        hasFolders -> "表示できるフォルダがありません"
        else -> "画像が見つかりませんでした"
    }
    HintColumn(text = message)
}

@Composable
private fun HintColumn(icon: @Composable () -> Unit = {}, text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 権限不足時に表示する案内。既存一覧 (キャッシュ) は消さず、要求アクションだけを提示する */
@Composable
private fun PermissionBanner(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "画像の読み取り権限がありません。既存のキャッシュを表示しています。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRequestPermission) {
                Text("許可する")
            }
        }
    }
}

@Composable
private fun SyncErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

private fun formatShortDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochSeconds * 1000))
}

/**
 * メディア再スキャンの案内 (Issue #86)。
 *
 * 全件走査しても新しい写真が見つからなかったときに出す。
 * フォルダ名やファイル名は含めない。「閉じる」で消せる。
 */
@Composable
private fun MediaRescanBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "新しい写真が見つかりませんでした",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = MediaRescanHint.MESSAGE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = MediaRescanHint.DETAIL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

/**
 * 実体が見つからない画像の案内 (Issue #80)。
 *
 * 件数だけを表示し、フォルダ名やファイル名は出さない。
 * 「整理する」で DB の登録情報だけを片付ける (写真そのものは消さない)。
 */
@Composable
private fun StaleMediaBanner(
    imageCount: Long,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "見つからない画像が ${imageCount} 枚あります",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "端末から削除された画像の登録情報が残っています。写真そのものは削除されません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCleanup) { Text("整理する") }
            }
        }
    }
}
