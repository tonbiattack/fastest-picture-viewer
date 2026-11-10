package com.tonbiattack.fastestpictureviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 縦スクロール一覧の右端に表示する高速スクローラー。
 *
 * つまみをドラッグすると、一覧の先頭〜末尾のインデックスへ直接ジャンプする。
 * LazyGridState を操作するだけなので、一覧の再取得や再走査は発生しない。
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    minItemsToShow: Int = 20,
    trackWidth: Dp = 28.dp,
    thumbWidth: Dp = 8.dp,
    thumbMinHeight: Dp = 40.dp
) {
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size

    // 画面内に収まる一覧では表示しない
    if (totalItems <= 0 || totalItems <= visibleItems || totalItems < minItemsToShow) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }

    val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index
        ?: state.firstVisibleItemIndex
    val progress = FastScrollMath.progress(firstVisibleIndex, totalItems)
    val thumbFraction = FastScrollMath.thumbFraction(visibleItems, totalItems)

    val thumbMinHeightPx = with(density) { thumbMinHeight.toPx() }
    val thumbHeightPx = max(thumbMinHeightPx, trackHeightPx * thumbFraction)
    val thumbOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f) * progress
    val active = dragging || state.isScrollInProgress
    val thumbAlpha = if (active) 1f else 0.35f

    fun jumpTo(y: Float) {
        if (trackHeightPx <= 0f) return
        val fraction = (y / trackHeightPx).coerceIn(0f, 1f)
        val target = FastScrollMath.targetIndex(fraction, totalItems)
        // ドラッグ中は多数のイベントが来るため、最後の位置だけを適用する
        scrollJob.value?.cancel()
        scrollJob.value = scope.launch { state.scrollToItem(target) }
    }

    Box(
        modifier = modifier
            .width(trackWidth)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(totalItems, trackHeightPx) {
                if (trackHeightPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    down.consume()
                    jumpTo(down.position.y)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 合成スワイプでは UP 位置が MOVE として来ないことがあるため、
                            // 指を離した位置もスクロール先として反映する
                            change.consume()
                            jumpTo(change.position.y)
                            break
                        }
                        change.consume()
                        jumpTo(change.position.y)
                    }
                    dragging = false
                }
            }
            .semantics {
                contentDescription = "高速スクロール"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
    ) {
        // 薄いトラック
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
        )
        // つまみ
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .width(thumbWidth)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha))
        )
    }
}

/** FastScroller の位置計算 (純粋ロジック) */
object FastScrollMath {

    /** ドラッグ位置 (0..1) からスクロール先のアイテムインデックスを求める */
    fun targetIndex(fraction: Float, totalItems: Int): Int {
        if (totalItems <= 0) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
    }

    /** 先頭アイテムの位置からつまみの進捗 (0..1) を求める */
    fun progress(firstVisibleIndex: Int, totalItems: Int): Float {
        if (totalItems <= 1) return 0f
        return (firstVisibleIndex.toFloat() / (totalItems - 1)).coerceIn(0f, 1f)
    }

    /** つまみの高さ比率。小さくなりすぎないよう下限を設ける */
    fun thumbFraction(visibleItems: Int, totalItems: Int): Float {
        if (totalItems <= 0) return 1f
        return (visibleItems.toFloat() / totalItems).coerceIn(0.06f, 1f)
    }
}
