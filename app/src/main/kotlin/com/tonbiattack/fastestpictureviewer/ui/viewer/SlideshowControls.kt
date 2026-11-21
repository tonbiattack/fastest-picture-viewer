package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tonbiattack.fastestpictureviewer.R

/**
 * スライドショーの操作バー (Issue #124)。
 *
 * 再生・一時停止・前後移動・終了・表示間隔の変更をまとめる。
 * 画面下部に置き、画像をできるだけ大きく見せられるよう 2 段に収める。
 */
@Composable
fun SlideshowControls(
    state: SlideshowState,
    interval: SlideshowInterval,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onIntervalChange: (SlideshowInterval) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "前の画像へ"
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    if (state.isPlaying) {
                        // 一時停止は「停止」と同じ四角で表す（material-icons-core に Pause が無い）
                        Icon(
                            painterResource(R.drawable.ic_pause),
                            contentDescription = "一時停止"
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "再生")
                    }
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "次の画像へ"
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Close, contentDescription = "スライドショーを終了")
                }
            }
            // 表示間隔。選択肢が 7 つあるため横スクロールで収める
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "間隔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SlideshowInterval.ALL.forEach { candidate ->
                    FilterChip(
                        selected = candidate == interval,
                        onClick = { onIntervalChange(candidate) },
                        label = { Text(candidate.label) },
                        modifier = Modifier.size(width = 64.dp, height = 32.dp)
                    )
                }
            }
        }
    }
}
