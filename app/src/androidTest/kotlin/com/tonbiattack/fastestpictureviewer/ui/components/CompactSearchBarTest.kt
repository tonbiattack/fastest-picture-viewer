package com.tonbiattack.fastestpictureviewer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * コンパクトな検索バーの高さと操作のテスト (Issue #100)。
 *
 * 高さを測るため instrumented test として実機・エミュレータで実行する。
 * 以前の OutlinedTextField は最小 56dp で、上下余白を足すと 60dp 以上を占めていた。
 */
class CompactSearchBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun TestHost(
        onClear: () -> Unit = {}
    ) {
        var query by remember { mutableStateOf("") }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            CompactSearchBar(
                query = query,
                onQueryChange = { query = it },
                onClear = {
                    query = ""
                    onClear()
                },
                placeholder = "フォルダ名で検索",
                modifier = Modifier.testTag("search-bar")
            )
        }
    }

    @Test
    fun `検索バーの高さは44dp以下に収まる`() {
        composeRule.setContent { TestHost() }
        composeRule.waitForIdle()

        val bounds = composeRule.onNodeWithTag("search-bar")
            .fetchSemanticsNode()
            .boundsInRoot
        val density = composeRule.density.density
        val heightDp = bounds.height / density

        // 1dp の丸め誤差を許容する
        assertTrue(
            "検索バーが高すぎます: ${heightDp}dp（上限 ${CompactSearchBarHeight.value}dp）",
            heightDp <= CompactSearchBarHeight.value + 1f
        )
        // タップ領域が小さすぎないことも確認する
        assertTrue("検索バーが低すぎます: ${heightDp}dp", heightDp >= 40f)
    }

    @Test
    fun `入力すると文字が入りクリアで空に戻る`() {
        var cleared = false
        composeRule.setContent { TestHost(onClear = { cleared = true }) }
        composeRule.waitForIdle()

        // 入力前はプレースホルダーが見える
        composeRule.onNodeWithText("フォルダ名で検索").assertIsDisplayed()

        // 入力欄（テキスト設定可能なノード）に入力する
        editableNode().performTextInput("Screen")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Screen").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("検索をクリア").performClick()
        composeRule.waitForIdle()

        assertTrue("クリアが通知されていない", cleared)
        composeRule.onNodeWithText("フォルダ名で検索").assertIsDisplayed()
    }

    @Test
    fun `複数行にならず高さが入力後も変わらない`() {
        composeRule.setContent { TestHost() }
        composeRule.waitForIdle()

        val before = composeRule.onNodeWithTag("search-bar").fetchSemanticsNode().boundsInRoot.height

        // 長い文字列を入れても 1 行のままであること
        editableNode().performTextInput(
            "a very long folder name that would wrap to several lines otherwise"
        )
        composeRule.waitForIdle()

        val after = composeRule.onNodeWithTag("search-bar").fetchSemanticsNode().boundsInRoot.height
        assertEquals("入力で高さが変わっています", before, after, 0.5f)
    }

    /** 検索バー内の入力欄。testTag は外枠に付いているため、入力可能なノードで引く */
    private fun editableNode() =
        composeRule.onNode(
            androidx.compose.ui.test.hasSetTextAction()
        )
}
