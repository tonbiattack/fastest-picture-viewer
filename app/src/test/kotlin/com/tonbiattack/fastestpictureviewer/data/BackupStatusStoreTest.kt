package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * バックアップの書き出し状況の保存テスト (Issue #108)。
 *
 * 実 SharedPreferences を使い、再起動（別インスタンス）でも状態が残ることを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupStatusStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // テスト間で状態を持ち越さない
        context.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newStore(): BackupStatusStore = SharedPreferencesBackupStatusStore(context)

    @Test
    fun `初期状態は未書き出しで変更なし`() {
        val status = newStore().status.value

        assertFalse("初期状態で書き出し済みになっている", status.hasExported)
        assertFalse("初期状態で変更ありになっている", status.hasChangesSinceExport)
        assertEquals(0L, status.lastExportedAtSec)
    }

    @Test
    fun `変更を記録できる`() {
        val store = newStore()

        store.markChanged()

        assertTrue("変更が記録されていない", store.status.value.hasChangesSinceExport)
    }

    @Test
    fun `書き出しを記録すると日時が入り変更フラグが下りる`() {
        val store = newStore()
        store.markChanged()

        store.markExported(atSec = 1_700_000_000L)

        val status = store.status.value
        assertTrue("書き出し済みになっていない", status.hasExported)
        assertEquals(1_700_000_000L, status.lastExportedAtSec)
        assertFalse("変更フラグが下りていない", status.hasChangesSinceExport)
    }

    @Test
    fun `書き出し後に再度変更すると変更フラグが立つ`() {
        val store = newStore()
        store.markExported(atSec = 1_700_000_000L)

        store.markChanged()

        assertTrue("書き出し後の変更が記録されていない", store.status.value.hasChangesSinceExport)
        // 日時は保たれる
        assertEquals(1_700_000_000L, store.status.value.lastExportedAtSec)
    }

    @Test
    fun `再起動しても状態が残る`() {
        newStore().apply {
            markChanged()
            markExported(atSec = 1_700_000_000L)
            markChanged()
        }

        // 別インスタンス = アプリの再起動に相当
        val restarted = newStore().status.value

        assertTrue("再起動後に書き出し済みが消えた", restarted.hasExported)
        assertEquals(1_700_000_000L, restarted.lastExportedAtSec)
        assertTrue("再起動後に変更フラグが消えた", restarted.hasChangesSinceExport)
    }

    @Test
    fun `負の時刻は0として扱う`() {
        val store = newStore()

        store.markExported(atSec = -100L)

        assertEquals(0L, store.status.value.lastExportedAtSec)
        assertFalse("負の時刻で書き出し済みになっている", store.status.value.hasExported)
    }

    @Test
    fun `変更を繰り返しても状態は変わらない`() {
        val store = newStore()

        store.markChanged()
        store.markChanged()
        store.markChanged()

        assertTrue(store.status.value.hasChangesSinceExport)
    }
}
