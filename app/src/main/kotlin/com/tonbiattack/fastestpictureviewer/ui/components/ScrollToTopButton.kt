package com.tonbiattack.fastestpictureviewer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 一覧をワンタップで先頭へ戻すボタン (Issue #76)。
 *
 * (Issue #61 と同じ理由で `derivedStateOf` を使い、スクロール中に毎フレーム再コンポーズしない)
 */
@Composable
fun ScrollToTopButton(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    tolerancePx: Int = ScrollToTopMath.DEFAULT_TOLERANCE_PX
) {
    val visible by remember(state, tolerancePx) {
        derivedStateOf {
            ScrollToTopMath.shouldShow(
                firstVisibleIndex = state.firstVisibleItemIndex,
                firstVisibleOffset = state.firstVisibleItemScrollOffset,
                tolerancePx = tolerancePx
            )
        }
    }

    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier
    ) {
        FloatingActionButton(
            onClick = {
                scope.launch {
                    // 滑らかに戻す。0 件のときは何もしない
                    if (state.layoutInfo.totalItemsCount > 0) {
                        state.animateScrollToItem(ScrollToTopMath.TOP_INDEX)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "先頭へ戻る",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
