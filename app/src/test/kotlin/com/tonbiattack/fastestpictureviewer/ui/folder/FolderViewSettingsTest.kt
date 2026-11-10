package com.tonbiattack.fastestpictureviewer.ui.folder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** フォルダ一覧の列数設定が永続化されることのテスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderViewSettingsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("folder_view_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `既定値は2列`() {
        val settings = SharedPreferencesFolderViewSettings(context)
        assertEquals(2, settings.columnCount.value)
    }

    @Test
    fun `変更した列数は別インスタンスでも維持される`() {
        val first = SharedPreferencesFolderViewSettings(context)
        first.setColumnCount(3)

        val second = SharedPreferencesFolderViewSettings(context)
        assertEquals(3, second.columnCount.value)
    }

    @Test
    fun `範囲外の列数は1から4に丸められる`() {
        val settings = SharedPreferencesFolderViewSettings(context)
        settings.setColumnCount(9)
        assertEquals(4, settings.columnCount.value)

        settings.setColumnCount(0)
        assertEquals(1, settings.columnCount.value)
    }

    @Test
    fun `既定の並び順は更新日時順`() {
        val settings = SharedPreferencesFolderViewSettings(context)
        assertEquals(FolderSortMode.LATEST, settings.sortMode.value)
    }

    @Test
    fun `変更した並び順は別インスタンスでも維持される`() {
        val first = SharedPreferencesFolderViewSettings(context)
        first.setSortMode(FolderSortMode.RECENT)

        val second = SharedPreferencesFolderViewSettings(context)
        assertEquals(FolderSortMode.RECENT, second.sortMode.value)
    }
}
