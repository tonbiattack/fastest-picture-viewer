package com.tonbiattack.fastestpictureviewer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 初回案内では選択を通知するだけで、バックアップを自動復元しない。 */
class InitialBackupPromptTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `あとでは復元画面へ進まず閉じる`() {
        var skips = 0
        var choices = 0
        composeRule.setContent {
            InitialBackupPrompt(onSkip = { skips++ }, onChooseBackup = { choices++ })
        }
        composeRule.onNodeWithText("バックアップから復元しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("あとで").performClick()
        composeRule.runOnIdle {
            assertEquals(1, skips)
            assertEquals(0, choices)
        }
    }

    @Test
    fun `バックアップを選ぶ操作だけが画面遷移を通知する`() {
        var skips = 0
        var choices = 0
        composeRule.setContent {
            InitialBackupPrompt(onSkip = { skips++ }, onChooseBackup = { choices++ })
        }
        composeRule.onNodeWithText("バックアップを選ぶ").performClick()
        composeRule.runOnIdle {
            assertEquals(0, skips)
            assertEquals(1, choices)
        }
    }
}
