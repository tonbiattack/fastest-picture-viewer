package com.tonbiattack.fastestpictureviewer.ui.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.tonbiattack.fastestpictureviewer.data.ImageItem

/**
 * お気に入り画像の一覧画面。
 *
 * - フォルダ別: フォルダごとの登録枚数を確認し、そのフォルダのお気に入りだけを開く
 * - 全件: 通常のフォルダ一覧と同じフォルダ順で連結し、各フォルダ内は自然順
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteImagesScreen(
    viewModel: FavoriteImagesViewModel,
    onBack: () -> Unit,
    onOpenFavoriteImage: (folderKey: String?, index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val selected = state.selectedFolderKey

    BackHandler(enabled = selected != null) {
        viewModel.clearSelection()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.selectedFolderName ?: "お気に入り画像",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selected != null) viewModel.clearSelection() else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (selected == null) {
                        TextButton(
                            onClick = {
                                val next = if (state.mode == FavoriteViewMode.FOLDERS) {
                                    FavoriteViewMode.ALL
                                } else {
                                    FavoriteViewMode.FOLDERS
                                }
                                viewModel.setMode(next)
                            }
                        ) {
                            Text(
                                if (state.mode == FavoriteViewMode.FOLDERS) "全件表示" else "フォルダ別"
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
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                state.groups.isEmpty() -> Text(
                    "お気に入り画像がありません",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                selected != null -> SelectedFolderGrid(
                    images = state.selectedImages,
                    onOpen = { index -> onOpenFavoriteImage(selected, index) },
                    onRemove = viewModel::removeFavorite
                )

                state.mode == FavoriteViewMode.FOLDERS -> FavoriteFolderGrid(
                    groups = state.groups,
                    onSelect = viewModel::selectFolder
                )

                else -> FavoriteAllGrid(
                    groups = state.groups,
                    allIndexById = state.allIndexById,
                    onOpen = { itemId ->
                        onOpenFavoriteImage(null, state.allIndexById[itemId] ?: 0)
                    },
                    onRemove = viewModel::removeFavorite
                )
            }
        }
    }
}

@Composable
private fun FavoriteFolderGrid(
    groups: List<FavoriteFolderGroup>,
    onSelect: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(groups, key = { it.folderKey }, contentType = { "favoriteFolder" }) { group ->
            FavoriteFolderCard(group = group, onClick = { onSelect(group.folderKey) })
        }
    }
}

@Composable
private fun FavoriteFolderCard(
    group: FavoriteFolderGroup,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val representative = group.representativeImage
    val request = remember(representative?.id, representative?.dateModifiedSec, representative?.size) {
        representative?.toImageRequest(context)
    }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = group.folderName,
                    contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.ic_broken_image),
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    group.folderName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${group.imageCount} 枚",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SelectedFolderGrid(
    images: List<ImageItem>,
    onOpen: (Int) -> Unit,
    onRemove: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(images, key = { _, item -> item.id }, contentType = { _, _ -> "favoriteImage" }) { index, image ->
            FavoriteImageCell(
                image = image,
                onClick = { onOpen(index) },
                onRemove = { onRemove(image.id) }
            )
        }
    }
}

@Composable
private fun FavoriteAllGrid(
    groups: List<FavoriteFolderGroup>,
    allIndexById: Map<String, Int>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        groups.forEach { group ->
            item(
                key = "header:${group.folderKey}",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.folderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${group.imageCount} 枚",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(group.images, key = { it.id }, contentType = { "favoriteImage" }) { image ->
                FavoriteImageCell(
                    image = image,
                    onClick = { onOpen(image.id) },
                    onRemove = { onRemove(image.id) }
                )
            }
        }
    }
}

@Composable
private fun FavoriteImageCell(
    image: ImageItem,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(image.id, image.dateModifiedSec, image.size) {
        image.toImageRequest(context)
    }
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
                .clickable(onClick = onClick)
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "画像のお気に入りから解除",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}
