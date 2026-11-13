package com.tonbiattack.fastestpictureviewer.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 画像詳細画面。
 *
 * - HorizontalPager で前後画像へスワイプ移動
 * - シングルタップでヘッダーとシステムバーを隠す没入型表示 (Issue #33)
 * - 下部のページスクラバーで大量画像を素早く移動 (Issue #37)
 * - ダブルタップした位置を中心にズーム (Issue #38)
 * - 共有・画像情報シート (Issue #40)
 * - 戻る際に最後に見ていた画像 ID を一覧へ返す (Issue #35)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    viewModel: ImageViewerViewModel,
    onBack: (itemId: String?, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAdjacentFolder: (folderKey: String, fromEnd: Boolean) -> Unit = { _, _ -> }
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val adjacent = viewModel.adjacentFolders.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val containerSize = LocalWindowInfo.current.containerSize
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // ビューアを開き直したら通常表示に戻る。ページ移動では維持される
    var immersive by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }

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
    val currentIndex = pagerState.currentPage.coerceIn(
        0,
        (state.images.size - 1).coerceAtLeast(0)
    )
    val currentImage = state.images.getOrNull(currentIndex)

    val closeViewer: () -> Unit = {
        onBack(state.images.getOrNull(pagerState.currentPage)?.id, pagerState.currentPage)
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

    // 前のフォルダから移動してきた場合は、画像が揃った時点で末尾の画像を開く (Issue #57)
    var appliedStartFromEnd by remember(state.images.size) { mutableStateOf(false) }
    LaunchedEffect(state.images.size, state.startsWithLastImage) {
        if (!appliedStartFromEnd && state.startsWithLastImage && state.images.isNotEmpty()) {
            appliedStartFromEnd = true
            pagerState.scrollToPage(state.images.lastIndex)
        }
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

    if (showInfo && currentImage != null) {
        ImageInfoSheet(image = currentImage, onDismiss = { showInfo = false })
    }

    Scaffold(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!immersive) {
                TopAppBar(
                    title = {
                        if (state.images.isNotEmpty()) {
                            Text("${currentIndex + 1} / ${state.images.size}")
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
                            // ランダムジャンプ (Issue #58)。ページを移るだけなので戻る階層は増えない
                            if (state.images.size > 1) {
                                IconButton(
                                    onClick = {
                                        val target = viewModel.randomJumpTarget(currentIndex)
                                            ?: return@IconButton
                                        // 取得直後に削除された場合に備え、必ず現在の一覧で確認する
                                        if (state.images.getOrNull(target) == null) return@IconButton
                                        scope.launch { pagerState.scrollToPage(target) }
                                    }
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_dice),
                                        contentDescription = "ランダムジャンプ"
                                    )
                                }
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
                                        text = { Text("共有") },
                                        onClick = {
                                            menuExpanded = false
                                            shareImage(context, currentImage)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("画像情報") },
                                        onClick = {
                                            menuExpanded = false
                                            showInfo = true
                                        }
                                    )
                                    // 前後のフォルダへ移動 (Issue #57)。一覧の並び順・絞り込みを引き継ぐ
                                    if (adjacent.hasPrevious) {
                                        DropdownMenuItem(
                                            text = { Text("前のフォルダへ") },
                                            onClick = {
                                                menuExpanded = false
                                                adjacent.previous?.let { target ->
                                                    onOpenAdjacentFolder(target, true)
                                                }
                                            }
                                        )
                                    }
                                    if (adjacent.hasNext) {
                                        DropdownMenuItem(
                                            text = { Text("次のフォルダへ") },
                                            onClick = {
                                                menuExpanded = false
                                                adjacent.next?.let { target ->
                                                    onOpenAdjacentFolder(target, false)
                                                }
                                            }
                                        )
                                    }
                                }
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
                if (!immersive && state.images.size > 1) {
                    val displayPage = if (isScrubbing) {
                        scrubValue.roundToInt().coerceIn(0, state.images.lastIndex)
                    } else {
                        currentIndex
                    }
                    ViewerScrubber(
                        page = displayPage,
                        pageCount = state.images.size,
                        displayName = state.images.getOrNull(displayPage)?.displayName.orEmpty(),
                        isScrubbing = isScrubbing,
                        scrubValue = if (isScrubbing) scrubValue else displayPage.toFloat(),
                        onScrub = { value ->
                            isScrubbing = true
                            scrubValue = value
                        },
                        onScrubFinished = {
                            val target = scrubValue.roundToInt()
                                .coerceIn(0, state.images.lastIndex)
                            isScrubbing = false
                            scope.launch { pagerState.scrollToPage(target) }
                        }
                    )
                }
            }
        }
    }
}

/** サムネイルを表示しない軽量なページスクラバー。ドラッグ中はラベルだけを更新する */
@Composable
private fun ViewerScrubber(
    page: Int,
    pageCount: Int,
    displayName: String,
    isScrubbing: Boolean,
    scrubValue: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${page + 1} / $pageCount",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = scrubValue,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..(pageCount - 1).toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "ページスクラバー" }
        )
        Text(
            text = if (isScrubbing) "離すとこのページへ移動" else "ドラッグでページ移動",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 画像情報シート。メタデータのみを使い、画像の再デコードは行わない */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageInfoSheet(image: ImageItem, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Text("画像情報", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            InfoRow("ファイル名", image.displayName)
            InfoRow("フォルダ", image.folderName)
            InfoRow("パス", "${image.volumeName}:${image.relativePath}")
            InfoRow("更新日時", formatDate(image.dateModifiedSec))
            InfoRow("サイズ", formatBytes(image.size))
            InfoRow(
                "解像度",
                if (image.width != null && image.height != null) {
                    "${image.width} × ${image.height}"
                } else {
                    "不明"
                }
            )
            InfoRow("MIME", image.mimeType.orEmpty().ifBlank { "不明" })
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
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
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale > 1f) {
            ZoomMath.clampOffset(
                offset = offset + panChange,
                viewportWidth = viewportSize.width,
                viewportHeight = viewportSize.height,
                scale = newScale
            )
        } else {
            Offset.Zero
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
            .onSizeChanged { viewportSize = it }
            // シングルタップで没入型表示を切り替える。
            // detectTapGestures はダブルタップ確定後に onTap を呼ぶため、意図しない切替を防げる。
            .pointerInput(image.uri) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                            offset = ZoomMath.doubleTapOffset(
                                tap = tap,
                                viewportWidth = viewportSize.width,
                                viewportHeight = viewportSize.height,
                                targetScale = 2f
                            )
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

private fun shareImage(context: Context, image: ImageItem) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = image.mimeType.orEmpty().ifBlank { "image/*" }
        putExtra(Intent.EXTRA_STREAM, image.uri.toUri())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "画像を共有").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            Toast.makeText(
                context,
                "共有できるアプリが見つかりません",
                Toast.LENGTH_SHORT
            ).show()
        }
}

private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "不明"
    val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochSeconds * 1000))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "不明"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", kb)
    }
}

/** Compose の View から所属 Activity を取り出す */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
