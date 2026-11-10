package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.toImageRequest
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.folderName.ifBlank { "読み込み中…" })
                        if (state.isSyncing) {
                            Text(
                                "更新チェック中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                else -> {
                    val gridState = rememberLazyGridState(viewModel.initialScrollIndex)
                    LaunchedEffect(gridState) {
                        snapshotFlow { gridState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect { viewModel.saveScrollIndex(it) }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
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
                            AsyncImage(
                                model = request,
                                contentDescription = image.displayName,
                                contentScale = ContentScale.Crop,
                                error = painterResource(R.drawable.ic_broken_image),
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(1.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .clickable { onOpenViewer(state.folderKey ?: "", index) }
                            )
                        }
                    }
                }
            }
        }
    }
}
