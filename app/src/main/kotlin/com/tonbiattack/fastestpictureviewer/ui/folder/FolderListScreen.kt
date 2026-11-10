package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.folderThumbnailRequest
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** フォルダ一覧画面。
 *
 * 設計原則: キャッシュ済み一覧を即座に表示し、同期は背景で行う。
 * 同期中も表示中のリストは一切クリアしない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    viewModel: FolderListViewModel,
    accessLevel: MediaAccessLevel,
    onRequestPermission: () -> Unit,
    onManualRefresh: () -> Unit,
    onClearImageCache: () -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    var showClearCacheDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("フォルダ") },
                actions = {
                    // 手動更新。実行中はインジケータに置き換える (連打防止)
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(24.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        IconButton(onClick = { showClearCacheDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "画像キャッシュを削除")
                        }
                        IconButton(onClick = onManualRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "全件再同期")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // FR-07: 権限不足の案内。削除扱いにせず、既存キャッシュはそのまま表示する
            if (accessLevel == MediaAccessLevel.Denied) {
                PermissionBanner(
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            } else {
                when {
                    // 真の初回フレーム (Room からまだ 1 度も発行されていない) のみローディング表示
                    state.isLoadingFirstFrame -> LoadingHint()

                    // 初回スキャン中でまだデータが無い
                    state.showInitialScanProgress -> InitialScanHint()

                    // キャッシュが全く無い (走査完了済み・画像 0 件)
                    state.folders.isEmpty() && !state.isSyncing -> EmptyHint()

                    else -> FolderList(
                        folders = state.folders,
                        // プロセス再生成後もスクロール基準を SavedStateHandle から復元する
                        initialScrollIndex = viewModel.initialScrollIndex(),
                        onScrollIndexChange = viewModel::saveScrollIndex,
                        onOpenFolder = onOpenFolder,
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
private fun FolderList(
    folders: List<FolderUiModel>,
    initialScrollIndex: Int,
    onScrollIndexChange: (Int) -> Unit,
    onOpenFolder: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState(initialScrollIndex)

    // Issue #4: スクロール位置をプロセス死に耐える形で ViewModel 経由で記録する
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onScrollIndexChange(it) }
    }

    // リスト状態は back stack の保存領域に自動保存されるため、戻る操作で初期化されない
    LazyColumn(modifier = modifier, state = listState) {
        items(folders, key = { it.folderKey }, contentType = { "folder" }) { folder ->
            FolderRow(folder = folder, onClick = { onOpenFolder(folder) })
        }
    }
}

@Composable
private fun FolderRow(folder: FolderUiModel, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(folder.representativeImageUri, folder.latestModifiedSec) {
        folder.representativeImageUri?.let {
            folderThumbnailRequest(context, it, folder.latestModifiedSec)
        }
    }

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 代表サムネイル。Coil が縮小デコードするため元画像を RAM で丸ごと保持しない
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.ic_broken_image),
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.folderName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDate(folder.latestModifiedSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${folder.imageCount} 枚",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun EmptyHint() {
    HintColumn(text = "画像が見つかりませんでした")
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
private fun SyncErrorBanner(message: String, modifier: Modifier = Modifier) {    Surface(
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

private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochSeconds * 1000))
}
