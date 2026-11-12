package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 写真一覧のピンチ操作で一時的に列数が変わり、指を離すと戻ることを
 * 実際の Compose 入力 (マルチタッチ) で検証する (Issue #45)。
 *
 * 通常の JVM テストでは 2 本指の入力を再現できないため、instrumented test として実機で実行する。
 */
class PinchColumnGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ピンチインとピンチアウトで一時的な列数が変わり指を離すと戻る`() {
        // ピンチ中に観測された列数 (null = 保存値へ復帰)
        val observed = mutableListOf<Int?>()
        var base = 3

        composeRule.setContent {
            var preview by remember { mutableStateOf<Int?>(null) }
            val columns = preview ?: base
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .testTag("grid")
                    .pinchToColumns(
                        baseColumns = base,
                        onPreviewChange = { value ->
                            preview = value
                            observed += value
                        }
                    )
            ) {
                PinchTestGrid(columns)
            }
        }

        composeRule.waitForIdle()

        // ピンチイン: 2本指を近づけて列数を増やす
        composeRule.onNodeWithTag("grid").performTouchInput {
            pinch(
                start0 = center - androidx.compose.ui.geometry.Offset(120f, 0f),
                end0 = center - androidx.compose.ui.geometry.Offset(30f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(120f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(30f, 0f)
            )
        }
        composeRule.waitForIdle()

        // 少なくとも 1 回は基準より多い列数が通知され、最後は null に戻る
        assertEquals("ピンチ中に列数が変化していない", true, observed.filterNotNull().any { it > base })
        assertNull("指を離したら保存値へ戻らない", observed.lastOrNull())

        // ピンチアウト: 2本指を広げて列数を減らす
        observed.clear()
        composeRule.onNodeWithTag("grid").performTouchInput {
            pinch(
                start0 = center - androidx.compose.ui.geometry.Offset(30f, 0f),
                end0 = center - androidx.compose.ui.geometry.Offset(140f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(30f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(140f, 0f)
            )
        }
        composeRule.waitForIdle()

        // 減る方向に変化する (3 → 2)
        assertEquals("ピンチアウトで列数が減らない", true, observed.filterNotNull().any { it < base })
        assertNull(observed.lastOrNull())

        // 保存済みの値は変更されていない
        assertEquals(3, base)
        assertNotNull(composeRule.onNodeWithTag("grid"))
    }

    @Composable
    private fun PinchTestGrid(columns: Int) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.size(300.dp).testTag("grid-content")
        ) {
            items((0 until 24).toList()) { index ->
                Box(modifier = Modifier.size(40.dp).testTag("cell-$index"))
            }
        }
    }
}
