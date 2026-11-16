package com.tonbiattack.fastestpictureviewer.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tonbiattack.fastestpictureviewer.data.BackupData
import com.tonbiattack.fastestpictureviewer.data.BackupFolderRef
import com.tonbiattack.fastestpictureviewer.data.BackupSettings
import com.tonbiattack.fastestpictureviewer.data.BackupTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 復元の確認ダイアログの表示内容 (Issue #111)。
 *
 * 取り込み前に「何が追加され、何が上書きされるか」を利用者が確認できることを固定する。
 * 特に表示設定はバックアップの値で上書きされるため、上書き後の実値を出す。
 */
class BackupRestoreDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sample(): BackupData = BackupData(
        exportedAt = "2026-11-16T10:30:00Z",
        appVersionName = "0.1.0",
        tags = listOf(BackupTag(displayName = "旅行", normalizedName = "旅行")),
        folderTags = emptyList(),
        favoriteFolders = listOf(BackupFolderRef("external_primary", "Pictures/A/")),
        favoriteImages = emptyList(),
        excludedFolders = listOf(BackupFolderRef("external_primary", "Private/")),
        settings = BackupSettings(
            folderColumns = 4,
            sortMode = "RECENT",
            imageColumnsPortrait = 5,
            imageColumnsLandscape = 2,
            showResumeCard = false
        )
    )

    @Test
    fun `確認ダイアログに日時と上書きされる設定の実値が出る`() {
        val data = sample()
        composeRule.setContent {
            RestoreConfirmDialog(
                data = data,
                exportedAtLabel = data.exportedAt,
                settingsLabel = "フォルダ一覧: 4 列 / 並び順: 最近閲覧順",
                onConfirm = {},
                onCancel = {}
            )
        }

        composeRule.onNodeWithText("バックアップを取り込みますか？").assertIsDisplayed()
        // 日時が記録値として出る
        composeRule.onNodeWithText("書き出し日時（記録値）: 2026-11-16T10:30:00Z").assertIsDisplayed()
        // 上書きされる実値が出る
        composeRule.onNodeWithText("フォルダ一覧: 4 列 / 並び順: 最近閲覧順").assertIsDisplayed()
        // 追加と上書きの違いが書かれている
        composeRule.onNodeWithText(
            "お気に入り・タグ・除外フォルダは、いまのデータを消さずに追加します。"
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "表示設定は、いまの設定をバックアップの値で上書きします。"
        ).assertIsDisplayed()
        // 対象外も明示する
        composeRule.onNodeWithText(
            "画像そのもの・閲覧履歴・サムネイルは対象外です（復元されません）。"
        ).assertIsDisplayed()
    }

    @Test
    fun `取り込むとキャンセルがそれぞれ通知される`() {
        var confirmed = 0
        var cancelled = 0
        val data = sample()
        composeRule.setContent {
            RestoreConfirmDialog(
                data = data,
                exportedAtLabel = "不明",
                settingsLabel = "フォルダ一覧: 2 列",
                onConfirm = { confirmed++ },
                onCancel = { cancelled++ }
            )
        }

        composeRule.onNodeWithText("取り込む").performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(0, cancelled)
        }
    }

    @Test
    fun `キャンセルは取り込まずに閉じる`() {
        var confirmed = 0
        var cancelled = 0
        val data = sample()
        composeRule.setContent {
            RestoreConfirmDialog(
                data = data,
                exportedAtLabel = "不明",
                settingsLabel = "フォルダ一覧: 2 列",
                onConfirm = { confirmed++ },
                onCancel = { cancelled++ }
            )
        }

        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, cancelled)
        }
    }
}
