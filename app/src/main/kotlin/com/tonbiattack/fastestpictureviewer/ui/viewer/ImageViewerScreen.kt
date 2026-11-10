package com.tonbiattack.fastestpictureviewer.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import com.tonbiattack.fastestpictureviewer.R
import com.tonbiattack.fastestpictureviewer.core.image.toImageRequest
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 画像詳細画面。
 *
 * - HorizontalPager で前後画像へスワイプ移動
 * - ピンチズーム / ダブルタップズーム対応 (ズーム中はページめくりを無効化)
 * - シングルタップでヘッダーとシステムバーを隠す没入型表示 (Issue #33)
 * - 戻る際に最後に見ていた画像 ID を一覧へ返す (Issue #35)
 * - 表示位置は ViewModel が SavedStateHandle へ書き戻す (Issue #4)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    viewModel: ImageViewerViewModel,
    onBack: (itemId: String?, index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val containerSize = LocalWindowInfo.current.containerSize
    val view = LocalView.current

    // ビューアを開き直したら通常表示に戻る。ページ移動では維持される
    var immersive by remember { mutableStateOf(false) }

    // フルスクリーン中はシステムバーを隠し、画面離脱時は必ず戻す
    DisposableEffect(immersive, view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = state.initialIndex,
        pageCount = { state.images.size }
    )

    val closeViewer: () -> Unit = {
        onBack(
            state.images.getOrNull(pagerState.currentPage)?.id,
            pagerState.currentPage
        )
    }

    // 没入型表示中は戻る操作でまず通常表示に戻し、次の戻るで一覧へ返る
    BackHandler {
        if (immersive) immersive = false else closeViewer()
    }

    // 現在ページの変化を ViewModel に記録し、プロセス再生成後に復元できるようにする
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { viewModel.onPageChanged(it) }
    }

    // 前後 1 枚だけ画面サイズで先読みする。全件先読みはせず、メモリ圧迫時はキャッシュ側で解放される
    LaunchedEffect(pagerState.currentPage, state.images, containerSize) {
        val width = containerSize.width
        val height = containerSize.height
        if (width <= 0 || height <= 0) return@LaunchedEffect
        val page = pagerState.currentPage
        listOf(page - 1, page + 1).forEach { index ->
            val image = state.images.getOrNull(index) ?: return@forEach
            imageLoader.enqueue(image.toImageRequest(context, width, height))
        }
    }

    Scaffold(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!immersive) {
                TopAppBar(
                    title = {
                        // "3 / 120" 形式の位置表示
                        val current = if (state.images.isEmpty()) 0 else pagerState.currentPage
                        if (state.images.isNotEmpty()) {
                            Text("${current + 1} / ${state.images.size}")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = closeViewer) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る"
                            )
                        }
                    },
                    actions = {
                        val currentImage = state.images.getOrNull(pagerState.currentPage)
                        if (currentImage != null) {
                            val isFavorite = currentImage.id in state.favoriteIds
                            IconButton(
                                onClick = {
                                    viewModel.toggleFavorite(currentImage.id, isFavorite)
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = if (isFavorite) {
                                        "画像のお気に入りから解除"
                                    } else {
                                        "画像のお気に入りに追加"
                                    },
                                    tint = if (isFavorite) {
                                        Color(0xFFFFC107)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (state.images.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("画像がありません", textAlign = TextAlign.Center)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    val image = state.images.getOrNull(page) ?: return@HorizontalPager
                    ZoomableImage(
                        image = image,
                        onSingleTap = { immersive = !immersive }
                    )
                }
            }
        }
    }
}

/** ピンチズーム・ダブルタップ・シングルタップに対応した画像 */
@Composable
private fun ZoomableImage(
    image: ImageItem,
    onSingleTap: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(image.id, image.dateModifiedSec, image.size) {
        image.toImageRequest(context)
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        if (newScale > 1f) {
            // ズーム中のみパンを許可
            offset += panChange
        } else {
            offset = Offset.Zero
        }
    }

    // ズームが解除されたら位置も戻す
    LaunchedEffect(scale) {
        if (scale <= 1f && offset != Offset.Zero) {
            offset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // シングルタップで没入型表示を切り替える。
            // detectTapGestures はダブルタップ確定後に onTap を呼ぶため、意図しない切替を防げる。
            .pointerInput(image.uri) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
            // ピンチズーム。等倍時はパンを Pager に譲り、ズーム中だけパンを消費する
            .transformable(
                state = transformState,
                canPan = { scale > 1f }
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = image.displayName,
            contentScale = ContentScale.Fit,
            error = painterResource(R.drawable.ic_broken_image),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

/** Compose の View から所属 Activity を取り出す */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
