package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.ui.folder.SharedPreferencesFolderViewSettings
import com.tonbiattack.fastestpictureviewer.ui.grid.SharedPreferencesImageGridViewSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewSettingsBackupAccessTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("folder_view_settings", "image_grid_view_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `設定を復元して再生成しても縦横別の値を保持する`() = runTest {
        val folder = SharedPreferencesFolderViewSettings(context)
        val grid = SharedPreferencesImageGridViewSettings(context)
        folder.setColumnCount(4)
        folder.setSortMode(FolderSortMode.RECENT)
        folder.setShowResumeCard(false)
        grid.setColumnCount(2)
        grid.setLandscape(true)
        grid.setColumnCount(6)
        val saved = ViewSettingsBackupAccess(folder, grid).snapshot()

        folder.setColumnCount(1)
        grid.setColumnCount(3)
        ViewSettingsBackupAccess(folder, grid).apply(saved)

        assertEquals(6, grid.columnCount.value)
        val reopenedFolder = SharedPreferencesFolderViewSettings(context)
        val reopenedGrid = SharedPreferencesImageGridViewSettings(context)
        assertEquals(saved, ViewSettingsBackupAccess(reopenedFolder, reopenedGrid).snapshot())
        reopenedGrid.setLandscape(true)
        assertEquals(6, reopenedGrid.columnCount.value)
        reopenedGrid.setLandscape(false)
        assertEquals(2, reopenedGrid.columnCount.value)
    }

    @Test
    fun `縦画面で復元した場合も縦画面の列数を使う`() = runTest {
        val folder = SharedPreferencesFolderViewSettings(context)
        val grid = SharedPreferencesImageGridViewSettings(context)
        ViewSettingsBackupAccess(folder, grid).apply(BackupSettings(3, "LATEST", 4, 6, true))
        assertEquals(4, grid.columnCount.value)
        grid.setLandscape(true)
        assertEquals(6, grid.columnCount.value)
    }

    // ---- 古いバックアップで新しい設定を壊さない (Issue #151) ----

    @Test
    fun `古いバックアップに無い設定は現在の値を保つ`() = runTest {
        val folder = SharedPreferencesFolderViewSettings(context)
        val grid = SharedPreferencesImageGridViewSettings(context)

        // 現在の設定（バックアップより新しい項目）
        grid.setSortMode(com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_OLDEST)
        grid.setRestoreScrollPosition(false)
        grid.setFolderSortMode("v:Pictures/A/", com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_NEWEST)
        folder.setShowStaleMediaNotice(false)

        // 古い形式のバックアップ（新しい項目が無い）
        val legacy = BackupSettings(
            folderColumns = 3,
            sortMode = "RECENT",
            imageColumnsPortrait = 4,
            imageColumnsLandscape = 5,
            showResumeCard = false,
            presentFields = setOf(
                "folderColumns", "sortMode",
                "imageColumnsPortrait", "imageColumnsLandscape", "showResumeCard"
            )
        )
        ViewSettingsBackupAccess(folder, grid).apply(legacy)

        // 古い項目は反映される
        assertEquals(3, folder.columnCount.value)
        assertEquals(4, grid.portraitColumnCount.value)
        // 新しい項目は壊されない
        assertEquals(
            com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_OLDEST,
            grid.sortMode.value
        )
        assertFalse(grid.restoreScrollPosition.value)
        assertFalse(folder.showStaleMediaNotice.value)
        assertEquals(
            com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_NEWEST,
            grid.folderSortOverrides.value["v:Pictures/A/"]
        )
    }

    @Test
    fun `新しいバックアップは既存の並び順を上書きする`() = runTest {
        val folder = SharedPreferencesFolderViewSettings(context)
        val grid = SharedPreferencesImageGridViewSettings(context)
        grid.setFolderSortMode("v:Pictures/A/", com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_NEWEST)

        val settings = BackupSettings(
            folderColumns = 2,
            sortMode = "LATEST",
            imageColumnsPortrait = 3,
            imageColumnsLandscape = 3,
            showResumeCard = true,
            imageSortMode = "DATE_OLDEST",
            folderSortOverrides = mapOf("v:Pictures/B/" to "NAME"),
            restoreScrollPosition = false,
            showStaleMediaNotice = false,
            slideshowIntervalSec = 10
        )
        ViewSettingsBackupAccess(folder, grid).apply(settings)

        assertEquals(
            com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.DATE_OLDEST,
            grid.sortMode.value
        )
        assertEquals(
            com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode.NAME,
            grid.folderSortOverrides.value["v:Pictures/B/"]
        )
        assertFalse(grid.restoreScrollPosition.value)
        assertFalse(folder.showStaleMediaNotice.value)
    }
}
