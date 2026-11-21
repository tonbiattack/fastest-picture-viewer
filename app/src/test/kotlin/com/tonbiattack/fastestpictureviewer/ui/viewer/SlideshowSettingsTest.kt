package com.tonbiattack.fastestpictureviewer.ui.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * スライドショーの表示間隔の保存のテスト (Issue #124)。
 *
 * 選んだ秒数が次回起動時も保持されることを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlideshowSettingsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun settings() = SharedPreferencesSlideshowSettings(context)

    @Test
    fun `初期値は3秒`() {
        assertEquals(SlideshowInterval.SEC_3, settings().interval.value)
    }

    @Test
    fun `保存した間隔が読み出せる`() {
        settings().setInterval(SlideshowInterval.SEC_10)

        // 別インスタンスで読み直す（アプリ再起動相当）
        assertEquals(SlideshowInterval.SEC_10, settings().interval.value)
    }

    @Test
    fun `変更すると即時反映される`() {
        val store = settings()
        store.setInterval(SlideshowInterval.SEC_15)

        assertEquals(SlideshowInterval.SEC_15, store.interval.value)
    }

    @Test
    fun `すべての選択肢を保存できる`() {
        SlideshowInterval.ALL.forEach { interval ->
            settings().setInterval(interval)
            assertEquals(interval, settings().interval.value)
        }
    }

    @Test
    fun `壊れた保存値は既定値へ戻す`() {
        context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("interval_seconds", 7)
            .commit()

        assertEquals(SlideshowInterval.DEFAULT, settings().interval.value)
    }
}
