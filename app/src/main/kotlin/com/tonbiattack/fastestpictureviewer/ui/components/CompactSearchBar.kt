package com.tonbiattack.fastestpictureviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 検索バーの高さ (Issue #100)。
 *
 * フォルダ一覧・画像一覧の上部バー内に 1 行で収め、一覧の表示領域を削らない。
 * 以前の OutlinedTextField は最小高さ 56dp と上下余白で 60dp 以上を占めていた。
 * 40dp にするとタップ領域が最小推奨を下回るため 44dp を下限にする。
 */
val CompactSearchBarHeight = 44.dp

/** 検索バー内のアイコンボタンの大きさ。44dp の 1 行に収めるため最小値より小さくする */
private val SearchIconButtonSize = 40.dp

/**
 * 画面上部に置く 1 行の検索バー (Issue #100)。
 *
 * - 1 行のみ。複数行にならず、高さは [CompactSearchBarHeight] に固定する
 * - 入力が空のときはプレースホルダーを出し、入力後はクリアボタンを出す
 * - IME の検索キーでキーボードを閉じる。検索語は入力のたびに反映する
 * - 検索モードの終了はヘッダーの検索アイコンで行う（アイコンが閉じる表示に変わる）
 *
 * @param focusRequestKey 0 より大きい値に変わったときだけフォーカスとキーボードを出す。
 *   検索を開いた直後の 1 回に限定し、一覧を眺めている間はキーボードを出さない
 */
@Composable
fun CompactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequestKey: Int = 0
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(focusRequestKey) {
        if (focusRequestKey > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CompactSearchBarHeight)
            .clip(RoundedCornerShape(CompactSearchBarHeight / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(SearchIconButtonSize)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "検索をクリア",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
