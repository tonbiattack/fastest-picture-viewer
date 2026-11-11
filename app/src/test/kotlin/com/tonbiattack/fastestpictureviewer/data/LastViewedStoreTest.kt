package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 最後に開いていたフォルダ・画像の保存テスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LastViewedStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("last_viewed_folder", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `保存したフォルダと画像は別インスタンスでも復元できる`() {
        val first = SharedPreferencesLastViewedStore(context)
        first.save(
            folderKey = "external_primary:Download/",
            itemId = "content://media/external_primary/images/media/828680",
            itemIndex = 223
        )

        val second = SharedPreferencesLastViewedStore(context)
        val saved = second.lastViewed.value
        assertEquals("external_primary:Download/", saved?.folderKey)
        assertEquals("content://media/external_primary/images/media/828680", saved?.itemId)
        assertEquals(223, saved?.itemIndex)
    }

    @Test
    fun `空のフォルダや画像IDは保存しない`() {
        val store = SharedPreferencesLastViewedStore(context)
        store.save("", "content://image/1", 0)
        store.save("external_primary:Download/", "", 0)
        assertNull(store.lastViewed.value)
    }

    @Test
    fun `クリアすると履歴が消える`() {
        val store = SharedPreferencesLastViewedStore(context)
        store.save("external_primary:Download/", "content://image/1", 2)
        store.clear()

        assertNull(store.lastViewed.value)
        assertNull(SharedPreferencesLastViewedStore(context).lastViewed.value)
    }
}
