package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.folderThumbnailRequest
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.ui.components.FastScroller
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
    onOpenExcludedFolders: () -> Unit,
    onExcludeFolder: (FolderUiModel) -> Unit,
    onSetFolderFavorite: (FolderUiModel, Boolean) -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(state.isSearching) }
    var pendingExclude by remember { mutableStateOf<FolderUiModel?>(null) }

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
            onColumnCountChange = viewModel::setColumnCount,
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
                            IconButton(onClick = onManualRefresh) {
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

                // フィルタ後に 0 件
                state.folders.isEmpty() && !state.isSyncing -> EmptyHint(
                    searchQuery = state.searchQuery,
                    favoritesOnly = state.favoritesOnly,
                    hasFolders = state.hasFolders
                )

                else -> FolderGrid(
                    folders = state.folders,
                    columnCount = state.columnCount,
                    // プロセス再生成後もスクロール基準を SavedStateHandle から復元する
                    initialScrollIndex = viewModel.initialScrollIndex(),
                    onScrollIndexChange = viewModel::saveScrollIndex,
                    onFavoriteToggle = { folder ->
                        onSetFolderFavorite(folder, !folder.isFavorite)
                    },
                    onExcludeRequest = { pendingExclude = it },
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier.fillMaxSize()
                )
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
        }
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
    onColumnCountChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表示設定") },
        text = {
            Column {
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
    initialScrollIndex: Int,
    onScrollIndexChange: (Int) -> Unit,
    onFavoriteToggle: (FolderUiModel) -> Unit,
    onExcludeRequest: (FolderUiModel) -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialScrollIndex)

    // Issue #4: スクロール位置をプロセス死に耐える形で ViewModel 経由で記録する
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onScrollIndexChange(it) }
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
                    onClick = { onOpenFolder(folder) },
                    onFavoriteToggle = { onFavoriteToggle(folder) },
                    onExcludeRequest = { onExcludeRequest(folder) }
                )
            }
        }
        FastScroller(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGridCell(
    folder: FolderUiModel,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onExcludeRequest: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val request = remember(folder.representativeImageUri, folder.latestModifiedSec) {
        folder.representativeImageUri?.let {
            folderThumbnailRequest(context, it, folder.latestModifiedSec)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        // 長押しでメニュー。スクロール中の誤表示は combinedClickable が抑止する
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    },
                    onClickLabel = "フォルダを開く",
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
                        modifier = Modifier.fillMaxSize()
                    )

                    if (folder.isFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "お気に入り",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .size(20.dp)
                        )
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
                text = { Text("このフォルダを除外") },
                onClick = {
                    menuExpanded = false
                    onExcludeRequest()
                }
            )
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
    hasFolders: Boolean
) {
    val message = when {
        searchQuery.isNotBlank() -> "一致するフォルダがありません"
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
