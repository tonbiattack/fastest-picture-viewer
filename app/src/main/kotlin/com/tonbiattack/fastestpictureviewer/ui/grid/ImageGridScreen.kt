package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.toImageRequest
import com.tonbiattack.fastestpictureviewer.ui.components.FastScroller
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * フォルダ内の画像をグリッド表示する画面。
 * 大量画像に耐えるよう LazyVerticalGrid で遅延描画し、表示中セルのコールバックで
 * スクロール基準位置を SavedStateHandle に書き込む (Issue #4)。
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
    var showFullFolderName by remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = modifier,
        topBar = {
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
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
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
            when {
                state.images.isEmpty() && state.isSyncing -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                    )
                }
                state.images.isEmpty() -> {
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
                else -> {
                    val gridState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = state.initialIndex,
                        initialFirstVisibleItemScrollOffset = state.initialOffset
                    )
                    LaunchedEffect(gridState) {
                        snapshotFlow {
                            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                        }
                            .distinctUntilChanged()
                            .collect { (index, offset) ->
                                viewModel.onScrollPositionChanged(index, offset)
                            }
                    }
                    DisposableEffect(gridState) {
                        onDispose { viewModel.flushScrollPosition() }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
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
                                // 更新日時とサイズをキャッシュキーに含め、上書きされた画像を再読込する
                                val request = remember(image.id, image.dateModifiedSec, image.size) {
                                    image.toImageRequest(context)
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
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(2.dp))
                                            .clickable { onOpenViewer(state.folderKey ?: "", index) }
                                    )
                                    // 右上の星でお気に入りを直接切り替える
                                    IconButton(
                                        onClick = { viewModel.toggleFavorite(image.id, isFavorite) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(48.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
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
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            } else {
                                                Icon(
                                                    painterResource(R.drawable.ic_star_outline),
                                                    contentDescription = "画像のお気に入りに追加",
                                                    tint = Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(17.dp)
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
