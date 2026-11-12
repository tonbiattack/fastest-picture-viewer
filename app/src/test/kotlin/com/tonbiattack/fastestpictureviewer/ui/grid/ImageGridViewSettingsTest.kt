package com.tonbiattack.fastestpictureviewer.ui.grid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** フォルダ内画像グリッドの列数設定が縦横別に永続化されることのテスト (Issue #44) */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageGridViewSettingsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("image_grid_view_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `既定値は縦横とも3列`() {
        val settings = SharedPreferencesImageGridViewSettings(context)
        assertEquals(3, settings.columnCount.value)
        assertEquals(3, settings.portraitColumnCount.value)
        assertEquals(3, settings.landscapeColumnCount.value)
    }

    @Test
    fun `変更した列数は別インスタンスでも維持される`() {
        val first = SharedPreferencesImageGridViewSettings(context)
        first.setColumnCount(5)

        val second = SharedPreferencesImageGridViewSettings(context)
        assertEquals(5, second.columnCount.value)
    }

    @Test
    fun `範囲外の列数は2から6に丸められる`() {
        val settings = SharedPreferencesImageGridViewSettings(context)
        settings.setColumnCount(9)
        assertEquals(6, settings.columnCount.value)

        settings.setColumnCount(0)
        assertEquals(2, settings.columnCount.value)
    }

    @Test
    fun `フォルダ一覧の列数設定とは独立している`() {
        val folderPrefs = context.getSharedPreferences("folder_view_settings", Context.MODE_PRIVATE)
        folderPrefs.edit().putInt("column_count", 1).commit()

        val settings = SharedPreferencesImageGridViewSettings(context)
        assertEquals(3, settings.columnCount.value)

        settings.setColumnCount(6)
        assertEquals(1, folderPrefs.getInt("column_count", 0))
    }

    @Test
    fun `縦向きと横向きで別々の列数を保存する`() {
        val settings = SharedPreferencesImageGridViewSettings(context)
        settings.setLandscape(false)
        settings.setColumnCount(3)
        assertEquals(3, settings.portraitColumnCount.value)
        assertEquals(3, settings.landscapeColumnCount.value)

        settings.setLandscape(true)
        assertEquals(3, settings.columnCount.value)
        settings.setColumnCount(6)

        assertEquals(6, settings.landscapeColumnCount.value)
        // 縦向きは変わらない
        assertEquals(3, settings.portraitColumnCount.value)

        // 向きを戻すと縦向きの値が適用される
        settings.setLandscape(false)
        assertEquals(3, settings.columnCount.value)

        // 別インスタンス (アプリ再起動相当) でも維持される
        val reopened = SharedPreferencesImageGridViewSettings(context)
        assertEquals(3, reopened.portraitColumnCount.value)
        assertEquals(6, reopened.landscapeColumnCount.value)
    }

    @Test
    fun `旧バージョンの列数設定を縦横の初期値として引き継ぐ`() {
        context.getSharedPreferences("image_grid_view_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("column_count", 5)
            .commit()

        val settings = SharedPreferencesImageGridViewSettings(context)
        assertEquals(5, settings.portraitColumnCount.value)
        assertEquals(5, settings.landscapeColumnCount.value)
        assertEquals(5, settings.columnCount.value)
    }

    @Test
    fun `旧キーが壊れていても既定値にフォールバックする`() {
        context.getSharedPreferences("image_grid_view_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("column_count", 99)
            .commit()

        val settings = SharedPreferencesImageGridViewSettings(context)
        assertEquals(6, settings.portraitColumnCount.value)
    }
}
