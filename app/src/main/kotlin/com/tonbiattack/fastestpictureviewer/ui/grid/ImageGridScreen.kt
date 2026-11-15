package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.toImageRequest
import com.tonbiattack.fastestpictureviewer.ui.components.CompactSearchBar
import com.tonbiattack.fastestpictureviewer.ui.components.FastScroller
import kotlinx.coroutines.flow.distinctUntilChanged
import android.content.res.Configuration

/**
 * フォルダ内の画像をグリッド表示する画面。
 * 大量画像に耐えるよう LazyVerticalGrid で遅延描画し、表示中セルのコールバックで
 * スクロール基準位置を SavedStateHandle に書き込む (Issue #4)。
 *
 * 列数はフォルダ一覧とは独立して 2〜6 列から選択できる (Issue #34)。
 * 詳細画面から戻った際は最後に見ていた画像 ID を基準に位置を復元する (Issue #35)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGridScreen(
    viewModel: ImageGridViewModel,
    onBack: () -> Unit,
    onOpenViewer: (folderKey: String, index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val containerSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showFullFolderName by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(state.isSearching) }
    // 検索バーを開いた回数。増えたときだけフォーカスとキーボードを出す (Issue #100)
    var searchFocusRequest by remember { mutableIntStateOf(0) }
    val scrollAnchor = remember { GridScrollAnchor() }
    // ピンチ操作中の一時的な列数。null なら保存済みの列数を使う (Issue #45)
    var pinchColumns by remember { mutableStateOf<Int?>(null) }

    // 画面の向きに応じて、その向きで保存した列数を適用する (Issue #44)
    LaunchedEffect(isLandscape) {
        viewModel.onOrientationChanged(isLandscape)
    }

    if (showFullFolderName) {
        AlertDialog(
            onDismissRequest = { showFullFolderName = false },
            title = { Text("フォルダ名") },
            text = { Text(state.folderName) },
            confirmButton = {
                TextButton(onClick = { showFullFolderName = false }) { Text("閉じる") }
            }
        )
    }

    if (showDisplaySettings) {
        ImageGridDisplaySettingsDialog(
            columnCount = state.columnCount,
            portraitColumnCount = state.portraitColumnCount,
            landscapeColumnCount = state.landscapeColumnCount,
            isLandscape = isLandscape,
            onColumnCountChange = viewModel::setColumnCount,
            onDismiss = { showDisplaySettings = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        // 長いフォルダ名でもヘッダーの高さが増えないよう1行固定にする
                        Text(
                            text = state.folderName.ifBlank { "読み込み中…" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                enabled = state.folderName.isNotBlank()
                            ) { showFullFolderName = true }
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (searchActive || state.isSearching) {
                                    searchActive = false
                                    viewModel.clearSearch()
                                } else {
                                    searchActive = true
                                    searchFocusRequest++
                                }
                            }
                        ) {
                            // 検索中は閉じるアイコンにして、同じ位置で解除できることを示す (Issue #100)
                            if (searchActive || state.isSearching) {
                                Icon(Icons.Filled.Close, contentDescription = "検索を閉じる")
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = "画像を検索")
                            }
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
                                    .size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("表示設定") },
                                    onClick = {
                                        menuExpanded = false
                                        showDisplaySettings = true
                                    }
                                )
                            }
                        }
                    }
                )
                if (searchActive || state.isSearching) {
                    // 1 行のコンパクトな検索バー。一覧の表示領域を削らない (Issue #100)
                    CompactSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onClear = viewModel::clearSearch,
                        placeholder = "ファイル名で検索",
                        focusRequestKey = searchFocusRequest,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                state.allImages.isEmpty() && state.isSyncing -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                    )
                }
                state.allImages.isEmpty() -> {
                    Text(
                        "画像がありません",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                !state.isScrollRestored -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                    )
                }
                state.images.isEmpty() -> {
                    Text(
                        text = if (state.isFiltered) {
                            "一致する画像がありません"
                        } else {
                            "画像がありません"
                        },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    val target = if (state.isFiltered) null else state.scrollTarget
                    // 列数変更時は、変更前に見えていた画像をアンカーにして再生成する
                    val anchorIndex = if (state.isFiltered) {
                        null
                    } else {
                        scrollAnchor.itemId
                            ?.let { id -> state.images.indexOfFirst { it.id == id } }
                            ?.takeIf { it >= 0 }
                    }
                    val initialIndex = if (state.isFiltered) {
                        0
                    } else {
                        target?.let {
                            ScrollPositionResolver.resolve(
                                images = state.images,
                                anchorItemId = it.itemId,
                                fallbackIndex = it.fallbackIndex
                            )
                        } ?: anchorIndex ?: state.initialIndex
                    }
                    val initialOffset = when {
                        state.isFiltered -> 0
                        target != null -> 0
                        else -> scrollAnchor.offsetPx
                    }
                    // ピンチ中は一時的な列数を使い、離したら保存値へ戻る (Issue #45)
                    val effectiveColumns = pinchColumns ?: state.columnCount
                    // ピンチ中に列数が細かく変わってもデコードサイズを作り直さないよう、
                    // サムネイルのデコードサイズは保存済みの列数を基準にする (メモリ・IO 保護)
                    val cellSizePx = (containerSize.width / state.columnCount).takeIf { it > 0 }
                    val starMetrics = remember(effectiveColumns) {
                        ImageGridStarMetrics.forColumns(effectiveColumns)
                    }

                    // 列数変更・詳細からの復帰時は、保存位置ジャンプのちらつきを避けるため
                    // グリッドState自体を復元先インデックスで作り直す。
                    // ピンチ中の一時列数は key に含めない (ジェスチャー中に State を作り直さない)。
                    // LazyGrid はアイテムキーを基準に列数変更後も先頭の画像を維持する。
                    key(state.columnCount, state.returnRequestId, state.isFiltered) {
                        val gridState = rememberLazyGridState(
                            initialFirstVisibleItemIndex = initialIndex,
                            initialFirstVisibleItemScrollOffset = initialOffset
                        )
                        LaunchedEffect(gridState) {
                            snapshotFlow {
                                gridState.firstVisibleItemIndex to
                                    gridState.firstVisibleItemScrollOffset
                            }
                                .distinctUntilChanged()
                                .collect { (first, offset) ->
                                    if (!state.isFiltered) {
                                        val last = gridState.layoutInfo.visibleItemsInfo
                                            .lastOrNull()?.index ?: first
                                        scrollAnchor.itemId =
                                            state.images.getOrNull(first)?.id
                                        scrollAnchor.offsetPx = offset
                                        viewModel.onVisibleRangeChanged(first, last, offset)
                                    }
                                }
                        }
                        // 検索・お気に入り絞り込みを変えたら結果の先頭から表示する
                        LaunchedEffect(state.searchQuery, state.favoritesOnly) {
                            if (state.isFiltered) gridState.scrollToItem(0)
                        }
                        LaunchedEffect(state.scrollTarget) {
                            if (!state.isFiltered) {
                                state.scrollTarget?.let {
                                    viewModel.consumeScrollTarget(it.requestId)
                                }
                            }
                        }
                        DisposableEffect(gridState) {
                            onDispose { viewModel.flushScrollPosition() }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // 2本指のピンチで一時的に列数を変える (Issue #45)。
                                // グリッドの外側で検出し、列数の変化で再構成されても途切れないようにする
                                .pinchToColumns(
                                    baseColumns = state.columnCount,
                                    onPreviewChange = { columns -> pinchColumns = columns }
                                )
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(effectiveColumns),
                                state = gridState,
                                // 右端の FastScroller と重ならないよう終端に余白を確保する
                                contentPadding = PaddingValues(end = 20.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(
                                    state.images,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> "imageCell" }
                                ) { index, image ->
                                    // 更新日時とサイズをキャッシュキーに含め、上書きされた画像を再読込する。
                                    // 列数に応じたサイズでデコードし、大量画像でもメモリ使用量を抑える。
                                    val request = remember(
                                        image.id,
                                        image.dateModifiedSec,
                                        image.size,
                                        cellSizePx
                                    ) {
                                        if (cellSizePx != null) {
                                            image.toImageRequest(context, cellSizePx, cellSizePx)
                                        } else {
                                            image.toImageRequest(context)
                                        }
                                    }
                                    val isFavorite = image.id in state.favoriteIds
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .padding(1.dp)
                                    ) {
                                        AsyncImage(
                                            model = request,
                                            contentDescription = image.displayName,
                                            contentScale = ContentScale.Crop,
                                            error = painterResource(R.drawable.ic_broken_image),
                                            // 読めない画像を通知し、フォルダごと非表示にできるようにする (Issue #77)
                                            onError = { viewModel.onImageLoadError(image) },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(2.dp))
                                                .clickable { onOpenViewer(state.folderKey ?: "", index) }
                                        )
                                        // 右上の星でお気に入りを直接切り替える。
                                        // 6列などセルが小さい場合は星を縮小して画像を隠しすぎないようにする。
                                        IconButton(
                                            onClick = {
                                                viewModel.toggleFavorite(image.id, isFavorite)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(starMetrics.buttonSize)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(starMetrics.circleSize)
                                                    .background(
                                                        Color.Black.copy(alpha = 0.35f),
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isFavorite) {
                                                    Icon(
                                                        Icons.Filled.Star,
                                                        contentDescription = "画像のお気に入りから解除",
                                                        tint = Color(0xFFFFC107),
                                                        modifier = Modifier.size(starMetrics.iconSize)
                                                    )
                                                } else {
                                                    Icon(
                                                        painterResource(R.drawable.ic_star_outline),
                                                        contentDescription = "画像のお気に入りに追加",
                                                        tint = Color.White.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(starMetrics.iconSize)
                                                    )
                                                }
                                            }
                                        }
                                    }
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
                }
            }
        }
    }
}

@Composable
private fun ImageGridDisplaySettingsDialog(
    columnCount: Int,
    portraitColumnCount: Int,
    landscapeColumnCount: Int,
    isLandscape: Boolean,
    onColumnCountChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表示設定") },
        text = {
            Column {
                Text(
                    text = if (isLandscape) "画像一覧の列数 (横向き)" else "画像一覧の列数 (縦向き)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (count in 2..4) {
                        ColumnCountChip(
                            count = count,
                            selected = columnCount == count,
                            onClick = { onColumnCountChange(count) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (count in 5..6) {
                        ColumnCountChip(
                            count = count,
                            selected = columnCount == count,
                            onClick = { onColumnCountChange(count) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "2〜6列から選択できます。フォルダ一覧の列数設定とは別に、" +
                        "縦向き・横向きそれぞれで保存されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "現在の設定: 縦 $portraitColumnCount 列 / 横 $landscapeColumnCount 列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "写真一覧では2本指のピンチでも一時的に列数を変えられます (指を離すと元に戻ります)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

@Composable
private fun ColumnCountChip(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("${count}列") }
    )
}

/** 列数変更時に現在位置を引き継ぐための、Composition を再描画しないアンカー */
private class GridScrollAnchor(
    var itemId: String? = null,
    var offsetPx: Int = 0
)

/** 列数に応じたお気に入り星のサイズ。小さいセルでは画像を隠しすぎないよう縮小する */
private data class ImageGridStarMetrics(
    val buttonSize: Dp,
    val circleSize: Dp,
    val iconSize: Dp
) {
    companion object {
        fun forColumns(columns: Int): ImageGridStarMetrics = when {
            columns >= 6 -> ImageGridStarMetrics(34.dp, 20.dp, 13.dp)
            columns == 5 -> ImageGridStarMetrics(38.dp, 22.dp, 15.dp)
            columns == 4 -> ImageGridStarMetrics(42.dp, 24.dp, 16.dp)
            else -> ImageGridStarMetrics(48.dp, 26.dp, 17.dp)
        }
    }
}
